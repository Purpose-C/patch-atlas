package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.repository.ParentRevisionCheckResult;
import io.github.patchatlas.repository.ParentRevisionValidator;
import io.github.patchatlas.replay.AttemptPhase;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.SingleAttemptEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 对一个已排序候选执行有界、固定顺序的原生 Maven + Docker 动态资格检查。 */
public final class DynamicCaseQualifier {

    public static final Duration QUALIFICATION_TIMEOUT = Duration.ofMinutes(20);

    public enum ExclusionCode {
        CHECKOUT_FAILED,
        QUALIFICATION_TIMEOUT,
        WARMUP_FAILED,
        BUGGY_TRIGGER_MISMATCH,
        FIXED_TRIGGER_MISMATCH,
        TRIGGER_FLAKY,
        OFFLINE_REPLAY_FAILED,
        PARENT_REVISION_MISMATCH
    }

    public record Input(
            String caseId,
            String repositoryUrl,
            String buggyRevision,
            String fixedRevision,
            String modulePath,
            TargetTest targetTest,
            String knownTriggerPatch,
            String javaVersion) {
        public Input {
            BenchmarkArtifacts.requireText(caseId, "caseId");
            BenchmarkArtifacts.requireText(repositoryUrl, "repositoryUrl");
            requireSha(buggyRevision, "buggyRevision");
            requireSha(fixedRevision, "fixedRevision");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(targetTest, "targetTest");
            BenchmarkArtifacts.requireText(knownTriggerPatch, "knownTriggerPatch");
            if (!javaVersion.equals("17") && !javaVersion.equals("21")) {
                throw new IllegalArgumentException("javaVersion must be 17 or 21");
            }
        }
    }

    public record Stage(String name, String result, long durationMs) {
        public Stage {
            BenchmarkArtifacts.requireText(name, "name");
            BenchmarkArtifacts.requireText(result, "result");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
        }
    }

    public sealed interface Result permits Result.Eligible, Result.Excluded {
        List<Stage> stages();

        record Eligible(List<Stage> stages) implements Result {
            public Eligible {
                stages = List.copyOf(stages);
            }
        }

        record Excluded(ExclusionCode code, List<Stage> stages) implements Result {
            public Excluded {
                Objects.requireNonNull(code, "code");
                stages = List.copyOf(stages);
            }
        }
    }

    @FunctionalInterface
    interface CheckoutPort {
        Optional<Path> checkout(String repositoryUrl, String revision, String workspaceId);
    }

    interface TriggerPort {
        boolean applyToBuggy(Path workspace, Input input, MavenExecutionPolicy policy);

        boolean verifyOnFixed(Path workspace, Input input, MavenExecutionPolicy policy);
    }

    @FunctionalInterface
    interface WarmupPort {
        boolean warm(Path workspace, MavenTestCommand command);
    }

    @FunctionalInterface
    interface ReplayPort {
        SideExecutionResult replay(Path workspace, MavenTestCommand command, TargetTest target);
    }

    @FunctionalInterface
    interface ParentRevisionPort {
        boolean matches(Path workspace, String buggyRevision, String fixedRevision);
    }

    private final CheckoutPort checkout;
    private final TriggerPort trigger;
    private final WarmupPort warmup;
    private final ReplayPort replay;
    private final ParentRevisionPort parentRevision;

    public DynamicCaseQualifier(
            BenchmarkGitWorkspace git,
            PatchGate patchGate,
            DependencyWarmupRunner warmupRunner,
            SideReplayRunner replayRunner) {
        this(
                (url, revision, id) -> switch (git.checkout(url, revision, id)) {
                    case BenchmarkGitWorkspace.CheckoutResult.Success success ->
                        Optional.of(success.workspace());
                    case BenchmarkGitWorkspace.CheckoutResult.Failure ignored -> Optional.empty();
                },
                new TriggerPort() {
                    @Override
                    public boolean applyToBuggy(
                            Path workspace, Input input, MavenExecutionPolicy policy) {
                        return patchGate.prepare(
                                        workspace,
                                        input.modulePath(),
                                        draft(input),
                                        policy,
                                        CompletionDiagnostics.unknown())
                                instanceof PatchPreparationResult.PreparedCandidate;
                    }

                    @Override
                    public boolean verifyOnFixed(
                            Path workspace, Input input, MavenExecutionPolicy policy) {
                        return patchGate.verifyAlreadyApplied(
                                        workspace,
                                        input.modulePath(),
                                        draft(input),
                                        policy,
                                        CompletionDiagnostics.unknown())
                                instanceof PatchPreparationResult.PreparedCandidate;
                    }
                },
                (workspace, command) -> warmupRunner.warm(workspace, command).isEmpty(),
                replayRunner::runSide,
                matchingParentRevision(new ParentRevisionValidator()));
    }

    DynamicCaseQualifier(
            CheckoutPort checkout,
            TriggerPort trigger,
            WarmupPort warmup,
            ReplayPort replay) {
        this(checkout, trigger, warmup, replay, (workspace, buggyRevision, fixedRevision) -> true);
    }

    DynamicCaseQualifier(
            CheckoutPort checkout,
            TriggerPort trigger,
            WarmupPort warmup,
            ReplayPort replay,
            ParentRevisionPort parentRevision) {
        this.checkout = Objects.requireNonNull(checkout, "checkout");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.warmup = Objects.requireNonNull(warmup, "warmup");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.parentRevision = Objects.requireNonNull(parentRevision, "parentRevision");
    }

    static ParentRevisionPort matchingParentRevision(ParentRevisionValidator validator) {
        Objects.requireNonNull(validator, "validator");
        return (workspace, buggyRevision, fixedRevision) ->
                validator.check(workspace.toFile(), buggyRevision, fixedRevision)
                        instanceof ParentRevisionCheckResult.Match;
    }

    public Result qualify(Input input) {
        Objects.requireNonNull(input, "input");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<Result> future = executor.submit(() -> qualifyWithinBudget(input));
        try {
            return future.get(QUALIFICATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return new Result.Excluded(
                    ExclusionCode.QUALIFICATION_TIMEOUT,
                    List.of(new Stage("qualification", "QUALIFICATION_TIMEOUT", QUALIFICATION_TIMEOUT.toMillis())));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new Result.Excluded(
                    ExclusionCode.QUALIFICATION_TIMEOUT,
                    List.of(new Stage("qualification", "QUALIFICATION_TIMEOUT", 0)));
        } catch (ExecutionException ex) {
            return new Result.Excluded(
                    ExclusionCode.OFFLINE_REPLAY_FAILED,
                    List.of(new Stage("qualification", "OFFLINE_REPLAY_FAILED", 0)));
        } finally {
            executor.shutdownNow();
        }
    }

    Result qualifyWithinBudget(Input input) {
        List<Stage> stages = new ArrayList<>();
        Optional<Path> buggy = timed(
                stages,
                "checkout_buggy",
                () -> checkout.checkout(
                        input.repositoryUrl(), input.buggyRevision(), input.caseId() + "-buggy"));
        if (buggy.isEmpty()) {
            return excluded(ExclusionCode.CHECKOUT_FAILED, stages);
        }
        Optional<Path> fixed = timed(
                stages,
                "checkout_fixed",
                () -> checkout.checkout(
                        input.repositoryUrl(), input.fixedRevision(), input.caseId() + "-fixed"));
        if (fixed.isEmpty()) {
            return excluded(ExclusionCode.CHECKOUT_FAILED, stages);
        }
        if (!timed(
                stages,
                "parent_revision",
                () -> parentRevision.matches(
                        fixed.orElseThrow(), input.buggyRevision(), input.fixedRevision()))) {
            return excluded(ExclusionCode.PARENT_REVISION_MISMATCH, stages);
        }

        MavenExecutionPolicy policy =
                new MavenExecutionPolicy(input.javaVersion(), MavenNetworkMode.OFFLINE);
        MavenTestCommand command = new MavenTestCommand(
                input.modulePath(),
                input.targetTest().className() + "#" + input.targetTest().methodName(),
                MavenNetworkMode.OFFLINE,
                input.javaVersion());
        if (!timed(stages, "apply_buggy_trigger", () -> trigger.applyToBuggy(buggy.orElseThrow(), input, policy))) {
            return excluded(ExclusionCode.BUGGY_TRIGGER_MISMATCH, stages);
        }
        if (!timed(stages, "verify_fixed_trigger", () -> trigger.verifyOnFixed(fixed.orElseThrow(), input, policy))) {
            return excluded(ExclusionCode.FIXED_TRIGGER_MISMATCH, stages);
        }
        if (!timed(stages, "warmup_buggy", () -> warmup.warm(buggy.orElseThrow(), command))
                || !timed(stages, "warmup_fixed", () -> warmup.warm(fixed.orElseThrow(), command))) {
            return excluded(ExclusionCode.WARMUP_FAILED, stages);
        }

        SideExecutionResult buggySide = timed(
                stages,
                "offline_buggy",
                () -> replay.replay(buggy.orElseThrow(), command, input.targetTest()));
        SideExecutionResult fixedSide = timed(
                stages,
                "offline_fixed",
                () -> replay.replay(fixed.orElseThrow(), command, input.targetTest()));
        if (!executedOffline(buggySide) || !executedOffline(fixedSide)) {
            return excluded(ExclusionCode.OFFLINE_REPLAY_FAILED, stages);
        }
        if (flaky(buggySide) || flaky(fixedSide)) {
            return excluded(ExclusionCode.TRIGGER_FLAKY, stages);
        }
        if (buggySide.attempts().getFirst().targetEvidence()
                != SingleAttemptEvidence.TARGET_ASSERTION_FAILURE) {
            return excluded(ExclusionCode.BUGGY_TRIGGER_MISMATCH, stages);
        }
        if (fixedSide.attempts().getFirst().targetEvidence()
                != SingleAttemptEvidence.TARGET_PASSED) {
            return excluded(ExclusionCode.FIXED_TRIGGER_MISMATCH, stages);
        }
        return new Result.Eligible(stages);
    }

    private static boolean executedOffline(SideExecutionResult side) {
        return side.attempts().stream().allMatch(attempt ->
                attempt.phase() == AttemptPhase.EXECUTED
                        && attempt.execution().isPresent()
                        && attempt.execution().orElseThrow().status()
                                == SandboxExecutionStatus.COMPLETED);
    }

    private static boolean flaky(SideExecutionResult side) {
        SingleAttemptEvidence first = side.attempts().get(0).targetEvidence();
        SingleAttemptEvidence second = side.attempts().get(1).targetEvidence();
        return first != SingleAttemptEvidence.INVALID
                && second != SingleAttemptEvidence.INVALID
                && first != second;
    }

    private static CandidateDraft draft(Input input) {
        return new CandidateDraft(input.knownTriggerPatch(), input.targetTest());
    }

    private static Result.Excluded excluded(ExclusionCode code, List<Stage> stages) {
        return new Result.Excluded(code, stages);
    }

    private static <T> T timed(List<Stage> stages, String name, CheckedSupplier<T> action) {
        Instant start = Instant.now();
        T result = action.get();
        long duration = Math.max(0, Duration.between(start, Instant.now()).toMillis());
        String status = result instanceof Boolean value
                ? (value ? "PASSED" : "FAILED")
                : (result instanceof Optional<?> optional && optional.isEmpty() ? "FAILED" : "PASSED");
        stages.add(new Stage(name, status, duration));
        return result;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get();
    }

    private static void requireSha(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException(field + " must be 40 lowercase hex chars");
        }
    }
}

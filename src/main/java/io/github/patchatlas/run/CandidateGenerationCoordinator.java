package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.GeneratorIdentity;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.util.Objects;
import java.util.Optional;

/**
 * 生成阶段编排：预占 → 模型调用 → Gate → Buggy 两次预验证 → 提交或修正/失败。
 *
 * <p>不持有 DataSource / Run Store；持久化只经 {@link GenerationRunSession}。
 * workspace 打开使用预占返回的 {@link ClaimedRun}，不再二次 currentClaim 查询。
 */
public final class CandidateGenerationCoordinator {

    public sealed interface Result permits Result.CandidateCommitted, Result.RunFailed {
        record CandidateCommitted(ClaimedRun claim) implements Result {}

        record RunFailed(RunDetails details) implements Result {}
    }

    private final TestGenerator generator;
    private final PatchGate patchGate;
    private final CandidateWorkspaceFactory workspaceFactory;
    private final DependencyWarmupRunner dependencyWarmupRunner;
    private final SideReplayRunner sideReplayRunner;

    public CandidateGenerationCoordinator(
            TestGenerator generator,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            DependencyWarmupRunner dependencyWarmupRunner,
            SideReplayRunner sideReplayRunner) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.patchGate = Objects.requireNonNull(patchGate, "patchGate");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.dependencyWarmupRunner =
                Objects.requireNonNull(dependencyWarmupRunner, "dependencyWarmupRunner");
        this.sideReplayRunner = Objects.requireNonNull(sideReplayRunner, "sideReplayRunner");
    }

    public Result run(GenerationInput generationInput, GenerationRunSession session) {
        String javaVersion = generationInput.generatorContext().javaVersion() == null
                ? MavenExecutionPolicy.DEFAULT_JAVA_VERSION
                : generationInput.generatorContext().javaVersion();
        return run(
                generationInput,
                new MavenExecutionPolicy(javaVersion, MavenNetworkMode.OFFLINE),
                session);
    }

    public Result run(
            GenerationInput generationInput,
            MavenExecutionPolicy executionPolicy,
            GenerationRunSession session) {
        Objects.requireNonNull(generationInput, "generationInput");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        Objects.requireNonNull(session, "session");

        Optional<CandidateDraft> previousDraft = Optional.empty();
        Optional<GenerationFeedback> feedback = Optional.empty();

        for (int guard = 0; guard < GenerationRequest.MAX_ATTEMPTS; guard++) {
            GeneratorIdentity identity = generator.identity();
            GenerationRunSession.ReserveResult reserveResult =
                    session.reserveGenerationAttempt(identity.provider(), identity.modelName());
            switch (reserveResult) {
                case GenerationRunSession.ReserveResult.Exhausted exhausted -> {
                    return new Result.RunFailed(exhausted.failedRun());
                }
                case GenerationRunSession.ReserveResult.Stale stale -> throw stale.cause();
                case GenerationRunSession.ReserveResult.Reserved slot -> {
                    int ordinal = slot.attemptOrdinal();
                    ClaimedRun claimAfterReserve = slot.claim();

                    GenerationRequest request;
                    if (previousDraft.isPresent()) {
                        request = GenerationRequest.correction(
                                generationInput,
                                ordinal,
                                previousDraft.orElseThrow(),
                                feedback.orElseThrow());
                    } else if (feedback.isPresent()) {
                        request = GenerationRequest.feedbackOnly(
                                generationInput, ordinal, feedback.orElseThrow());
                    } else {
                        request = GenerationRequest.first(generationInput, ordinal);
                    }

                    GenerationResult call = generator.generate(request);
                    recordUsageIfPresent(session, call.usage());

                    switch (call) {
                        case GenerationResult.GenerationCallFailure failure -> {
                            boolean remaining = ordinal < GenerationRequest.MAX_ATTEMPTS;
                            CallFailureMapper.Outcome mapped = CallFailureMapper.map(
                                    failure.category(), failure.summary(), remaining);
                            switch (mapped) {
                                case CallFailureMapper.Outcome.Terminal terminal -> {
                                    return new Result.RunFailed(session.fail(terminal.failure()));
                                }
                                case CallFailureMapper.Outcome.Correctable correctable -> {
                                    previousDraft = Optional.empty();
                                    feedback = Optional.of(correctable.feedback());
                                    continue;
                                }
                            }
                        }
                        case GenerationResult.GeneratedDraft draftResult -> {
                            CandidateDraft draft = draftResult.draft();
                            // 使用预占后的 claim，避免 currentClaim 与心跳竞态
                            try (CandidateWorkspaceFactory.WorkspaceSession workspace =
                                    workspaceFactory.open(
                                            claimAfterReserve, generationInput, executionPolicy)) {
                                MavenTestCommand command = commandForDraft(workspace, draft);
                                Optional<String> warmupFailure = dependencyWarmupRunner.warm(
                                        workspace.workspace(), command);
                                if (warmupFailure.isPresent()) {
                                    return new Result.RunFailed(session.fail(new RunFailure(
                                            FailureStage.REPLAY,
                                            FailureCategory.REPLAY_SYSTEM_ERROR,
                                            warmupFailure.orElseThrow())));
                                }
                                PatchPreparationResult prepared = patchGate.prepare(
                                        workspace.workspace(),
                                        workspace.modulePath(),
                                        draft,
                                        workspace.executionPolicy());
                                if (prepared instanceof PatchPreparationResult.RejectedCandidate rejected) {
                                    PatchGateOutcomeMapper.Outcome gateOutcome =
                                            PatchGateOutcomeMapper.map(
                                                    rejected.category(), rejected.reason());
                                    switch (gateOutcome) {
                                        case PatchGateOutcomeMapper.Outcome.Terminal terminal -> {
                                            return new Result.RunFailed(session.fail(terminal.failure()));
                                        }
                                        case PatchGateOutcomeMapper.Outcome.Correctable correctable -> {
                                            previousDraft = Optional.of(draft);
                                            feedback = Optional.of(correctable.feedback());
                                            continue;
                                        }
                                    }
                                }
                                PatchPreparationResult.PreparedCandidate preparedOk =
                                        (PatchPreparationResult.PreparedCandidate) prepared;

                                SideExecutionResult side = sideReplayRunner.runSide(
                                        preparedOk.workspace(),
                                        preparedOk.command(),
                                        preparedOk.targetTest());
                                PrevalidationFeedbackMapper.Outcome pre =
                                        PrevalidationFeedbackMapper.map(side);
                                switch (pre) {
                                    case PrevalidationFeedbackMapper.Outcome.Success ignored -> {
                                        GatedCandidate gated = GatedCandidate.afterSuccessfulGate(
                                                draft, preparedOk);
                                        ClaimedRun replaying = session.commitCandidate(gated);
                                        return new Result.CandidateCommitted(replaying);
                                    }
                                    case PrevalidationFeedbackMapper.Outcome.Correctable correctable -> {
                                        previousDraft = Optional.of(draft);
                                        feedback = Optional.of(correctable.feedback());
                                        continue;
                                    }
                                    case PrevalidationFeedbackMapper.Outcome.Terminal terminal -> {
                                        return new Result.RunFailed(session.fail(terminal.failure()));
                                    }
                                }
                            } catch (StaleClaimException stale) {
                                throw stale;
                            } catch (Exception ex) {
                                return new Result.RunFailed(session.fail(new RunFailure(
                                        FailureStage.WORKSPACE,
                                        FailureCategory.WORKSPACE_UNSAFE,
                                        bound("workspace: " + ex.getClass().getSimpleName()))));
                            }
                        }
                    }
                }
            }
        }
        return new Result.RunFailed(session.fail(new RunFailure(
                FailureStage.GENERATION,
                FailureCategory.GENERATION_EXHAUSTED,
                "generation attempts exhausted")));
    }

    private static void recordUsageIfPresent(GenerationRunSession session, Optional<ModelUsage> usage) {
        usage.ifPresent(session::recordModelUsage);
    }

    private static MavenTestCommand commandForDraft(
            CandidateWorkspaceFactory.WorkspaceSession workspace, CandidateDraft draft) {
        return new MavenTestCommand(
                workspace.modulePath(),
                draft.targetTest().className() + "#" + draft.targetTest().methodName(),
                workspace.executionPolicy().networkMode(),
                workspace.executionPolicy().javaVersion());
    }

    private static String bound(String s) {
        if (s.length() <= RunFailure.MAX_SUMMARY_CHARS) {
            return s;
        }
        return s.substring(0, RunFailure.MAX_SUMMARY_CHARS);
    }
}

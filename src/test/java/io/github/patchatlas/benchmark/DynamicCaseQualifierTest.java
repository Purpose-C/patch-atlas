package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.DynamicCaseQualifier.ExclusionCode;
import io.github.patchatlas.benchmark.DynamicCaseQualifier.Input;
import io.github.patchatlas.benchmark.DynamicCaseQualifier.Result;
import io.github.patchatlas.repository.ParentRevisionValidator;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicCaseQualifierTest {

    private static final TargetTest TARGET = new TargetTest("p.T", "fails");

    @Test
    void acceptsOnlyStableBuggyFailureAndFixedPass() {
        AtomicInteger checkoutCalls = new AtomicInteger();
        AtomicInteger replayCalls = new AtomicInteger();
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                (url, revision, id) -> Optional.of(Path.of("/tmp", "ws-" + checkoutCalls.incrementAndGet())),
                acceptingTrigger(),
                (workspace, command) -> true,
                (workspace, command, target) ->
                        replayCalls.incrementAndGet() == 1 ? side(TestCaseStatus.FAILED) : side(TestCaseStatus.PASSED));

        Result result = qualifier.qualifyWithinBudget(input());

        assertThat(result).isInstanceOf(Result.Eligible.class);
        assertThat(checkoutCalls).hasValue(2);
        assertThat(result.stages()).extracting(DynamicCaseQualifier.Stage::name)
                .containsExactly(
                        "checkout_buggy",
                        "checkout_fixed",
                        "parent_revision",
                        "apply_buggy_trigger",
                        "verify_fixed_trigger",
                        "warmup_buggy",
                        "warmup_fixed",
                        "offline_buggy",
                        "offline_fixed");
    }

    @Test
    void distinguishesFlakyFromStableMismatch() {
        AtomicInteger replayCalls = new AtomicInteger();
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                (url, revision, id) -> Optional.of(Path.of("/tmp/ws")),
                acceptingTrigger(),
                (workspace, command) -> true,
                (workspace, command, target) -> replayCalls.incrementAndGet() == 1
                        ? mixedSide()
                        : side(TestCaseStatus.PASSED));

        Result result = qualifier.qualifyWithinBudget(input());

        assertThat(result).isInstanceOf(Result.Excluded.class);
        assertThat(((Result.Excluded) result).code()).isEqualTo(ExclusionCode.TRIGGER_FLAKY);
    }

    @Test
    void stopsAtFirstFailedSharedStage() {
        AtomicInteger warmupCalls = new AtomicInteger();
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                (url, revision, id) -> Optional.of(Path.of("/tmp/ws")),
                acceptingTrigger(),
                (workspace, command) -> warmupCalls.incrementAndGet() != 1,
                (workspace, command, target) -> side(TestCaseStatus.PASSED));

        Result result = qualifier.qualifyWithinBudget(input());

        assertThat(((Result.Excluded) result).code()).isEqualTo(ExclusionCode.WARMUP_FAILED);
        assertThat(warmupCalls).hasValue(1);
    }

    @Test
    void checksParentOnTheFixedWorkspaceAndSkipsTriggerWhenItFails() {
        AtomicInteger triggerCalls = new AtomicInteger();
        AtomicReference<Path> parentWorkspace = new AtomicReference<>();
        Path buggyWorkspace = Path.of("/tmp/case-buggy");
        Path fixedWorkspace = Path.of("/tmp/case-fixed");
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                (url, revision, id) -> Optional.of(
                        id.endsWith("-fixed") ? fixedWorkspace : buggyWorkspace),
                countingTrigger(triggerCalls),
                (workspace, command) -> true,
                (workspace, command, target) -> side(TestCaseStatus.PASSED),
                (workspace, buggyRevision, fixedRevision) -> {
                    parentWorkspace.set(workspace);
                    return false;
                });

        Result result = qualifier.qualifyWithinBudget(input());

        assertThat(result).isInstanceOf(Result.Excluded.class);
        assertThat(((Result.Excluded) result).code()).isEqualTo(ExclusionCode.PARENT_REVISION_MISMATCH);
        assertThat(parentWorkspace).hasValue(fixedWorkspace);
        assertThat(triggerCalls).hasValue(0);
        assertThat(result.stages()).extracting(DynamicCaseQualifier.Stage::name)
                .containsExactly("checkout_buggy", "checkout_fixed", "parent_revision");
    }

    @Test
    void productionParentPortAcceptsFixedWhoseFirstParentIsBuggy(@TempDir Path workspace)
            throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit buggy;
        RevCommit fixed;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            buggy = commit(git, "buggy");
            Files.writeString(repository.resolve("a.txt"), "2\n");
            git.add().addFilepattern("a.txt").call();
            fixed = commit(git, "fixed");
        }
        AtomicInteger triggerCalls = new AtomicInteger();
        AtomicInteger replayCalls = new AtomicInteger();
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                (url, revision, id) -> Optional.of(repository),
                countingTrigger(triggerCalls),
                (workspacePath, command) -> true,
                (workspacePath, command, target) ->
                        replayCalls.incrementAndGet() == 1
                                ? side(TestCaseStatus.FAILED)
                                : side(TestCaseStatus.PASSED),
                DynamicCaseQualifier.matchingParentRevision(new ParentRevisionValidator()));

        Result result = qualifier.qualifyWithinBudget(input(buggy.getName(), fixed.getName()));

        assertThat(result).isInstanceOf(Result.Eligible.class);
        assertThat(triggerCalls).hasValue(2);
    }

    @Test
    void productionParentPortRejectsFixedWhoseFirstParentIsNotBuggy(@TempDir Path workspace)
            throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit first;
        RevCommit fixed;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            first = commit(git, "first");
            Files.writeString(repository.resolve("a.txt"), "other\n");
            git.add().addFilepattern("a.txt").call();
            commit(git, "other");
            Files.writeString(repository.resolve("a.txt"), "fixed\n");
            git.add().addFilepattern("a.txt").call();
            fixed = commit(git, "fixed");
        }
        AtomicInteger triggerCalls = new AtomicInteger();
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                (url, revision, id) -> Optional.of(repository),
                countingTrigger(triggerCalls),
                (workspacePath, command) -> true,
                (workspacePath, command, target) -> side(TestCaseStatus.PASSED),
                DynamicCaseQualifier.matchingParentRevision(new ParentRevisionValidator()));

        Result result = qualifier.qualifyWithinBudget(input(first.getName(), fixed.getName()));

        assertThat(((Result.Excluded) result).code()).isEqualTo(ExclusionCode.PARENT_REVISION_MISMATCH);
        assertThat(triggerCalls).hasValue(0);
    }

    private static DynamicCaseQualifier.TriggerPort countingTrigger(AtomicInteger calls) {
        return new DynamicCaseQualifier.TriggerPort() {
            @Override
            public boolean applyToBuggy(
                    Path workspace,
                    Input input,
                    io.github.patchatlas.sandbox.MavenExecutionPolicy policy) {
                calls.incrementAndGet();
                return true;
            }

            @Override
            public boolean verifyOnFixed(
                    Path workspace,
                    Input input,
                    io.github.patchatlas.sandbox.MavenExecutionPolicy policy) {
                calls.incrementAndGet();
                return true;
            }
        };
    }

    private static DynamicCaseQualifier.TriggerPort acceptingTrigger() {
        return countingTrigger(new AtomicInteger());
    }

    private static Input input() {
        return input("a".repeat(40), "b".repeat(40));
    }

    private static Input input(String buggyRevision, String fixedRevision) {
        return new Input(
                "case",
                "https://github.com/o/r.git",
                buggyRevision,
                fixedRevision,
                "",
                TARGET,
                "patch",
                "17");
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        return git.commit()
                .setMessage(message)
                .setAuthor("PatchAtlas Test", "test@example.com")
                .setCommitter("PatchAtlas Test", "test@example.com")
                .setSign(false)
                .call();
    }

    private static SideExecutionResult side(TestCaseStatus status) {
        AttemptRecord attempt = attempt(status);
        return new SideExecutionResult(List.of(attempt, attempt));
    }

    private static SideExecutionResult mixedSide() {
        return new SideExecutionResult(List.of(
                attempt(TestCaseStatus.FAILED), attempt(TestCaseStatus.PASSED)));
    }

    private static AttemptRecord attempt(TestCaseStatus status) {
        int exit = status == TestCaseStatus.PASSED ? 0 : 1;
        SandboxExecution execution = new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exit,
                Duration.ofMillis(1),
                false,
                List.of("mvn", "test"),
                "",
                "maven:3.9-eclipse-temurin-17",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
        TestCaseResult test = new TestCaseResult(
                TARGET.className(),
                TARGET.methodName(),
                Duration.ofMillis(1),
                status,
                status == TestCaseStatus.FAILED
                        ? "org.opentest4j.AssertionFailedError"
                        : null,
                status == TestCaseStatus.FAILED ? "expected" : null);
        return AttemptRecord.executed(execution, new TestReport(List.of(test)), TARGET);
    }
}

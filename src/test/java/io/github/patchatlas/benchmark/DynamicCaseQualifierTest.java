package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.DynamicCaseQualifier.ExclusionCode;
import io.github.patchatlas.benchmark.DynamicCaseQualifier.Input;
import io.github.patchatlas.benchmark.DynamicCaseQualifier.Result;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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

    private static DynamicCaseQualifier.TriggerPort acceptingTrigger() {
        return new DynamicCaseQualifier.TriggerPort() {
            @Override
            public boolean applyToBuggy(
                    Path workspace,
                    Input input,
                    io.github.patchatlas.sandbox.MavenExecutionPolicy policy) {
                return true;
            }

            @Override
            public boolean verifyOnFixed(
                    Path workspace,
                    Input input,
                    io.github.patchatlas.sandbox.MavenExecutionPolicy policy) {
                return true;
            }
        };
    }

    private static Input input() {
        return new Input(
                "case",
                "https://github.com/o/r.git",
                "a".repeat(40),
                "b".repeat(40),
                "",
                TARGET,
                "patch",
                "17");
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

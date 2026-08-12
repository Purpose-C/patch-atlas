package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.List;

/** 测试用一致 SideExecutionResult 构造（经 AttemptRecord 工厂从原始事实计算）。 */
final class ReplayTestFixtures {

    static final TargetTest TARGET = new TargetTest("c.T", "m");

    private ReplayTestFixtures() {}

    static SideExecutionResult preExecutionFailureSide() {
        AttemptRecord a = AttemptRecord.preExecutionFailure("test");
        return new SideExecutionResult(List.of(a, a));
    }

    static SideExecutionResult targetPassedSide() {
        AttemptRecord a = AttemptRecord.executed(
                completed(0), new TestReport(List.of(passed())), TARGET);
        return new SideExecutionResult(List.of(a, a));
    }

    static SideExecutionResult targetAssertionFailureSide() {
        AttemptRecord a = AttemptRecord.executed(
                completed(1), new TestReport(List.of(failed())), TARGET);
        return new SideExecutionResult(List.of(a, a));
    }

    private static SandboxExecution completed(int exit) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exit,
                Duration.ofMillis(1),
                false,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static TestCaseResult passed() {
        return new TestCaseResult(
                "c.T", "m", Duration.ofMillis(1), TestCaseStatus.PASSED, null, null);
    }

    private static TestCaseResult failed() {
        return new TestCaseResult(
                "c.T",
                "m",
                Duration.ofMillis(1),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "x");
    }
}

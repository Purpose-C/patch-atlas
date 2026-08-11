package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttemptEvidenceEvaluatorTest {

    private final AttemptEvidenceEvaluator evaluator = new AttemptEvidenceEvaluator();
    private final TargetTest target = new TargetTest("com.example.BugTest", "detectsBug");

    @Test
    void targetPassedWithRealPassExecution() {
        TestReport report = new TestReport(List.of(passed("com.example.BugTest", "detectsBug")));
        assertThat(evaluator.evaluate(completed(0, "BUILD SUCCESS"), report, target))
                .isEqualTo(SingleAttemptEvidence.TARGET_PASSED);
    }

    @Test
    void targetAssertionFailureWithRealFailureExecution() {
        TestReport report = new TestReport(List.of(failed("com.example.BugTest", "detectsBug")));
        assertThat(evaluator.evaluate(completed(1, "Failures: 1"), report, target))
                .isEqualTo(SingleAttemptEvidence.TARGET_ASSERTION_FAILURE);
    }

    @Test
    void rejectsTimeoutEvenWhenReportLooksLikePass() {
        // P1：不得信任“伪造 PASS outcome”；超时执行本身必须否决成功证据
        TestReport report = new TestReport(List.of(passed("com.example.BugTest", "detectsBug")));
        assertThat(evaluator.evaluate(timedOut(), report, target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
    }

    @Test
    void emptyReportPassIsInvalidForTargetEvidence() {
        assertThat(evaluator.evaluate(completed(0, "No tests"), TestReport.empty(), target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
    }

    @Test
    void compileEnvironmentTimeoutErrorAreInvalid() {
        assertThat(evaluator.evaluate(
                        completed(1, "[ERROR] COMPILATION ERROR"), TestReport.empty(), target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
        assertThat(evaluator.evaluate(
                        completed(1, "Could not resolve dependencies"), TestReport.empty(), target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
        assertThat(evaluator.evaluate(timedOut(), TestReport.empty(), target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
        assertThat(evaluator.evaluate(
                        completed(1, "Errors: 1"),
                        new TestReport(List.of(errored("com.example.BugTest", "detectsBug"))),
                        target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
    }

    @Test
    void accompanyingFailuresAreInvalid() {
        TestReport report = new TestReport(List.of(
                failed("com.example.BugTest", "detectsBug"),
                failed("com.example.Other", "x")));

        assertThat(evaluator.evaluate(completed(1, "multi"), report, target))
                .isEqualTo(SingleAttemptEvidence.INVALID);
    }

    private static SandboxExecution completed(int exitCode, String log) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exitCode,
                Duration.ofSeconds(1),
                false,
                List.of("mvn", "test"),
                log,
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static SandboxExecution timedOut() {
        return new SandboxExecution(
                SandboxExecutionStatus.TIMED_OUT,
                null,
                Duration.ofSeconds(30),
                true,
                List.of("mvn", "test"),
                "timeout",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static TestCaseResult passed(String className, String method) {
        return new TestCaseResult(
                className, method, Duration.ofMillis(1), TestCaseStatus.PASSED, null, null);
    }

    private static TestCaseResult failed(String className, String method) {
        return new TestCaseResult(
                className,
                method,
                Duration.ofMillis(2),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "expected");
    }

    private static TestCaseResult errored(String className, String method) {
        return new TestCaseResult(
                className,
                method,
                Duration.ofMillis(2),
                TestCaseStatus.ERROR,
                "java.lang.IllegalStateException",
                "boom");
    }
}

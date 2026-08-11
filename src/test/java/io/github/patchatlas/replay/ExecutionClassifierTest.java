package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionClassifierTest {

    private final ExecutionClassifier classifier = new ExecutionClassifier();

    @Test
    void classifiesAllPassedWithExitZeroAsPass() {
        TestReport report = new TestReport(List.of(
                passed("com.example.A", "ok"),
                passed("com.example.A", "alsoOk")));

        assertThat(classifier.classify(completed(0, "BUILD SUCCESS"), report))
                .isEqualTo(RunOutcome.PASS);
    }

    @Test
    void classifiesSurefireFailureAsAssertionFailure() {
        TestReport report = new TestReport(List.of(failed("com.example.A", "detectsBug")));

        assertThat(classifier.classify(completed(1, "Tests run: 1, Failures: 1"), report))
                .isEqualTo(RunOutcome.ASSERTION_FAILURE);
    }

    @Test
    void classifiesSurefireErrorAsTestError() {
        TestReport report = new TestReport(List.of(errored("com.example.A", "blowsUp")));

        assertThat(classifier.classify(completed(1, "Tests run: 1, Errors: 1"), report))
                .isEqualTo(RunOutcome.TEST_ERROR);
    }

    @Test
    void prefersAssertionFailureWhenBothFailuresAndErrorsPresent() {
        TestReport report = new TestReport(List.of(
                failed("com.example.A", "assert"),
                errored("com.example.A", "npe")));

        assertThat(classifier.classify(completed(1, "mixed"), report))
                .isEqualTo(RunOutcome.ASSERTION_FAILURE);
    }

    @Test
    void classifiesTimeoutStatusAsTimeoutEvenIfReportsExist() {
        SandboxExecution execution = execution(
                SandboxExecutionStatus.TIMED_OUT,
                null,
                true,
                "killed");

        assertThat(classifier.classify(execution, new TestReport(List.of(passed("c", "m")))))
                .isEqualTo(RunOutcome.TIMEOUT);
    }

    @Test
    void classifiesTimedOutFlagAsTimeout() {
        SandboxExecution execution = execution(
                SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED,
                null,
                true,
                "timeout cleanup failed");

        assertThat(classifier.classify(execution, TestReport.empty())).isEqualTo(RunOutcome.TIMEOUT);
    }

    @Test
    void classifiesNonCompletedSandboxStatusAsEnvironmentFailure() {
        SandboxExecution execution = execution(
                SandboxExecutionStatus.DOCKER_UNAVAILABLE,
                null,
                false,
                "Cannot connect to Docker");

        assertThat(classifier.classify(execution, TestReport.empty()))
                .isEqualTo(RunOutcome.ENVIRONMENT_FAILURE);
    }

    @Test
    void classifiesEmptyReportWithNonZeroExitAsCompileFailureByDefault() {
        assertThat(classifier.classify(
                        completed(1, "[ERROR] COMPILATION ERROR :\nFailed to execute goal compiler"),
                        TestReport.empty()))
                .isEqualTo(RunOutcome.COMPILE_FAILURE);
    }

    @Test
    void classifiesEmptyReportWithDependencyFailureAsEnvironmentFailure() {
        String log =
                "[ERROR] Failed to execute goal on project x: Could not resolve dependencies for project";
        assertThat(classifier.classify(completed(1, log), TestReport.empty()))
                .isEqualTo(RunOutcome.ENVIRONMENT_FAILURE);
    }

    @Test
    void classifiesEmptyReportWithExitZeroAsPass() {
        assertThat(classifier.classify(completed(0, "No tests to run"), TestReport.empty()))
                .isEqualTo(RunOutcome.PASS);
    }

    @Test
    void classifiesContradictoryPassedReportWithNonZeroExitAsEnvironmentFailure() {
        TestReport report = new TestReport(List.of(passed("com.example.A", "ok")));

        assertThat(classifier.classify(completed(1, "weird exit"), report))
                .isEqualTo(RunOutcome.ENVIRONMENT_FAILURE);
    }

    @Test
    void singleClassifyNeverReturnsFlaky() {
        assertThat(classifier.classify(
                        completed(1, "Tests run: 1, Failures: 1"),
                        new TestReport(List.of(failed("c", "m")))))
                .isNotEqualTo(RunOutcome.FLAKY_FAILURE);
        assertThat(classifier.classify(completed(0, "ok"), TestReport.empty()))
                .isNotEqualTo(RunOutcome.FLAKY_FAILURE);
    }

    @Test
    void classifyAttemptsRequiresAtLeastTwoAttempts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> classifier.classifyAttempts(List.of(RunOutcome.PASS)));
    }

    @Test
    void classifyAttemptsReturnsStableOutcomeWhenAllMatch() {
        assertThat(classifier.classifyAttempts(List.of(
                        RunOutcome.ASSERTION_FAILURE,
                        RunOutcome.ASSERTION_FAILURE,
                        RunOutcome.ASSERTION_FAILURE)))
                .isEqualTo(RunOutcome.ASSERTION_FAILURE);
    }

    @Test
    void classifyAttemptsMarksDivergentOutcomesAsFlaky() {
        assertThat(classifier.classifyAttempts(
                        List.of(RunOutcome.ASSERTION_FAILURE, RunOutcome.PASS, RunOutcome.ASSERTION_FAILURE)))
                .isEqualTo(RunOutcome.FLAKY_FAILURE);
    }

    private static SandboxExecution completed(int exitCode, String log) {
        return execution(SandboxExecutionStatus.COMPLETED, exitCode, false, log);
    }

    private static SandboxExecution execution(
            SandboxExecutionStatus status, Integer exitCode, boolean timedOut, String log) {
        return new SandboxExecution(
                status,
                exitCode,
                Duration.ofSeconds(3),
                timedOut,
                List.of("mvn", "-B", "test"),
                log,
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
                "expected <c> but was <b>");
    }

    private static TestCaseResult errored(String className, String method) {
        return new TestCaseResult(
                className,
                method,
                Duration.ofMillis(2),
                TestCaseStatus.ERROR,
                "java.lang.NullPointerException",
                "boom");
    }
}

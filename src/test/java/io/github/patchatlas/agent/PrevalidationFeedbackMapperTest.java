package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.RunOutcome;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.StableSideEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrevalidationFeedbackMapperTest {

    private static final TargetTest TARGET = new TargetTest("fixtures.OldTest", "added");

    @Test
    void targetAssertionFailureIsSuccess() {
        AttemptRecord fail = AttemptRecord.executed(completed(1), failedReport(), TARGET);
        SideExecutionResult side = new SideExecutionResult(
                List.of(fail, fail),
                StableSideEvidence.TARGET_ASSERTION_FAILURE,
                Optional.of(RunOutcome.ASSERTION_FAILURE));
        assertThat(PrevalidationFeedbackMapper.map(side))
                .isInstanceOf(PrevalidationFeedbackMapper.Outcome.Success.class);
    }

    @Test
    void targetPassedMapsToCorrectable() {
        AttemptRecord pass = AttemptRecord.executed(completed(0), passedReport(), TARGET);
        SideExecutionResult side = new SideExecutionResult(
                List.of(pass, pass),
                StableSideEvidence.TARGET_PASSED,
                Optional.of(RunOutcome.PASS));
        var outcome = PrevalidationFeedbackMapper.map(side);
        assertThat(outcome).isInstanceOf(PrevalidationFeedbackMapper.Outcome.Correctable.class);
        assertThat(((PrevalidationFeedbackMapper.Outcome.Correctable) outcome).feedback().category())
                .isEqualTo(GenerationFeedbackCategory.TARGET_TEST_PASSED);
    }

    @Test
    void aggregatedPassWithOtherOrInvalidMapsToTargetMissingNotPassed() {
        // 总体 PASS 但目标未匹配：报告中是别的测试通过
        TestReport other = new TestReport(List.of(new TestCaseResult(
                "fixtures.Other", "x", Duration.ofMillis(1), TestCaseStatus.PASSED, null, null)));
        AttemptRecord a = AttemptRecord.executed(completed(0), other, TARGET);
        SideExecutionResult side = new SideExecutionResult(
                List.of(a, a),
                StableSideEvidence.OTHER_OR_INVALID,
                Optional.of(RunOutcome.PASS));
        var outcome = PrevalidationFeedbackMapper.map(side);
        assertThat(outcome).isInstanceOf(PrevalidationFeedbackMapper.Outcome.Correctable.class);
        assertThat(((PrevalidationFeedbackMapper.Outcome.Correctable) outcome).feedback().category())
                .isEqualTo(GenerationFeedbackCategory.TARGET_TEST_MISSING);
    }

    @Test
    void compileFailureMapsToCompilationFailed() {
        AttemptRecord a = AttemptRecord.executed(
                completed(1, "[ERROR] COMPILATION ERROR"), TestReport.empty(), TARGET);
        SideExecutionResult side = new SideExecutionResult(
                List.of(a, a),
                StableSideEvidence.OTHER_OR_INVALID,
                Optional.of(RunOutcome.COMPILE_FAILURE));
        var outcome = PrevalidationFeedbackMapper.map(side);
        assertThat(outcome).isInstanceOf(PrevalidationFeedbackMapper.Outcome.Correctable.class);
        assertThat(((PrevalidationFeedbackMapper.Outcome.Correctable) outcome).feedback().category())
                .isEqualTo(GenerationFeedbackCategory.COMPILATION_FAILED);
    }

    private static SandboxExecution completed(int exit) {
        return completed(exit, "log");
    }

    private static SandboxExecution completed(int exit, String log) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exit,
                Duration.ofMillis(1),
                false,
                List.of("mvn"),
                log,
                "img",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static TestReport failedReport() {
        return new TestReport(List.of(new TestCaseResult(
                TARGET.className(),
                TARGET.methodName(),
                Duration.ofMillis(1),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "x")));
    }

    private static TestReport passedReport() {
        return new TestReport(List.of(new TestCaseResult(
                TARGET.className(),
                TARGET.methodName(),
                Duration.ofMillis(1),
                TestCaseStatus.PASSED,
                null,
                null)));
    }
}

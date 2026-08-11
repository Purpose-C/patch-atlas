package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttemptRecordTest {

    private final TargetTest target = new TargetTest("c.T", "m");

    @Test
    void executedDerivesEvidenceFromFactsNotCallerClaims() {
        // 空报告 + exit 0：Classifier 可为 PASS，但 Target 未出现 → INVALID，不得变成断言失败成功证据
        SandboxExecution passExec = completed(0);
        AttemptRecord record = AttemptRecord.executed(passExec, TestReport.empty(), target);

        assertThat(record.phase()).isEqualTo(AttemptPhase.EXECUTED);
        assertThat(record.outcome()).contains(RunOutcome.PASS);
        assertThat(record.targetEvidence()).isEqualTo(SingleAttemptEvidence.INVALID);
    }

    @Test
    void executedWithMatchingFailureYieldsTargetAssertionFailure() {
        TestReport report = new TestReport(List.of(new TestCaseResult(
                "c.T",
                "m",
                Duration.ofMillis(1),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "x")));
        AttemptRecord record = AttemptRecord.executed(completed(1), report, target);

        assertThat(record.targetEvidence()).isEqualTo(SingleAttemptEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(record.outcome()).contains(RunOutcome.ASSERTION_FAILURE);
    }

    @Test
    void reportFailureReclassifiesEmptyReportAndKeepsInvalidEvidence() {
        AttemptRecord record = AttemptRecord.reportFailure(completed(1), "path rejected");

        assertThat(record.phase()).isEqualTo(AttemptPhase.REPORT_FAILURE);
        assertThat(record.targetEvidence()).isEqualTo(SingleAttemptEvidence.INVALID);
        assertThat(record.outcome()).isPresent();
        assertThat(record.diagnostic()).contains("path rejected");
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
}

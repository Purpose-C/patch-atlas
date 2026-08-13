package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.RunOutcome;
import io.github.patchatlas.replay.SingleAttemptEvidence;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Attempt codec round-trip 与 fail-closed。 */
class AttemptRecordCodecTest {

    private final AttemptRecordCodec codec = new AttemptRecordCodec();
    private final TargetTest target = new TargetTest("c.T", "m");
    private final UUID runId = UUID.randomUUID();

    @Test
    void roundTripsExecutedAttempt() {
        AttemptRecord original = AttemptRecord.executed(
                completed(0), new TestReport(List.of(passed())), target);

        AttemptRecordCodec.PersistedAttempt row =
                codec.encode(UUID.randomUUID(), runId, 1, ReplaySide.PRIMARY, 1, original);
        AttemptRecord restored = codec.decode(row, target);

        assertThat(restored.phase()).isEqualTo(original.phase());
        assertThat(restored.outcome()).contains(RunOutcome.PASS);
        assertThat(restored.targetEvidence()).isEqualTo(SingleAttemptEvidence.TARGET_PASSED);
        assertThat(restored.report().testCases()).hasSize(1);
        assertThat(restored.execution()).isPresent();
        assertThat(restored.execution().orElseThrow().command()).containsExactly("mvn", "test");
    }

    @Test
    void roundTripsPreExecutionFailure() {
        AttemptRecord original = AttemptRecord.preExecutionFailure("workspace missing");
        AttemptRecordCodec.PersistedAttempt row =
                codec.encode(UUID.randomUUID(), runId, 0, ReplaySide.PRIMARY, 2, original);
        AttemptRecord restored = codec.decode(row, target);

        assertThat(restored.phase()).isEqualTo(original.phase());
        assertThat(restored.diagnostic()).contains("workspace missing");
        assertThat(restored.execution()).isEmpty();
        assertThat(restored.outcome()).isEmpty();
    }

    @Test
    void roundTripsReportFailure() {
        AttemptRecord original = AttemptRecord.reportFailure(completed(1), "path rejected");
        AttemptRecordCodec.PersistedAttempt row =
                codec.encode(UUID.randomUUID(), runId, 1, ReplaySide.FIXED, 1, original);
        AttemptRecord restored = codec.decode(row, target);

        assertThat(restored.phase()).isEqualTo(original.phase());
        assertThat(restored.targetEvidence()).isEqualTo(SingleAttemptEvidence.INVALID);
        assertThat(restored.diagnostic()).contains("path rejected");
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        AttemptRecord original = AttemptRecord.preExecutionFailure("x");
        AttemptRecordCodec.PersistedAttempt row =
                codec.encode(UUID.randomUUID(), runId, 1, ReplaySide.PRIMARY, 1, original);
        AttemptRecordCodec.PersistedAttempt bad = new AttemptRecordCodec.PersistedAttempt(
                row.id(),
                row.runId(),
                row.replayRound(),
                row.side(),
                row.attemptOrdinal(),
                row.phase(),
                row.outcome(),
                row.targetEvidence(),
                row.diagnostic(),
                row.sandboxStatus(),
                row.exitCode(),
                row.elapsedMs(),
                row.timedOut(),
                row.commandJson(),
                row.logSummary(),
                row.image(),
                row.limitsJson(),
                row.networkMode(),
                row.testCasesJson(),
                99);

        assertThatThrownBy(() -> codec.decode(bad, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void rejectsEvidenceMismatchOnDecode() {
        AttemptRecord original = AttemptRecord.executed(
                completed(0), new TestReport(List.of(passed())), target);
        AttemptRecordCodec.PersistedAttempt row =
                codec.encode(UUID.randomUUID(), runId, 1, ReplaySide.PRIMARY, 1, original);
        AttemptRecordCodec.PersistedAttempt tampered = new AttemptRecordCodec.PersistedAttempt(
                row.id(),
                row.runId(),
                row.replayRound(),
                row.side(),
                row.attemptOrdinal(),
                row.phase(),
                row.outcome(),
                SingleAttemptEvidence.INVALID,
                row.diagnostic(),
                row.sandboxStatus(),
                row.exitCode(),
                row.elapsedMs(),
                row.timedOut(),
                row.commandJson(),
                row.logSummary(),
                row.image(),
                row.limitsJson(),
                row.networkMode(),
                row.testCasesJson(),
                row.evidenceSchemaVersion());

        assertThatThrownBy(() -> codec.decode(tampered, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetEvidence");
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
}

package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.BenchmarkArtifacts;
import io.github.patchatlas.replay.VerificationMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class ToolBudgetCalibrationTest {

    @Test
    void loadQueueReadsFrozenCohortWithoutOracleFiles() throws Exception {
        List<ToolBudgetCalibration.Slot> slots =
                ToolBudgetCalibration.loadQueue(Path.of("benchmark-cases/task018"));

        assertThat(slots).hasSize(6);
        assertThat(slots).extracting(ToolBudgetCalibration.Slot::caseId)
                .containsExactly(
                        "Bindambc-whatsapp-business-java-api-362caf5eb33c",
                        "jhy-jsoup-91b630f86b5c",
                        "davidmoten-word-wrap-e59eedf0bac7",
                        "jhy-jsoup-a96ebc95f9ad",
                        "jhy-jsoup-9de27fa7cd82",
                        "AuthMe-ConfigMe-7bf10c513479");
        assertThat(slots.get(0).issueContentSha256())
                .isEqualTo("06e47f05f0ff7bc8c38ea5ec721a61ca006ea6db1afcc7d0220b89ceb64bf8a1");
        assertThat(slots.get(0).buggyRevision())
                .isEqualTo("dec4ca17d1194c063e63197d214322f2149c4ee5");
        assertThat(slots.get(0).issueUrl())
                .isEqualTo("https://github.com/Bindambc/whatsapp-business-java-api/issues/100");
    }

    @Test
    void digestUsesFrozenTitleNewlineBodyAlgorithm() {
        String title = "NoSuchMethodError while uploadMedia";
        String body = "body-one\nbody-two";
        ToolBudgetCalibration.Slot slot = slotWithDigest(BenchmarkArtifacts.issueContentSha256(title, body));

        assertThat(ToolBudgetCalibration.skipReason(slot, title, body)).isEmpty();
        assertThat(ToolBudgetCalibration.skipReason(slot, title, body + " edited"))
                .contains("Issue edited, digest mismatch");
        assertThat(ToolBudgetCalibration.skipReason(slot, title, ""))
                .contains("Issue text blank, cannot verify digest");
    }

    @Test
    void mismatchIsNotAcceptedToPadTheSample() {
        ToolBudgetCalibration.Slot slot = slotWithDigest("a".repeat(64));
        Optional<String> skip = ToolBudgetCalibration.skipReason(slot, "title", "body");
        assertThat(skip).isPresent();
        assertThat(BenchmarkArtifacts.issueContentSha256("title", "body")).isNotEqualTo("a".repeat(64));
    }

    @Test
    void submissionIsLiveDiagnosticTextToolsWithoutFixedRevision() {
        ToolBudgetCalibration.Slot slot = slotWithDigest("b".repeat(64));
        ToolBudgetCalibration.PreparedCase prepared =
                new ToolBudgetCalibration.PreparedCase(slot, "title", "body");
        RunSubmission submission = ToolBudgetCalibration.submission(prepared);

        assertThat(submission.mode()).isEqualTo(VerificationMode.LIVE);
        assertThat(submission.fixedRevision()).isNull();
        assertThat(submission.contextOrigin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(submission.sourceSnapshots()).isEmpty();
        assertThat(submission.caseId()).isEqualTo(slot.caseId());
        assertThat(submission.buggyRevision()).isEqualTo(slot.buggyRevision());
        assertThat(submission.issueTitle()).isEqualTo("title");
        assertThat(submission.issueBody()).isEqualTo("body");
    }

    @Test
    void stopAfterThreeConsecutiveTransportStartFailures() {
        ToolBudgetCalibration.Report report =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        report.absorb(transportStartFail());
        report.absorb(transportStartFail());
        assertThat(report.consecutiveStartFails).isEqualTo(2);
        report.absorb(transportStartFail());
        assertThat(report.consecutiveStartFails).isEqualTo(3);
        assertThat(report.transportFailures).isEqualTo(3);
        assertThat(report.submits).isEqualTo(3);
        assertThat(report.transportFailures * 2).isGreaterThan(report.submits);
    }

    @Test
    void transportRateOverHalfStopsEvenWhenStartsSucceedLater() {
        ToolBudgetCalibration.Report report =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        report.absorb(transportStartFail());
        report.absorb(okSession());
        report.absorb(transportMidFail());
        assertThat(report.consecutiveStartFails).isEqualTo(0);
        assertThat(report.submits).isEqualTo(3);
        assertThat(report.transportFailures).isEqualTo(2);
        assertThat(report.transportFailures * 2).isGreaterThan(report.submits);
    }

    @Test
    void successfulStartResetsConsecutiveTransportStartFailures() {
        ToolBudgetCalibration.Report report =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        report.absorb(transportStartFail());
        report.absorb(transportStartFail());
        report.absorb(okSession());
        assertThat(report.consecutiveStartFails).isEqualTo(0);
        assertThat(report.transportFailures).isEqualTo(2);
    }

    @Test
    void parallelGuardAndRelaxedCapAreDetectedFromTraces() {
        assertThat(ToolBudgetCalibration.isParallelGuard(
                        "parallel tool calls are not supported: received 2 [search, read]"))
                .isTrue();
        assertThat(ToolBudgetCalibration.isParallelGuard("locating produced no readable context"))
                .isFalse();

        LocatingTraceStep exhausted = LocatingTraceStep.of(
                5,
                LocatingStepKind.BUDGET_EXHAUSTED,
                LocatingTraceOutcome.OK,
                ".",
                "CALLS",
                "{}");
        LocatingTraceStep clock = LocatingTraceStep.of(
                5,
                LocatingStepKind.BUDGET_EXHAUSTED,
                LocatingTraceOutcome.OK,
                ".",
                "CLOCK",
                "{}");
        assertThat(ToolBudgetCalibration.hitRelaxedCap(List.of(exhausted))).isTrue();
        assertThat(ToolBudgetCalibration.hitRelaxedCap(List.of(clock))).isFalse();
        assertThat(ToolBudgetCalibration.toolCalls(List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.SEARCH, "src", "search", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.READ, "src/A.java", "read", "{}"),
                        LocatingTraceStep.of(2, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"),
                        exhausted)))
                .isEqualTo(3);
        assertThat(ToolBudgetCalibration.reachedSubmit(List.of(
                        LocatingTraceStep.of(
                                0,
                                LocatingStepKind.SUBMIT,
                                LocatingTraceOutcome.ERROR,
                                ".",
                                "submit",
                                "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"))))
                .isTrue();
    }

    @Test
    void transportClassifierMatches429AndHttpZeroWithoutTreatingAuthAsTransport() {
        assertThat(ToolBudgetCalibration.isTransportFailure(new RuntimeException("HTTP 429 Too Many Requests")))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportFailure(new RuntimeException("curl HTTP=000")))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportFailure(
                        new RuntimeException(new java.net.ConnectException("connection refused"))))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportFailure(new RuntimeException("Unauthorized 401")))
                .isFalse();
    }

    @Test
    void measureBudgetStaysAtRelaxedLimitsAndDoesNotChangeDefaults() {
        assertThat(ToolBudgetCalibration.MEASURE_MAX_CALLS).isEqualTo(60);
        assertThat(ToolBudgetCalibration.MEASURE_WALL_CLOCK).isEqualTo(Duration.ofMinutes(15));
        assertThat(io.github.patchatlas.analysis.LocalizationBudget.MAX_TOOL_CALLS).isEqualTo(25);
        assertThat(io.github.patchatlas.analysis.LocalizationBudget.WALL_CLOCK).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void sanitizeStripsCredentialAssignments() {
        assertThat(ToolBudgetCalibration.sanitize("OPENAI_API_KEY=sk-secret leftover"))
                .doesNotContain("sk-secret")
                .contains("OPENAI_API_KEY=***");
    }

    @Test
    @Tag("model")
    @EnabledIfEnvironmentVariable(named = "PATCHATLAS_CALIBRATE_027", matches = "1")
    void runCalibrationWhenRequested() throws Exception {
        int code = ToolBudgetCalibration.execute();
        assertThat(code).isIn(0, 2);
    }

    private static ToolBudgetCalibration.Slot slotWithDigest(String digest) {
        return new ToolBudgetCalibration.Slot(
                1,
                "Bindambc-whatsapp-business-java-api-362caf5eb33c",
                "https://github.com/Bindambc/whatsapp-business-java-api.git",
                "https://github.com/Bindambc/whatsapp-business-java-api/issues/100",
                "MIT",
                "",
                "17",
                digest,
                "dec4ca17d1194c063e63197d214322f2149c4ee5");
    }

    private static ToolBudgetCalibration.SessionRow transportStartFail() {
        return new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                0,
                false,
                "TRANSPORT",
                false,
                true,
                false,
                false,
                List.of(),
                List.of(),
                List.of());
    }

    private static ToolBudgetCalibration.SessionRow transportMidFail() {
        return new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                3,
                false,
                "TRANSPORT",
                false,
                true,
                false,
                true,
                List.of(),
                List.of(),
                List.of());
    }

    private static ToolBudgetCalibration.SessionRow okSession() {
        return new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                8,
                true,
                "SUBMIT",
                false,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of());
    }
}

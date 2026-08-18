package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.agent.PatchRejectionCategory;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.CaseResult;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.CaseRejections;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.GenerationRejection;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.GenerationRejectionLog;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.ResultsExport;
import io.github.patchatlas.benchmark.LocalizationCoverageEvaluator.Score;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.run.RunPurpose;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TestPatchProvenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Evidence exporter: cohort consistency and mechanical results.json / Markdown. */
class BenchmarkEvidenceExporterTest {

    private static final Cohort COHORT = sampleCohort();
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @TempDir
    Path tempDir;

    private Path protocolPath;

    @BeforeEach
    void writeProtocol() throws IOException {
        protocolPath = tempDir.resolve("protocol.json");
        Files.writeString(protocolPath, """
                {
                  "provider": "agnes",
                  "model": "agnes-2.5-flash",
                  "endpoint": "https://apihub.agnes-ai.com/v1",
                  "limitations": [
                    "该 model 标识无日期版本锚点，供应商可在不改名的前提下更换权重，因此本批次结果不保证长期可复现。",
                    "该模型的训练数据构成与知识截止时间未公开，无法论证其对 GitBug-Java 案例的污染边界。"
                  ],
                  "failureHandling": "Patch Gate 的策略性拒绝可修正并进入下一次 Generation Attempt（三轮上限不变）；仅 WORKSPACE_UNSAFE 立即终态。该规则在任何正式模型调用之前确定。"
                }
                """);
    }

    @Test
    void exportsResultsJsonAndMarkdownWithCorrectCounts() throws IOException {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        ResultsExport export = exporter.export(COHORT, results, protocolPath, tempDir);

        assertThat(export.calibrationPassed()).isEqualTo(3);
        assertThat(export.agentReproductions()).isEqualTo(1);
        assertThat(export.agentFailures()).isEqualTo(2);
        assertThat(export.cases()).hasSize(6);
        assertThat(export.cohortSha256()).isEqualTo(COHORT.cohortSha256());
        assertThat(export.modelProvider()).isEqualTo("agnes");
        assertThat(export.modelName()).isEqualTo("agnes-2.5-flash");
        assertThat(export.protocolLimitations()).hasSize(2);

        assertThat(Files.readString(tempDir.resolve("results.json")))
                .contains("case-1")
                .contains("agnes-2.5-flash")
                .contains("无日期版本锚点")
                .contains("训练数据构成")
                .contains("failureHandling")
                .contains("Patch Gate 的策略性拒绝可修正");
        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("Calibration passed").contains("3 / 3");
        assertThat(md).contains("Agent VALID_REPRODUCTION").contains("1 / 3");
        assertThat(md).contains("Estimated Model Cost is unavailable");
        assertThat(md).doesNotContain("$0");
        assertThat(md).contains("Failed Candidate Drafts are not persisted");
        assertThat(md).contains("Model provider: agnes");
        assertThat(md).contains("Model: agnes-2.5-flash");
        assertThat(md).contains("openai/gpt-4.1-mini");
        assertThat(md).contains("Protocol Limitations");
        assertThat(md).contains("无日期版本锚点");
        assertThat(md).contains("训练数据构成");
        assertThat(md).contains("Failure Handling");
        assertThat(md).contains("Patch Gate 的策略性拒绝可修正");
        assertThat(md).contains("Locating");
        assertThat(md).contains("| none |");
    }

    @Test
    void rejectsCaseIdMismatch() {
        List<CaseResult> results = List.of(
                calibration(1, "WRONG", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        assertThatThrownBy(() -> exporter.export(COHORT, results, protocolPath, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caseId mismatch");
    }

    @Test
    void rejectsPurposeMismatch() {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentWithPurpose(4, "case-4", RunPurpose.DIAGNOSTIC, TestPatchProvenance.AGENT_GENERATED,
                        ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        assertThatThrownBy(() -> exporter.export(COHORT, results, protocolPath, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("purpose mismatch");
    }

    @Test
    void rejectsProvenanceMismatch() {
        List<CaseResult> results = List.of(
                calibrationWithProvenance(1, "case-1", TestPatchProvenance.AGENT_GENERATED,
                        ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        assertThatThrownBy(() -> exporter.export(COHORT, results, protocolPath, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provenance mismatch");
    }

    @Test
    void agentBenchmarkFailedWithoutCandidateExportsNullProvenance() throws IOException {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentFailedNoCandidate(4, "case-4"),
                agent(5, "case-5", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(6, "case-6", ReplayVerdict.NOT_REPRODUCED, false));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        ResultsExport export = exporter.export(COHORT, results, protocolPath, tempDir);

        assertThat(export.calibrationPassed()).isEqualTo(3);
        assertThat(export.agentReproductions()).isEqualTo(1);
        assertThat(export.agentFailures()).isEqualTo(2);
        assertThat(export.cases().get(3).provenance()).isNull();
        assertThat(export.cases().get(3).state()).isEqualTo("FAILED");
        assertThat(export.cases().get(3).candidatePatchSha256()).isNull();
        assertThat(export.cases().get(3).modelProvider()).isEqualTo("openai");
        assertThat(export.cases().get(0).modelProvider()).isNull();

        String json = Files.readString(tempDir.resolve("results.json"));
        assertThat(json).contains("\"case-4\"");
        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("GENERATION_EXHAUSTED");
    }

    @Test
    void rejectsWrongResultCount() {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        assertThatThrownBy(() -> exporter.export(COHORT, results, protocolPath, tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 6");
    }

    @Test
    void missingProtocolFileFailsExport() throws IOException {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        Path missing = tempDir.resolve("nonexistent-protocol.json");
        assertThatThrownBy(() -> exporter.export(COHORT, results, missing, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocol file missing");
    }

    @Test
    void rejectionLogDrivesDistributionAndContractLayer() throws IOException {
        Files.writeString(tempDir.resolve("generation-rejections.json"), """
                {
                  "cases": [
                    {
                      "caseId": "case-4",
                      "rejections": [
                        {"attemptOrdinal": 1, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"},
                        {"attemptOrdinal": 2, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"},
                        {"attemptOrdinal": 3, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"}
                      ]
                    },
                    {
                      "caseId": "case-5",
                      "rejections": [
                        {"attemptOrdinal": 1, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "hunk new count mismatch"},
                        {"attemptOrdinal": 2, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "hunk new count mismatch"},
                        {"attemptOrdinal": 3, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "hunk new count mismatch"}
                      ]
                    },
                    {
                      "caseId": "case-6",
                      "rejections": [
                        {"attemptOrdinal": 1, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"},
                        {"attemptOrdinal": 2, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"},
                        {"attemptOrdinal": 3, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"}
                      ]
                    }
                  ]
                }
                """);
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentFailedNoCandidate(4, "case-4"),
                agentFailedNoCandidate(5, "case-5"),
                agentFailedNoCandidate(6, "case-6"));

        ResultsExport export = new BenchmarkEvidenceExporter().export(COHORT, results, protocolPath, tempDir);

        assertThat(export.calibrationPassed()).isEqualTo(3);
        assertThat(export.agentReproductions()).isEqualTo(0);
        assertThat(export.agentFailures()).isEqualTo(3);
        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("| trailing non-patch text | 6 |");
        assertThat(md).contains("| hunk new count mismatch | 3 |");
        assertThat(md).contains("| total | 9 |");
        assertThat(md).contains("strictness choice, not a safety property");
        assertThat(md).contains("失败发生在输出契约层");
        assertThat(md).contains("Failed Candidate Drafts are not persisted");
    }

    @Test
    void frozenRejectionLogKeepsExistingFieldsWithoutRejectionCategory() throws IOException {
        GenerationRejectionLog log = new BenchmarkArtifacts().readJson(
                Path.of("benchmark-cases/task018/generation-rejections.json"),
                GenerationRejectionLog.class);

        assertThat(log.cases()).extracting(CaseRejections::caseId)
                .containsExactly(
                        "jhy-jsoup-a96ebc95f9ad",
                        "jhy-jsoup-9de27fa7cd82",
                        "AuthMe-ConfigMe-7bf10c513479");
        for (CaseRejections item : log.cases()) {
            assertThat(item.rejections()).isNotEmpty();
            for (GenerationRejection rejection : item.rejections()) {
                assertThat(rejection.feedbackCategory()).isEqualTo("PATCH_POLICY_REJECTED");
                assertThat(rejection.feedbackSummary()).isIn("trailing non-patch text", "hunk new count mismatch");
                assertThat(rejection.rejectionCategory()).isNull();
            }
        }
        assertThat(log.cases().get(0).rejections())
                .extracting(GenerationRejection::feedbackSummary)
                .containsOnly("trailing non-patch text");
        assertThat(log.cases().get(1).rejections())
                .extracting(GenerationRejection::feedbackSummary)
                .containsOnly("hunk new count mismatch");
    }

    @Test
    void rejectionCategoryKeepsPatchGateDistinctionsThatFeedbackCategoryFlattens() throws IOException {
        Files.writeString(tempDir.resolve("generation-rejections.json"), """
                {
                  "cases": [
                    {
                      "caseId": "case-4",
                      "rejections": [
                        {
                          "attemptOrdinal": 1,
                          "feedbackCategory": "PATCH_POLICY_REJECTED",
                          "feedbackSummary": "cannot derive unique target test",
                          "rejectionCategory": "TARGET_TEST_NOT_DERIVABLE"
                        }
                      ]
                    },
                    {
                      "caseId": "case-5",
                      "rejections": [
                        {
                          "attemptOrdinal": 1,
                          "feedbackCategory": "PATCH_POLICY_REJECTED",
                          "feedbackSummary": "target file not in patch",
                          "rejectionCategory": "TARGET_NOT_CHANGED_BY_PATCH"
                        }
                      ]
                    },
                    {
                      "caseId": "case-6",
                      "rejections": [
                        {
                          "attemptOrdinal": 1,
                          "feedbackCategory": "PATCH_APPLICATION_FAILED",
                          "feedbackSummary": "application failure",
                          "rejectionCategory": "APPLICATION_FAILURE"
                        }
                      ]
                    }
                  ]
                }
                """);
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentFailedNoCandidate(4, "case-4", 1),
                agentFailedNoCandidate(5, "case-5", 1),
                agentFailedNoCandidate(6, "case-6", 1));

        new BenchmarkEvidenceExporter().export(COHORT, results, protocolPath, tempDir);

        GenerationRejectionLog log = new BenchmarkArtifacts().readJson(
                tempDir.resolve("generation-rejections.json"),
                GenerationRejectionLog.class);
        assertThat(log.cases().get(0).rejections().get(0).feedbackCategory())
                .isEqualTo("PATCH_POLICY_REJECTED");
        assertThat(log.cases().get(1).rejections().get(0).feedbackCategory())
                .isEqualTo("PATCH_POLICY_REJECTED");
        assertThat(log.cases().get(0).rejections().get(0).feedbackSummary())
                .isEqualTo("cannot derive unique target test");
        assertThat(log.cases().get(1).rejections().get(0).feedbackSummary())
                .isEqualTo("target file not in patch");
        assertThat(log.cases().get(0).rejections().get(0).rejectionCategory())
                .isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(log.cases().get(1).rejections().get(0).rejectionCategory())
                .isEqualTo(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH);
        assertThat(log.cases().get(2).rejections().get(0).rejectionCategory())
                .isEqualTo(PatchRejectionCategory.APPLICATION_FAILURE);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("| TARGET_TEST_NOT_DERIVABLE | 1 |");
        assertThat(md).contains("| TARGET_NOT_CHANGED_BY_PATCH | 1 |");
        assertThat(md).contains("| APPLICATION_FAILURE | 1 |");
        assertThat(md).contains("| trailing non-patch text |");
    }

    @Test
    void truncationRejectionCategoryIsDistinctFromMalformedInRejectionLog() throws IOException {
        Files.writeString(tempDir.resolve("generation-rejections.json"), """
                {
                  "cases": [
                    {
                      "caseId": "case-4",
                      "rejections": [
                        {
                          "attemptOrdinal": 1,
                          "feedbackCategory": "PATCH_POLICY_REJECTED",
                          "feedbackSummary": "响应被截断",
                          "rejectionCategory": "RESPONSE_TRUNCATED"
                        }
                      ]
                    },
                    {
                      "caseId": "case-5",
                      "rejections": [
                        {
                          "attemptOrdinal": 1,
                          "feedbackCategory": "PATCH_POLICY_REJECTED",
                          "feedbackSummary": "hunk new count mismatch",
                          "rejectionCategory": "MALFORMED_OR_OVERSIZED_PATCH"
                        }
                      ]
                    },
                    {
                      "caseId": "case-6",
                      "rejections": [
                        {
                          "attemptOrdinal": 1,
                          "feedbackCategory": "PATCH_POLICY_REJECTED",
                          "feedbackSummary": "trailing non-patch text",
                          "rejectionCategory": "MALFORMED_OR_OVERSIZED_PATCH"
                        }
                      ]
                    }
                  ]
                }
                """);
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentFailedNoCandidate(4, "case-4", 1),
                agentFailedNoCandidate(5, "case-5", 1),
                agentFailedNoCandidate(6, "case-6", 1));

        new BenchmarkEvidenceExporter().export(COHORT, results, protocolPath, tempDir);

        GenerationRejectionLog log = new BenchmarkArtifacts().readJson(
                tempDir.resolve("generation-rejections.json"),
                GenerationRejectionLog.class);
        assertThat(log.cases().get(0).rejections().get(0).rejectionCategory())
                .isEqualTo(PatchRejectionCategory.RESPONSE_TRUNCATED);
        assertThat(log.cases().get(1).rejections().get(0).rejectionCategory())
                .isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("| RESPONSE_TRUNCATED | 1 |");
        assertThat(md).contains("| MALFORMED_OR_OVERSIZED_PATCH | 2 |");
    }

    @Test
    void exportsLocalizationCoverageNumbersAndNotApplicable() throws IOException {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));
        List<Score> coverage = List.of(
                new Score.Measured(true, 1.0, OptionalDouble.of(0.5), 2),
                new Score.NotApplicable(),
                new Score.Measured(false, 0.0, OptionalDouble.of(0.0), 1),
                new Score.Measured(true, 1.0 / 3.0, OptionalDouble.of(0.5), 2),
                new Score.NotApplicable(),
                new Score.NotApplicable());

        ResultsExport export = new BenchmarkEvidenceExporter()
                .export(COHORT, results, protocolPath, tempDir, coverage);

        assertThat(export.cases().get(0).localizationCoverage())
                .isEqualTo(new BenchmarkEvidenceExporter.LocalizationCoverageExport(true, 1.0, 0.5, 2));
        assertThat(export.cases().get(1).localizationCoverage()).isNull();
        assertThat(export.cases().get(2).localizationCoverage().anyHit()).isFalse();
        assertThat(export.cases().get(2).localizationCoverage().recall()).isEqualTo(0.0);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("Issue Localization Coverage");
        assertThat(md).contains("anyHit");
        assertThat(md).contains("recall");
        assertThat(md).contains("precision");
        assertThat(md).contains("selectedCount");
        assertThat(md).contains("| true | 1.0000 | 0.5000 | 2 |");
        assertThat(md).contains("| N/A | N/A | N/A | N/A |");
        assertThat(md).contains("| false | 0.0000 | 0.0000 | 1 |");
        String json = Files.readString(tempDir.resolve("results.json"));
        assertThat(json).contains("\"anyHit\"");
        assertThat(json).contains("\"localizationCoverage\" : null");
    }

    @Test
    void calibrationCaseResultsHaveNullModelIdentity() throws IOException {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        BenchmarkEvidenceExporter exporter = new BenchmarkEvidenceExporter();
        ResultsExport export = exporter.export(COHORT, results, protocolPath, tempDir);

        assertThat(export.cases().get(0).modelProvider()).isNull();
        assertThat(export.cases().get(0).modelName()).isNull();
        assertThat(export.cases().get(3).modelProvider()).isEqualTo("openai");
        assertThat(export.cases().get(3).modelName()).isEqualTo("gpt-4.1-mini");

        String json = Files.readString(tempDir.resolve("results.json"));
        assertThat(json).contains("\"modelProvider\" : \"agnes\"");
        assertThat(json).contains("\"modelName\" : \"agnes-2.5-flash\"");
    }

    @Test
    void rejectsWrongCoverageScoreCount() {
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        assertThatThrownBy(() -> new BenchmarkEvidenceExporter()
                        .export(COHORT, results, protocolPath, tempDir, List.of(new Score.NotApplicable())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage scores");
    }

    @Test
    void rejectsCohortPositionMismatch() {
        List<CaseResult> results = List.of(
                calibration(2, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        assertThatThrownBy(() -> new BenchmarkEvidenceExporter().export(COHORT, results, protocolPath, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cohort position mismatch");
    }

    @Test
    void generationRejectionRejectsNonPositiveOrdinal() {
        assertThatThrownBy(() -> new GenerationRejection(0, "PATCH_POLICY_REJECTED", "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptOrdinal");
    }

    @Test
    void rejectsInvalidCohortPosition() {
        assertThatThrownBy(() -> calibration(0, "case-1", ReplayVerdict.VALID_REPRODUCTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cohortPosition");
    }

    @Test
    void rejectionCountMismatchFailsExport() throws IOException {
        Files.writeString(tempDir.resolve("generation-rejections.json"), """
                {
                  "cases": [
                    {
                      "caseId": "case-4",
                      "rejections": [
                        {"attemptOrdinal": 1, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"}
                      ]
                    },
                    {
                      "caseId": "case-5",
                      "rejections": [
                        {"attemptOrdinal": 1, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "hunk new count mismatch"},
                        {"attemptOrdinal": 2, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "hunk new count mismatch"},
                        {"attemptOrdinal": 3, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "hunk new count mismatch"}
                      ]
                    },
                    {
                      "caseId": "case-6",
                      "rejections": [
                        {"attemptOrdinal": 1, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"},
                        {"attemptOrdinal": 2, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"},
                        {"attemptOrdinal": 3, "feedbackCategory": "PATCH_POLICY_REJECTED", "feedbackSummary": "trailing non-patch text"}
                      ]
                    }
                  ]
                }
                """);
        List<CaseResult> results = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentFailedNoCandidate(4, "case-4"),
                agentFailedNoCandidate(5, "case-5"),
                agentFailedNoCandidate(6, "case-6"));

        assertThatThrownBy(() -> new BenchmarkEvidenceExporter().export(COHORT, results, protocolPath, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation rejection count mismatch");
    }

    private static Cohort sampleCohort() {
        List<CohortCase> cases = List.of(
                caseAt(1, "case-1", BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(2, "case-2", BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(3, "case-3", BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(4, "case-4", BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(5, "case-5", BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(6, "case-6", BenchmarkArtifacts.Role.AGENT_BENCHMARK));
        return new Cohort(
                "fe986fb7919be62c2a6f611ee16659e849646798",
                "cc279be0a2cfe38a327d24d828a49b8425ae37e7",
                "task018-v1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                cases,
                List.of());
    }

    private static CohortCase caseAt(int position, String caseId, BenchmarkArtifacts.Role role) {
        return new CohortCase(
                position, role, caseId,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT", "", "17");
    }

    private static CaseResult calibration(int pos, String caseId, ReplayVerdict verdict) {
        return calibrationWithProvenance(pos, caseId, TestPatchProvenance.KNOWN_TRIGGER, verdict);
    }

    private static CaseResult calibrationWithProvenance(
            int pos, String caseId, TestPatchProvenance provenance, ReplayVerdict verdict) {
        return new CaseResult(
                pos, caseId, RunPurpose.CALIBRATION, UUID.randomUUID(),
                RunState.COMPLETED, provenance,
                Optional.ofNullable(verdict), Optional.empty(), Optional.empty(), Optional.empty(),
                0, null, null, 0, 0, 0, null,
                Optional.of("a".repeat(64)),
                Optional.of("c.T"), Optional.of("m"),
                NOW, NOW);
    }

    private static CaseResult agent(int pos, String caseId, ReplayVerdict verdict, boolean failed) {
        return agentWithPurpose(pos, caseId, RunPurpose.AGENT_BENCHMARK,
                TestPatchProvenance.AGENT_GENERATED, verdict, failed);
    }

    private static CaseResult agentFailedNoCandidate(int pos, String caseId) {
        return agentFailedNoCandidate(pos, caseId, 3);
    }

    private static CaseResult agentFailedNoCandidate(int pos, String caseId, int attempts) {
        return new CaseResult(
                pos, caseId, RunPurpose.AGENT_BENCHMARK, UUID.randomUUID(),
                RunState.FAILED, null,
                Optional.empty(),
                Optional.of("GENERATION"), Optional.of("GENERATION_EXHAUSTED"),
                Optional.of("generation attempts exhausted"),
                attempts, "openai", "gpt-4.1-mini", 100, 200, 300, 1,
                Optional.empty(), Optional.empty(), Optional.empty(),
                NOW, NOW);
    }

    private static CaseResult agentWithPurpose(
            int pos, String caseId, RunPurpose purpose, TestPatchProvenance provenance,
            ReplayVerdict verdict, boolean failed) {
        RunState state = failed ? RunState.FAILED : RunState.COMPLETED;
        return new CaseResult(
                pos, caseId, purpose, UUID.randomUUID(),
                state, provenance,
                Optional.ofNullable(verdict),
                failed ? Optional.of("GENERATION") : Optional.empty(),
                failed ? Optional.of("GENERATION_FAILURE") : Optional.empty(),
                failed ? Optional.of("model unavailable") : Optional.empty(),
                failed ? 3 : 1, "openai", "gpt-4.1-mini", 100, 200, 300, 1,
                Optional.of("a".repeat(64)),
                Optional.of("c.T"), Optional.of("m"),
                NOW, NOW);
    }
}

package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.CaseResult;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.ResultsExport;
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
        assertThat(md).contains("lower bounds").contains("not actual bills");
        assertThat(md).contains("Model provider: agnes");
        assertThat(md).contains("Model: agnes-2.5-flash");
        assertThat(md).contains("openai/gpt-4.1-mini");
        assertThat(md).contains("Protocol Limitations");
        assertThat(md).contains("无日期版本锚点");
        assertThat(md).contains("训练数据构成");
        assertThat(md).contains("Failure Handling");
        assertThat(md).contains("Patch Gate 的策略性拒绝可修正");
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
        return new CaseResult(
                pos, caseId, RunPurpose.AGENT_BENCHMARK, UUID.randomUUID(),
                RunState.FAILED, null,
                Optional.empty(),
                Optional.of("GENERATION"), Optional.of("GENERATION_EXHAUSTED"),
                Optional.of("generation attempts exhausted"),
                3, "openai", "gpt-4.1-mini", 100, 200, 300, 1,
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

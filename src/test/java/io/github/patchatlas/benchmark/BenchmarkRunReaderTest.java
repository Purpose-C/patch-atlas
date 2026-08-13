package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.CaseResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunPurpose;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TestPatchProvenance;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Converts RunDetailView facts into CaseResult rows for the evidence exporter. */
class BenchmarkRunReaderTest {

    private static final Cohort COHORT = sampleCohort();
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void convertsSixCompletedRunsToCaseResults() {
        List<RunDetailView> details = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        List<CaseResult> results = BenchmarkRunReader.toCaseResults(COHORT, details);

        assertThat(results).hasSize(6);
        assertThat(results.get(0).purpose()).isEqualTo(RunPurpose.CALIBRATION);
        assertThat(results.get(0).provenance()).isEqualTo(TestPatchProvenance.KNOWN_TRIGGER);
        assertThat(results.get(0).modelProvider()).isEqualTo("openai");
        assertThat(results.get(0).modelName()).isEqualTo("gpt-4.1-mini");
        assertThat(results.get(3).purpose()).isEqualTo(RunPurpose.AGENT_BENCHMARK);
        assertThat(results.get(3).provenance()).isEqualTo(TestPatchProvenance.AGENT_GENERATED);
        assertThat(results.get(5).state()).isEqualTo(RunState.FAILED);
        assertThat(results.get(5).failureCategory()).contains("GENERATION_FAILURE");
    }

    @Test
    void rejectsWrongCaseId() {
        List<RunDetailView> details = List.of(
                calibration(1, "WRONG", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        assertThatThrownBy(() -> BenchmarkRunReader.toCaseResults(COHORT, details))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caseId mismatch");
    }

    @Test
    void agentBenchmarkFailedWithoutCandidateProducesNullProvenance() {
        List<RunDetailView> details = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentFailedNoCandidate(4, "case-4"),
                agent(5, "case-5", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(6, "case-6", ReplayVerdict.NOT_REPRODUCED, false));

        List<CaseResult> results = BenchmarkRunReader.toCaseResults(COHORT, details);

        assertThat(results).hasSize(6);
        assertThat(results.get(3).state()).isEqualTo(RunState.FAILED);
        assertThat(results.get(3).provenance()).isNull();
        assertThat(results.get(3).candidatePatchSha256()).isEmpty();
        assertThat(results.get(3).failureCategory()).contains("GENERATION_EXHAUSTED");
    }

    @Test
    void rejectsWrongPurpose() {
        List<RunDetailView> details = List.of(
                calibration(1, "case-1", ReplayVerdict.VALID_REPRODUCTION),
                calibration(2, "case-2", ReplayVerdict.VALID_REPRODUCTION),
                calibration(3, "case-3", ReplayVerdict.VALID_REPRODUCTION),
                agentWithPurpose(4, "case-4", RunPurpose.DIAGNOSTIC,
                        TestPatchProvenance.AGENT_GENERATED, ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        assertThatThrownBy(() -> BenchmarkRunReader.toCaseResults(COHORT, details))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("purpose mismatch");
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

    private static RunDetailView calibration(int pos, String caseId, ReplayVerdict verdict) {
        return detail(pos, caseId, RunPurpose.CALIBRATION, TestPatchProvenance.KNOWN_TRIGGER,
                RunState.COMPLETED, Optional.ofNullable(verdict), false);
    }

    private static RunDetailView agent(int pos, String caseId, ReplayVerdict verdict, boolean failed) {
        return detail(pos, caseId, RunPurpose.AGENT_BENCHMARK, TestPatchProvenance.AGENT_GENERATED,
                failed ? RunState.FAILED : RunState.COMPLETED, Optional.ofNullable(verdict), failed);
    }

    private static RunDetailView agentWithPurpose(
            int pos, String caseId, RunPurpose purpose, TestPatchProvenance provenance,
            ReplayVerdict verdict, boolean failed) {
        return detail(pos, caseId, purpose, provenance,
                failed ? RunState.FAILED : RunState.COMPLETED, Optional.ofNullable(verdict), failed);
    }

    private static RunDetailView agentFailedNoCandidate(int pos, String caseId) {
        return new RunDetailView(
                UUID.randomUUID(),
                VerificationMode.HISTORICAL,
                RunPurpose.AGENT_BENCHMARK,
                RunState.FAILED,
                caseId,
                NOW, NOW, NOW,
                new RunDetailView.InputSummary(
                        "https://github.com/ex/repo.git", "url", "t", "b",
                        "aaa", "bbb", ""),
                new MavenExecutionPolicy("17", MavenNetworkMode.OFFLINE),
                new RunDetailView.GenerationMeta(3, "openai", "gpt-4.1-mini", 100, 200, 300, 1),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new RunFailure(
                        FailureStage.GENERATION, FailureCategory.GENERATION_EXHAUSTED,
                        "generation attempts exhausted")),
                List.of());
    }

    private static RunDetailView detail(
            int pos, String caseId, RunPurpose purpose, TestPatchProvenance provenance,
            RunState state, Optional<ReplayVerdict> verdict, boolean failed) {
        return new RunDetailView(
                UUID.randomUUID(),
                VerificationMode.HISTORICAL,
                purpose,
                state,
                caseId,
                NOW, NOW, NOW,
                new RunDetailView.InputSummary(
                        "https://github.com/ex/repo.git", "url", "t", "b",
                        "aaa", "bbb", ""),
                new MavenExecutionPolicy("17", MavenNetworkMode.OFFLINE),
                new RunDetailView.GenerationMeta(1, "openai", "gpt-4.1-mini", 100, 200, 300, 1),
                Optional.of(new RunDetailView.CandidateView(
                        "diff", "a".repeat(64), new TargetTest("c.T", "m"), provenance)),
                verdict,
                failed ? Optional.of(new RunFailure(
                        FailureStage.GENERATION, FailureCategory.GENERATION_FAILURE, "model unavailable"))
                        : Optional.empty(),
                List.of());
    }
}

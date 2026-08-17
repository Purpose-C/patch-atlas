package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.CaseResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunPurpose;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TestPatchProvenance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 从 {@link RunDetailView} 读取 Run 事实并转换为 {@link CaseResult}，供 Evidence Exporter 使用。
 *
 * <p>不访问数据库；调用方负责从 PostgreSQL 读取 {@code RunDetailView}。
 */
public final class BenchmarkRunReader {

    private BenchmarkRunReader() {}

    /**
     * 将六例 RunDetailView 转换为 CaseResult 列表；校验位置、caseId、purpose 与 provenance。
     *
     * @param cohort  冻结 cohort
     * @param details 按位置排列的六例 RunDetailView
     */
    public static List<CaseResult> toCaseResults(Cohort cohort, List<RunDetailView> details) {
        if (details.size() != 6) {
            throw new IllegalArgumentException("expected 6 run details, got " + details.size());
        }
        return java.util.stream.IntStream.range(0, 6).mapToObj(i -> {
            CohortCase cc = cohort.cases().get(i);
            RunDetailView detail = details.get(i);
            return toCaseResult(cc, detail);
        }).toList();
    }

    private static CaseResult toCaseResult(CohortCase cohortCase, RunDetailView detail) {
        if (!detail.caseId().equals(cohortCase.caseId())) {
            throw new IllegalStateException(
                    "caseId mismatch at position " + cohortCase.position()
                            + ": run=" + detail.caseId()
                            + " cohort=" + cohortCase.caseId());
        }
        RunPurpose expectedPurpose = cohortCase.role() == BenchmarkArtifacts.Role.CALIBRATION
                ? RunPurpose.CALIBRATION
                : RunPurpose.AGENT_BENCHMARK;
        if (detail.purpose() != expectedPurpose) {
            throw new IllegalStateException(
                    "purpose mismatch at position " + cohortCase.position()
                            + ": run=" + detail.purpose()
                            + " expected=" + expectedPurpose);
        }

        TestPatchProvenance provenance = detail.candidate()
                .map(RunDetailView.CandidateView::provenance)
                .orElse(null);
        TestPatchProvenance expectedProvenance = cohortCase.role() == BenchmarkArtifacts.Role.CALIBRATION
                ? TestPatchProvenance.KNOWN_TRIGGER
                : TestPatchProvenance.AGENT_GENERATED;
        if (detail.candidate().isPresent()) {
            if (provenance != expectedProvenance) {
                throw new IllegalStateException(
                        "provenance mismatch at position " + cohortCase.position()
                                + ": run=" + provenance
                                + " expected=" + expectedProvenance);
            }
        } else {
            if (detail.state() == RunState.COMPLETED) {
                throw new IllegalStateException(
                        "completed run at position " + cohortCase.position()
                                + " has no candidate; completed runs must have a patch");
            }
            if (cohortCase.role() == BenchmarkArtifacts.Role.CALIBRATION) {
                throw new IllegalStateException(
                        "calibration run at position " + cohortCase.position()
                                + " has no candidate; calibration writes Run and candidate in one transaction");
            }
        }

        UUID runId = detail.runId();
        Optional<ReplayVerdict> verdict = detail.verdict();
        Optional<String> failureStage = detail.failure().map(f -> f.stage().name());
        Optional<String> failureCategory = detail.failure().map(f -> f.category().name());
        Optional<String> failureSummary = detail.failure().map(RunFailure::summary);

        return new CaseResult(
                cohortCase.position(),
                cohortCase.caseId(),
                detail.purpose(),
                runId,
                detail.state(),
                provenance,
                verdict,
                failureStage,
                failureCategory,
                failureSummary,
                detail.generation().attemptCount(),
                detail.generation().modelProvider(),
                detail.generation().modelName(),
                detail.generation().inputTokens(),
                detail.generation().outputTokens(),
                detail.generation().totalTokens(),
                detail.generation().usageRecordCount(),
                detail.candidate().map(RunDetailView.CandidateView::patchSha256),
                detail.candidate().map(c -> c.targetTest().className()),
                detail.candidate().map(c -> c.targetTest().methodName()),
                detail.createdAt(),
                detail.completedAt(),
                detail.locatingUsage());
    }
}

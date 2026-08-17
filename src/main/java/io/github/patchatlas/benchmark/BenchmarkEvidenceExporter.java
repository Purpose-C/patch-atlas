package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.run.LocatingUsage;
import io.github.patchatlas.run.RunPurpose;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TestPatchProvenance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 从 Frozen Cohort + PostgreSQL Run 事实机械导出 {@code results.json} 与 Markdown Evidence Report。
 *
 * <p>生成器在 runId、caseId、purpose、provenance、cohort 位置或摘要不一致时失败。
 * 不手填任何数字；成功率、失败计数、token 全部由传入事实计算。
 */
public final class BenchmarkEvidenceExporter {

    /** 单案例的运行事实快照；由调用方从 PostgreSQL 读取后构造。 */
    public record CaseResult(
            int cohortPosition,
            String caseId,
            RunPurpose purpose,
            UUID runId,
            RunState state,
            TestPatchProvenance provenance,
            Optional<ReplayVerdict> verdict,
            Optional<String> failureStage,
            Optional<String> failureCategory,
            Optional<String> failureSummary,
            int generationAttemptCount,
            String modelProvider,
            String modelName,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            Integer usageRecordCount,
            Optional<String> candidatePatchSha256,
            Optional<String> targetTestClass,
            Optional<String> targetTestMethod,
            Instant createdAt,
            Instant completedAt,
            LocatingUsage locatingUsage) {

        public CaseResult {
            if (cohortPosition < 1 || cohortPosition > 6) {
                throw new IllegalArgumentException("cohortPosition must be 1..6");
            }
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(state, "state");
            // provenance may be null when FAILED without candidate
            verdict = Objects.requireNonNull(verdict, "verdict");
            failureStage = Objects.requireNonNull(failureStage, "failureStage");
            failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
            failureSummary = Objects.requireNonNull(failureSummary, "failureSummary");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(completedAt, "completedAt");
            candidatePatchSha256 = Objects.requireNonNull(candidatePatchSha256, "candidatePatchSha256");
            targetTestClass = Objects.requireNonNull(targetTestClass, "targetTestClass");
            targetTestMethod = Objects.requireNonNull(targetTestMethod, "targetTestMethod");
            locatingUsage = locatingUsage == null ? LocatingUsage.none() : locatingUsage;
        }

        public CaseResult(
                int cohortPosition,
                String caseId,
                RunPurpose purpose,
                UUID runId,
                RunState state,
                TestPatchProvenance provenance,
                Optional<ReplayVerdict> verdict,
                Optional<String> failureStage,
                Optional<String> failureCategory,
                Optional<String> failureSummary,
                int generationAttemptCount,
                String modelProvider,
                String modelName,
                long inputTokens,
                long outputTokens,
                long totalTokens,
                Integer usageRecordCount,
                Optional<String> candidatePatchSha256,
                Optional<String> targetTestClass,
                Optional<String> targetTestMethod,
                Instant createdAt,
                Instant completedAt) {
            this(
                    cohortPosition,
                    caseId,
                    purpose,
                    runId,
                    state,
                    provenance,
                    verdict,
                    failureStage,
                    failureCategory,
                    failureSummary,
                    generationAttemptCount,
                    modelProvider,
                    modelName,
                    inputTokens,
                    outputTokens,
                    totalTokens,
                    usageRecordCount,
                    candidatePatchSha256,
                    targetTestClass,
                    targetTestMethod,
                    createdAt,
                    completedAt,
                    LocatingUsage.none());
        }
    }

    /** 导出的 results.json 模型。 */
    public record ResultsExport(
            String cohortSha256,
            String datasetRevision,
            String modelProvider,
            String modelName,
            String modelEndpoint,
            List<String> protocolLimitations,
            String failureHandling,
            int calibrationPassed,
            int agentReproductions,
            int agentFailures,
            List<CaseResultExport> cases) {

        public ResultsExport {
            Objects.requireNonNull(cohortSha256, "cohortSha256");
            Objects.requireNonNull(datasetRevision, "datasetRevision");
            protocolLimitations = List.copyOf(Objects.requireNonNull(protocolLimitations, "protocolLimitations"));
            Objects.requireNonNull(failureHandling, "failureHandling");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        }
    }

    public record CaseResultExport(
            int position,
            String role,
            String caseId,
            String runId,
            String purpose,
            String provenance,
            String state,
            String verdict,
            String failureStage,
            String failureCategory,
            String failureSummary,
            int generationAttemptCount,
            String modelProvider,
            String modelName,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            String usageRecordCount,
            String candidatePatchSha256,
            String targetTestClass,
            String targetTestMethod,
            Instant createdAt,
            Instant completedAt) {

        public CaseResultExport {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(purpose, "purpose");
            // provenance may be null when FAILED without candidate
            Objects.requireNonNull(state, "state");
        }
    }

    private final BenchmarkArtifacts artifacts;

    public BenchmarkEvidenceExporter() {
        this(new BenchmarkArtifacts());
    }

    BenchmarkEvidenceExporter(BenchmarkArtifacts artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    /**
     * 导出 results.json 与 Markdown 报告；校验每个 CaseResult 与 Cohort 一致。
     *
     * @param cohort        冻结的 cohort
     * @param results       按位置排序的六例运行事实
     * @param protocolPath  protocol.json 路径（缺失时导出失败）
     * @param outputDir     输出目录
     */
    public ResultsExport export(Cohort cohort, List<CaseResult> results, Path protocolPath, Path outputDir)
            throws IOException {
        Objects.requireNonNull(cohort, "cohort");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(protocolPath, "protocolPath");
        Objects.requireNonNull(outputDir, "outputDir");
        if (results.size() != 6) {
            throw new IllegalArgumentException("expected 6 case results, got " + results.size());
        }

        BenchmarkArtifacts.ProtocolMetadata protocol = artifacts.readProtocol(protocolPath);

        List<CaseResultExport> exports = new ArrayList<>(6);
        int calibrationPassed = 0;
        int agentReproductions = 0;
        int agentFailures = 0;

        for (int i = 0; i < 6; i++) {
            CohortCase cohortCase = cohort.cases().get(i);
            CaseResult result = results.get(i);

            if (result.cohortPosition() != cohortCase.position()) {
                throw new IllegalStateException(
                        "cohort position mismatch: result=" + result.cohortPosition()
                                + " cohort=" + cohortCase.position());
            }
            if (!result.caseId().equals(cohortCase.caseId())) {
                throw new IllegalStateException(
                        "caseId mismatch at position " + cohortCase.position()
                                + ": result=" + result.caseId()
                                + " cohort=" + cohortCase.caseId());
            }

            BenchmarkArtifacts.Role expectedRole = cohortCase.position() <= 3
                    ? BenchmarkArtifacts.Role.CALIBRATION
                    : BenchmarkArtifacts.Role.AGENT_BENCHMARK;
            RunPurpose expectedPurpose = expectedRole == BenchmarkArtifacts.Role.CALIBRATION
                    ? RunPurpose.CALIBRATION
                    : RunPurpose.AGENT_BENCHMARK;

            if (result.purpose() != expectedPurpose) {
                throw new IllegalStateException(
                        "purpose mismatch at position " + cohortCase.position()
                                + ": result=" + result.purpose()
                                + " expected=" + expectedPurpose);
            }

            TestPatchProvenance expectedProvenance = expectedRole == BenchmarkArtifacts.Role.CALIBRATION
                    ? TestPatchProvenance.KNOWN_TRIGGER
                    : TestPatchProvenance.AGENT_GENERATED;

            if (result.provenance() != null) {
                if (result.provenance() != expectedProvenance) {
                    throw new IllegalStateException(
                            "provenance mismatch at position " + cohortCase.position()
                                    + ": result=" + result.provenance()
                                    + " expected=" + expectedProvenance);
                }
            } else if (result.state() == RunState.COMPLETED) {
                throw new IllegalStateException(
                        "completed run at position " + cohortCase.position()
                                + " has no candidate; completed runs must have a patch");
            }

            if (result.state() == RunState.COMPLETED
                    && result.verdict().map(ReplayVerdict.VALID_REPRODUCTION::equals).orElse(false)) {
                if (expectedRole == BenchmarkArtifacts.Role.CALIBRATION) {
                    calibrationPassed++;
                } else {
                    agentReproductions++;
                }
            } else if (result.state() == RunState.FAILED
                    && expectedRole == BenchmarkArtifacts.Role.AGENT_BENCHMARK) {
                agentFailures++;
            } else if (result.state() == RunState.COMPLETED
                    && expectedRole == BenchmarkArtifacts.Role.AGENT_BENCHMARK
                    && !result.verdict().map(ReplayVerdict.VALID_REPRODUCTION::equals).orElse(false)) {
                agentFailures++;
            }

            exports.add(toExport(cohortCase, result));
        }

        ResultsExport export = new ResultsExport(
                cohort.cohortSha256(),
                cohort.datasetRevision(),
                protocol.provider(),
                protocol.model(),
                protocol.endpoint(),
                protocol.limitations(),
                protocol.failureHandling(),
                calibrationPassed,
                agentReproductions,
                agentFailures,
                exports);

        artifacts.write(outputDir.resolve("results.json"), export);
        GenerationRejectionLog rejections = readRejections(protocolPath.getParent(), results);
        writeMarkdownReport(outputDir, cohort, export, results, rejections);
        return export;
    }

    public record GenerationRejection(int attemptOrdinal, String feedbackCategory, String feedbackSummary) {
        public GenerationRejection {
            if (attemptOrdinal < 1) {
                throw new IllegalArgumentException("attemptOrdinal must be >= 1");
            }
            Objects.requireNonNull(feedbackCategory, "feedbackCategory");
            Objects.requireNonNull(feedbackSummary, "feedbackSummary");
        }
    }

    public record CaseRejections(String caseId, List<GenerationRejection> rejections) {
        public CaseRejections {
            Objects.requireNonNull(caseId, "caseId");
            rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        }
    }

    public record GenerationRejectionLog(List<CaseRejections> cases) {
        public GenerationRejectionLog {
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        }
    }

    private GenerationRejectionLog readRejections(Path artifactsRoot, List<CaseResult> results)
            throws IOException {
        if (artifactsRoot == null) {
            return null;
        }
        Path path = artifactsRoot.resolve("generation-rejections.json");
        if (!java.nio.file.Files.isRegularFile(path)) {
            return null;
        }
        GenerationRejectionLog log = artifacts.readJson(path, GenerationRejectionLog.class);
        for (CaseResult result : results) {
            if (result.purpose() != RunPurpose.AGENT_BENCHMARK || result.generationAttemptCount() == 0) {
                continue;
            }
            CaseRejections found = log.cases().stream()
                    .filter(item -> result.caseId().equals(item.caseId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "generation rejections missing for " + result.caseId()));
            if (found.rejections().size() != result.generationAttemptCount()) {
                throw new IllegalStateException(
                        "generation rejection count mismatch for " + result.caseId()
                                + ": log=" + found.rejections().size()
                                + " attempts=" + result.generationAttemptCount());
            }
        }
        return log;
    }

    private static CaseResultExport toExport(CohortCase cohortCase, CaseResult result) {
        return new CaseResultExport(
                cohortCase.position(),
                cohortCase.role().name(),
                cohortCase.caseId(),
                result.runId().toString(),
                result.purpose().name(),
                result.provenance() != null ? result.provenance().name() : null,
                result.state().name(),
                result.verdict().map(Enum::name).orElse(null),
                result.failureStage().orElse(null),
                result.failureCategory().orElse(null),
                result.failureSummary().orElse(null),
                result.generationAttemptCount(),
                result.modelProvider(),
                result.modelName(),
                result.inputTokens(),
                result.outputTokens(),
                result.totalTokens(),
                result.usageRecordCount() != null ? result.usageRecordCount().toString() : null,
                result.candidatePatchSha256().orElse(null),
                result.targetTestClass().orElse(null),
                result.targetTestMethod().orElse(null),
                result.createdAt(),
                result.completedAt());
    }

    private void writeMarkdownReport(
            Path outputDir,
            Cohort cohort,
            ResultsExport export,
            List<CaseResult> results,
            GenerationRejectionLog rejections)
            throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Benchmark Evidence Report\n\n");
        md.append("- Dataset: GitBug-Java @ ").append(cohort.datasetRevision()).append('\n');
        md.append("- Cohort SHA-256: `").append(cohort.cohortSha256()).append("`\n");
        md.append("- Selector: ").append(cohort.selectorVersion()).append('\n');
        md.append("- Seed: `").append(cohort.seed()).append("`\n");
        md.append("- Model provider: ").append(export.modelProvider()).append('\n');
        md.append("- Model: ").append(export.modelName()).append('\n');
        md.append("- Endpoint: ").append(export.modelEndpoint()).append('\n');
        md.append("- Protocol family: OpenAI-compatible\n\n");

        md.append("## Protocol Limitations\n\n");
        for (String limitation : export.protocolLimitations()) {
            md.append("- ").append(limitation).append('\n');
        }
        md.append('\n');
        md.append("## Failure Handling\n\n");
        md.append(export.failureHandling()).append("\n\n");

        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n| --- | --- |\n");
        md.append("| Calibration passed | ").append(export.calibrationPassed()).append(" / 3 |\n");
        md.append("| Agent VALID_REPRODUCTION | ").append(export.agentReproductions()).append(" / 3 |\n");
        md.append("| Agent non-reproduction / failure | ").append(export.agentFailures()).append(" / 3 |\n\n");

        md.append("## Per-Case Results\n\n");
        md.append("| # | Role | Case | State | Verdict | Failure | Attempts | Model | Tokens (in/out/total) | Locating |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < 6; i++) {
            CohortCase cc = cohort.cases().get(i);
            CaseResult cr = results.get(i);
            md.append("| ").append(cc.position());
            md.append(" | ").append(cc.role().name());
            md.append(" | `").append(cc.caseId()).append("`");
            md.append(" | ").append(cr.state().name());
            md.append(" | ").append(cr.verdict().map(Enum::name).orElse("—"));
            String failure = cr.failureCategory().orElse("—");
            md.append(" | ").append(failure);
            md.append(" | ").append(cr.generationAttemptCount());
            String modelId = cr.modelProvider() != null && cr.modelName() != null
                    ? cr.modelProvider() + "/" + cr.modelName()
                    : "—";
            md.append(" | ").append(modelId);
            md.append(" | ").append(cr.inputTokens()).append('/').append(cr.outputTokens())
                    .append('/').append(cr.totalTokens());
            md.append(" | ").append(cr.locatingUsage().reportLabel()).append(" |\n");
        }
        md.append('\n');

        md.append("## Estimated Model Cost\n\n");
        md.append("Estimated Model Cost is unavailable. Provider `")
                .append(export.modelProvider())
                .append("` has no Pricing Reference in this batch. ")
                .append("Missing cost must not be shown as a zero bill. ")
                .append("Token columns above are recorded usage, not a bill.\n\n");

        md.append("## Interpretation Limits\n\n");
        md.append("Failed Candidate Drafts are not persisted (ADR-002). ")
                .append("The system records token counts and Patch Gate summaries, ")
                .append("but not the rejected patch text. ")
                .append("A generation failure therefore cannot be read as proof that ")
                .append("the test design itself was wrong.\n\n");

        if (rejections != null) {
            int trailing = 0;
            int hunkMismatch = 0;
            int other = 0;
            int total = 0;
            for (CaseRejections item : rejections.cases()) {
                for (GenerationRejection rejection : item.rejections()) {
                    total++;
                    String summary = rejection.feedbackSummary();
                    if ("trailing non-patch text".equals(summary)) {
                        trailing++;
                    } else if ("hunk new count mismatch".equals(summary)) {
                        hunkMismatch++;
                    } else {
                        other++;
                    }
                }
            }
            md.append("## Generation Rejection Distribution\n\n");
            md.append("Counts are computed from structured `generation.attempt.rejected` records.\n\n");
            md.append("| feedback_summary | Count |\n| --- | --- |\n");
            md.append("| trailing non-patch text | ").append(trailing).append(" |\n");
            md.append("| hunk new count mismatch | ").append(hunkMismatch).append(" |\n");
            if (other > 0) {
                md.append("| other | ").append(other).append(" |\n");
            }
            md.append("| total | ").append(total).append(" |\n\n");

            md.append("These two rejection classes are not equivalent. ")
                    .append("`hunk new count mismatch` is a safety-relevant integrity check: ")
                    .append("a hunk whose declared line count does not match its body can corrupt a file and must be rejected. ")
                    .append("`trailing non-patch text` is a strictness choice, not a safety property: ")
                    .append("a parser can stop after the last hunk. ")
                    .append("This Gate therefore measures whether a model can emit a patch that satisfies this Gate, ")
                    .append("including a non-safety cleanliness rule. A different Gate tolerance would change the numbers. ")
                    .append("This batch does not change the Gate; the observation belongs to follow-up work.\n\n");
        }

        boolean allAgentsExhaustedAtGate = results.stream()
                .filter(result -> result.purpose() == RunPurpose.AGENT_BENCHMARK)
                .allMatch(result -> result.state() == RunState.FAILED
                        && result.failureCategory().orElse("").equals("GENERATION_EXHAUSTED")
                        && result.generationAttemptCount() == 3
                        && result.candidatePatchSha256().isEmpty());
        if (allAgentsExhaustedAtGate) {
            md.append("## Agent Outcome Layer\n\n");
            md.append("该模型在各轮中均产出了实质性补丁内容，但始终未满足统一 diff 的格式契约；")
                    .append("失败发生在输出契约层，本次证据不足以判断其测试设计能力。\n\n");
        }

        Files.writeString(outputDir.resolve("evidence-report.md"), md.toString(), StandardCharsets.UTF_8);
    }
}

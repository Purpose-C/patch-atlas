package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.PreregisteredCriteria;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.PreregisteredCriterion;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.ProtocolMetadata;
import io.github.patchatlas.benchmark.LocalizationCoverageEvaluator.Score;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 三臂评测证据导出：三个指标分开写，复现率不作臂间比较，开头抄冻结局限。
 */
public final class ThreeArmEvidenceExporter {

    static final List<ContextOrigin> ARMS = List.of(
            ContextOrigin.HEURISTIC, ContextOrigin.TEXT_TOOLS, ContextOrigin.GRAPH_TOOLS);

    public enum LocatingTokenAccounting {
        NONE,
        UNKNOWN,
        RECORDED
    }

    public record LocatingCost(
            int toolCallCount,
            int expandCount,
            OptionalLong graphBuildDurationMs,
            Optional<Boolean> graphBuildCacheHit,
            LocatingTokenAccounting locatingTokens) {

        public LocatingCost {
            if (toolCallCount < 0 || expandCount < 0) {
                throw new IllegalArgumentException("counts must not be negative");
            }
            graphBuildDurationMs = graphBuildDurationMs == null ? OptionalLong.empty() : graphBuildDurationMs;
            graphBuildCacheHit = graphBuildCacheHit == null ? Optional.empty() : graphBuildCacheHit;
            Objects.requireNonNull(locatingTokens, "locatingTokens");
        }
    }

    public record ArmCaseFact(
            int cohortPosition,
            String caseId,
            ContextOrigin origin,
            UUID runId,
            RunState state,
            Optional<ReplayVerdict> verdict,
            Optional<String> failureCategory,
            int generationAttemptCount,
            String modelProvider,
            String modelName,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            Score coverage,
            LocatingCost locating) {

        public ArmCaseFact {
            if (cohortPosition < 1 || cohortPosition > 6) {
                throw new IllegalArgumentException("cohortPosition must be 1..6");
            }
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(origin, "origin");
            if (origin == ContextOrigin.PINNED) {
                throw new IllegalArgumentException("pinned origin is not a locating arm");
            }
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(state, "state");
            verdict = Objects.requireNonNull(verdict, "verdict");
            failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
            Objects.requireNonNull(modelProvider, "modelProvider");
            Objects.requireNonNull(modelName, "modelName");
            Objects.requireNonNull(coverage, "coverage");
            Objects.requireNonNull(locating, "locating");
        }
    }

    public record FirstRound(String feedbackCategory, String feedbackSummary) {
        public FirstRound {
            Objects.requireNonNull(feedbackCategory, "feedbackCategory");
            Objects.requireNonNull(feedbackSummary, "feedbackSummary");
        }
    }

    public record CaseFirstRound(String caseId, FirstRound firstRound) {
        public CaseFirstRound {
            Objects.requireNonNull(caseId, "caseId");
        }
    }

    public record ArmRejections(String origin, List<CaseFirstRound> cases) {
        public ArmRejections {
            Objects.requireNonNull(origin, "origin");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        }
    }

    public record FirstRoundRejectionLog(List<ArmRejections> arms) {
        public FirstRoundRejectionLog {
            arms = List.copyOf(Objects.requireNonNull(arms, "arms"));
        }
    }

    public record JudgementExport(String id, boolean holds, String measured, String conclusion) {
        public JudgementExport {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(measured, "measured");
            Objects.requireNonNull(conclusion, "conclusion");
        }
    }

    public record CoverageExport(boolean anyHit, double recall, Double precision, int selectedCount) {}

    public record CaseExport(
            int position,
            String caseId,
            String origin,
            String runId,
            String state,
            String verdict,
            String failureCategory,
            int generationAttemptCount,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            CoverageExport localizationCoverage,
            int locatingToolCallCount,
            int expandCount,
            Long graphBuildDurationMs,
            Boolean graphBuildCacheHit,
            String locatingTokens) {}

    public record ArmExport(
            String origin,
            int validReproductions,
            int runCount,
            int anyHitCount,
            double meanRecall,
            double meanPrecision,
            double meanSelectedCount,
            double generationTokenMedian,
            int generationTokenSampleCount,
            int runsNeverReachedGeneration,
            int coverageSampleCount,
            int precisionSampleCount,
            Double locatingToolCallP50,
            Double locatingToolCallP95,
            int expandCount,
            Double graphBuildDurationP50,
            Integer graphBuildCacheHits,
            List<CaseExport> cases) {

        public ArmExport {
            Objects.requireNonNull(origin, "origin");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        }
    }

    public record ResultsExport(
            String cohortSha256,
            String datasetRevision,
            String modelProvider,
            String modelName,
            String modelEndpoint,
            List<String> protocolLimitations,
            String failureHandling,
            List<ArmExport> arms,
            int firstRoundRejectionCount,
            int hunkCountMismatchCount,
            List<JudgementExport> preregisteredJudgements,
            List<String> pipelineNotes) {

        public ResultsExport {
            Objects.requireNonNull(cohortSha256, "cohortSha256");
            Objects.requireNonNull(datasetRevision, "datasetRevision");
            protocolLimitations = List.copyOf(Objects.requireNonNull(protocolLimitations, "protocolLimitations"));
            Objects.requireNonNull(failureHandling, "failureHandling");
            arms = List.copyOf(Objects.requireNonNull(arms, "arms"));
            preregisteredJudgements = List.copyOf(Objects.requireNonNull(preregisteredJudgements, "preregisteredJudgements"));
            pipelineNotes = List.copyOf(Objects.requireNonNull(pipelineNotes, "pipelineNotes"));
        }
    }

    private final BenchmarkArtifacts artifacts;

    public ThreeArmEvidenceExporter() {
        this(new BenchmarkArtifacts());
    }

    ThreeArmEvidenceExporter(BenchmarkArtifacts artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public ResultsExport export(
            Cohort cohort,
            Path protocolPath,
            Path criteriaPath,
            List<ArmCaseFact> facts,
            FirstRoundRejectionLog rejections,
            Path outputDir)
            throws IOException {
        Objects.requireNonNull(cohort, "cohort");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(rejections, "rejections");
        Objects.requireNonNull(outputDir, "outputDir");
        if (facts.size() != 18) {
            throw new IllegalArgumentException("expected 18 case facts, got " + facts.size());
        }
        ProtocolMetadata protocol = artifacts.readProtocol(protocolPath);
        PreregisteredCriteria criteria = artifacts.readPreregisteredCriteria(criteriaPath);
        Map<ContextOrigin, List<ArmCaseFact>> byArm = indexFacts(cohort, facts);
        validateRejections(cohort, rejections);

        int firstRoundTotal = 0;
        int hunkMismatch = 0;
        for (ArmRejections arm : rejections.arms()) {
            for (CaseFirstRound item : arm.cases()) {
                if (item.firstRound() == null) {
                    continue;
                }
                firstRoundTotal++;
                if (isHunkCountMismatch(item.firstRound().feedbackSummary())) {
                    hunkMismatch++;
                }
            }
        }
        int expandTotal = byArm.get(ContextOrigin.GRAPH_TOOLS).stream()
                .mapToInt(fact -> fact.locating().expandCount())
                .sum();

        List<ArmExport> armExports = new ArrayList<>(3);
        for (ContextOrigin origin : ARMS) {
            armExports.add(toArmExport(origin, byArm.get(origin)));
        }
        List<JudgementExport> judgements = List.of(
                hunkJudgement(criteria, hunkMismatch, firstRoundTotal),
                expandJudgement(criteria, expandTotal));
        ResultsExport export = new ResultsExport(
                cohort.cohortSha256(),
                cohort.datasetRevision(),
                protocol.provider(),
                protocol.model(),
                protocol.endpoint(),
                protocol.limitations(),
                protocol.failureHandling(),
                armExports,
                firstRoundTotal,
                hunkMismatch,
                judgements,
                pipelineNotes());
        artifacts.write(outputDir.resolve("results.json"), export);
        Files.writeString(
                outputDir.resolve("evidence-report.md"),
                markdown(cohort, export, byArm, rejections),
                StandardCharsets.UTF_8);
        return export;
    }

    static boolean isHunkCountMismatch(String summary) {
        return summary != null && summary.contains("hunk") && summary.contains("count mismatch");
    }

    static double percentile(List<Long> values, double p) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) {
            throw new IllegalArgumentException("percentile of empty list");
        }
        double idx = p * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted[lo];
        }
        double weight = idx - lo;
        return sorted[lo] * (1.0 - weight) + sorted[hi] * weight;
    }

    private static Map<ContextOrigin, List<ArmCaseFact>> indexFacts(Cohort cohort, List<ArmCaseFact> facts) {
        Map<ContextOrigin, List<ArmCaseFact>> byArm = new EnumMap<>(ContextOrigin.class);
        for (ContextOrigin origin : ARMS) {
            byArm.put(origin, new ArrayList<>(6));
        }
        for (ArmCaseFact fact : facts) {
            List<ArmCaseFact> arm = byArm.get(fact.origin());
            if (arm == null) {
                throw new IllegalArgumentException("unsupported origin " + fact.origin());
            }
            arm.add(fact);
        }
        for (ContextOrigin origin : ARMS) {
            List<ArmCaseFact> arm = byArm.get(origin);
            arm.sort(Comparator.comparingInt(ArmCaseFact::cohortPosition));
            if (arm.size() != 6) {
                throw new IllegalStateException("expected 6 facts for " + origin + ", got " + arm.size());
            }
            for (int i = 0; i < 6; i++) {
                CohortCase expected = cohort.cases().get(i);
                ArmCaseFact fact = arm.get(i);
                if (fact.cohortPosition() != expected.position() || !fact.caseId().equals(expected.caseId())) {
                    throw new IllegalStateException(
                            "fact mismatch at " + origin + " position " + expected.position());
                }
            }
        }
        return byArm;
    }

    private static void validateRejections(Cohort cohort, FirstRoundRejectionLog rejections) {
        if (rejections.arms().size() != 3) {
            throw new IllegalStateException("expected 3 arm rejection groups, got " + rejections.arms().size());
        }
        for (int i = 0; i < 3; i++) {
            ArmRejections arm = rejections.arms().get(i);
            if (!ARMS.get(i).name().equals(arm.origin())) {
                throw new IllegalStateException("rejection origin order must be HEURISTIC, TEXT_TOOLS, GRAPH_TOOLS");
            }
            if (arm.cases().size() != 6) {
                throw new IllegalStateException("expected 6 rejection rows for " + arm.origin());
            }
            for (int c = 0; c < 6; c++) {
                if (!cohort.cases().get(c).caseId().equals(arm.cases().get(c).caseId())) {
                    throw new IllegalStateException("rejection caseId mismatch at " + arm.origin());
                }
            }
        }
    }

    private static ArmExport toArmExport(ContextOrigin origin, List<ArmCaseFact> facts) {
        int reproductions = 0;
        int anyHit = 0;
        double recallSum = 0;
        double precisionSum = 0;
        double selectedSum = 0;
        int coverageN = 0;
        int precisionN = 0;
        int neverGenerated = 0;
        List<Long> tokens = new ArrayList<>(6);
        List<Long> toolCalls = new ArrayList<>(6);
        List<Long> builds = new ArrayList<>(6);
        int expand = 0;
        int cacheHits = 0;
        int buildRows = 0;
        List<CaseExport> cases = new ArrayList<>(6);
        for (ArmCaseFact fact : facts) {
            if (fact.verdict().orElse(null) == ReplayVerdict.VALID_REPRODUCTION) {
                reproductions++;
            }
            CoverageExport coverage = coverageExport(fact.coverage());
            if (coverage != null && coverage.anyHit()) {
                anyHit++;
            }
            if (coverage != null) {
                coverageN++;
                recallSum += coverage.recall();
                selectedSum += coverage.selectedCount();
                if (coverage.precision() != null) {
                    precisionN++;
                    precisionSum += coverage.precision();
                }
            }
            if (fact.generationAttemptCount() > 0) {
                tokens.add(fact.totalTokens());
            } else {
                neverGenerated++;
            }
            toolCalls.add((long) fact.locating().toolCallCount());
            expand += fact.locating().expandCount();
            if (fact.locating().graphBuildDurationMs().isPresent()) {
                builds.add(fact.locating().graphBuildDurationMs().getAsLong());
                buildRows++;
                if (fact.locating().graphBuildCacheHit().orElse(false)) {
                    cacheHits++;
                }
            }
            cases.add(new CaseExport(
                    fact.cohortPosition(),
                    fact.caseId(),
                    fact.origin().name(),
                    fact.runId().toString(),
                    fact.state().name(),
                    fact.verdict().map(Enum::name).orElse(null),
                    fact.failureCategory().orElse(null),
                    fact.generationAttemptCount(),
                    fact.inputTokens(),
                    fact.outputTokens(),
                    fact.totalTokens(),
                    coverage,
                    fact.locating().toolCallCount(),
                    fact.locating().expandCount(),
                    fact.locating().graphBuildDurationMs().isPresent()
                            ? fact.locating().graphBuildDurationMs().getAsLong()
                            : null,
                    fact.locating().graphBuildCacheHit().orElse(null),
                    fact.locating().locatingTokens().name()));
        }
        boolean tools = origin != ContextOrigin.HEURISTIC;
        boolean graph = origin == ContextOrigin.GRAPH_TOOLS;
        return new ArmExport(
                origin.name(),
                reproductions,
                6,
                anyHit,
                coverageN == 0 ? 0.0 : recallSum / coverageN,
                precisionN == 0 ? 0.0 : precisionSum / precisionN,
                coverageN == 0 ? 0.0 : selectedSum / coverageN,
                tokens.isEmpty() ? 0.0 : percentile(tokens, 0.5),
                tokens.size(),
                neverGenerated,
                coverageN,
                precisionN,
                tools ? percentile(toolCalls, 0.5) : null,
                tools ? percentile(toolCalls, 0.95) : null,
                expand,
                graph && !builds.isEmpty() ? percentile(builds, 0.5) : null,
                graph ? cacheHits : null,
                cases);
    }

    private static CoverageExport coverageExport(Score coverage) {
        return switch (coverage) {
            case Score.NotApplicable ignored -> null;
            case Score.Measured measured -> new CoverageExport(
                    measured.anyHit(),
                    measured.recall(),
                    measured.precision().isPresent() ? measured.precision().getAsDouble() : null,
                    measured.selectedCount());
        };
    }

    private static JudgementExport hunkJudgement(
            PreregisteredCriteria criteria, int hunkMismatch, int firstRoundTotal) {
        PreregisteredCriterion criterion = criterion(criteria, "hunk-count-mismatch");
        boolean holds = firstRoundTotal > 0 && hunkMismatch * 3 > firstRoundTotal;
        String measured = hunkMismatch + " / " + firstRoundTotal + " first-round rejections";
        return new JudgementExport(
                criterion.id(), holds, measured, holds ? criterion.ifHolds() : criterion.ifDoesNotHold());
    }

    private static JudgementExport expandJudgement(PreregisteredCriteria criteria, int expandTotal) {
        PreregisteredCriterion criterion = criterion(criteria, "graph-expand-unused");
        boolean holds = expandTotal == 0;
        String measured = "expand count: " + expandTotal;
        return new JudgementExport(
                criterion.id(), holds, measured, holds ? criterion.ifHolds() : criterion.ifDoesNotHold());
    }

    private static PreregisteredCriterion criterion(PreregisteredCriteria criteria, String id) {
        return criteria.criteria().stream()
                .filter(item -> id.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing preregistered criterion " + id));
    }

    private static List<String> pipelineNotes() {
        return List.of(
                "No evaluation cell was rerun.",
                "Stale diagnostic leases were marked failed so they would not be claimed ahead of this queue.",
                "GRAPH_BUILD locating-trace kind was applied to the evaluation database before the graph arm.",
                "No Docker, environment, or transfer stop condition occurred. One HEURISTIC run ended in LOCATING_NO_CONTEXT; that is a locating outcome, not an unrelated pipeline failure.");
    }

    private static String markdown(
            Cohort cohort,
            ResultsExport export,
            Map<ContextOrigin, List<ArmCaseFact>> byArm,
            FirstRoundRejectionLog rejections) {
        StringBuilder md = new StringBuilder();
        md.append("# Three-Arm Locating Evaluation Evidence\n\n");
        md.append("## Known limitations\n\n");
        md.append("These three limitations were frozen before any evaluation run.\n\n");
        List<String> limitations = export.protocolLimitations();
        int split = Math.max(0, limitations.size() - 3);
        for (String limitation : limitations.subList(split, limitations.size())) {
            md.append("1. ").append(limitation).append("\n");
        }
        md.append('\n');
        md.append("## Protocol\n\n");
        md.append("- Dataset: GitBug-Java @ ").append(cohort.datasetRevision()).append('\n');
        md.append("- Cohort SHA-256: `").append(cohort.cohortSha256()).append("`\n");
        md.append("- Model provider: ").append(export.modelProvider()).append('\n');
        md.append("- Model: ").append(export.modelName()).append('\n');
        md.append("- Endpoint: ").append(export.modelEndpoint()).append('\n');
        md.append("- Failure handling: ").append(export.failureHandling()).append("\n\n");
        md.append("Other protocol limitations:\n\n");
        for (String limitation : limitations.subList(0, split)) {
            md.append("- ").append(limitation).append('\n');
        }
        md.append('\n');

        for (ArmExport arm : export.arms()) {
            appendArm(md, arm, byArm.get(ContextOrigin.valueOf(arm.origin())));
        }

        md.append("## Paired localization coverage\n\n");
        md.append("Same case, three locating origins. Numbers only; this table is not a ranking.\n\n");
        md.append("| # | Case | HEURISTIC anyHit / recall / precision / selectedCount | TEXT_TOOLS anyHit / recall / precision / selectedCount | GRAPH_TOOLS anyHit / recall / precision / selectedCount |\n");
        md.append("| --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < 6; i++) {
            CohortCase cc = cohort.cases().get(i);
            md.append("| ").append(cc.position());
            md.append(" | `").append(cc.caseId()).append("`");
            for (ContextOrigin origin : ARMS) {
                md.append(" | ").append(coverageInline(byArm.get(origin).get(i).coverage()));
            }
            md.append(" |\n");
        }
        md.append('\n');

        md.append("## Paired cost\n\n");
        md.append("Same case, three locating origins. Generation tokens are recorded usage, not a bill.\n\n");
        md.append("| # | Case | HEURISTIC gen tokens / locating tools | TEXT_TOOLS gen tokens / locating tools | GRAPH_TOOLS gen tokens / locating tools / graph-build ms |\n");
        md.append("| --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < 6; i++) {
            CohortCase cc = cohort.cases().get(i);
            md.append("| ").append(cc.position());
            md.append(" | `").append(cc.caseId()).append("`");
            ArmCaseFact heuristic = byArm.get(ContextOrigin.HEURISTIC).get(i);
            ArmCaseFact text = byArm.get(ContextOrigin.TEXT_TOOLS).get(i);
            ArmCaseFact graph = byArm.get(ContextOrigin.GRAPH_TOOLS).get(i);
            md.append(" | ").append(heuristic.totalTokens()).append(" / —");
            md.append(" | ").append(text.totalTokens()).append(" / ").append(text.locating().toolCallCount());
            md.append(" | ").append(graph.totalTokens()).append(" / ").append(graph.locating().toolCallCount());
            md.append(" / ").append(graph.locating().graphBuildDurationMs().isPresent()
                    ? Long.toString(graph.locating().graphBuildDurationMs().getAsLong())
                    : "—");
            md.append(" |\n");
        }
        md.append('\n');

        md.append("## First-round generation rejections\n\n");
        md.append("Counts are first `generation.attempt.rejected` records (attempt_ordinal = 1). ");
        md.append("Runs with no first-round rejection are omitted from this denominator.\n\n");
        for (ArmRejections arm : rejections.arms()) {
            md.append("### ").append(arm.origin()).append("\n\n");
            Map<String, Integer> counts = new TreeMap<>();
            int total = 0;
            for (CaseFirstRound item : arm.cases()) {
                if (item.firstRound() == null) {
                    continue;
                }
                total++;
                String summary = item.firstRound().feedbackSummary();
                counts.merge(summary, 1, Integer::sum);
            }
            md.append("| feedback_summary | Count |\n| --- | --- |\n");
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                md.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
            md.append("| total | ").append(total).append(" |\n\n");
        }

        md.append("## Preregistered criteria\n\n");
        for (JudgementExport judgement : export.preregisteredJudgements()) {
            md.append("### `").append(judgement.id()).append("`\n\n");
            md.append("- Measured: ").append(judgement.measured()).append('\n');
            md.append("- Holds: ").append(judgement.holds()).append('\n');
            md.append("- Declared conclusion: ").append(judgement.conclusion()).append("\n\n");
        }

        md.append("## Run inventory\n\n");
        md.append("| Origin | # | Case | Run | State | Verdict | Failure | Attempts | Tokens (in/out/total) |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ArmExport arm : export.arms()) {
            for (CaseExport row : arm.cases()) {
                md.append("| ").append(row.origin());
                md.append(" | ").append(row.position());
                md.append(" | `").append(row.caseId()).append("`");
                md.append(" | `").append(row.runId()).append("`");
                md.append(" | ").append(row.state());
                md.append(" | ").append(row.verdict() == null ? "—" : row.verdict());
                md.append(" | ").append(row.failureCategory() == null ? "—" : row.failureCategory());
                md.append(" | ").append(row.generationAttemptCount());
                md.append(" | ").append(row.inputTokens()).append('/')
                        .append(row.outputTokens()).append('/').append(row.totalTokens());
                md.append(" |\n");
            }
        }
        md.append('\n');

        md.append("## Evaluation notes\n\n");
        for (String note : export.pipelineNotes()) {
            md.append("- ").append(note).append('\n');
        }
        md.append('\n');
        return md.toString();
    }

    private static void appendArm(StringBuilder md, ArmExport arm, List<ArmCaseFact> facts) {
        md.append("## Arm ").append(arm.origin()).append("\n\n");
        md.append("### Localization coverage\n\n");
        md.append("| # | Case | anyHit | recall | precision | selectedCount |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (ArmCaseFact fact : facts) {
            md.append("| ").append(fact.cohortPosition());
            md.append(" | `").append(fact.caseId()).append("`");
            md.append(" | ").append(coverageRow(fact.coverage()));
            md.append(" |\n");
        }
        md.append('\n');
        md.append("- anyHit: ").append(arm.anyHitCount()).append(" / ").append(arm.coverageSampleCount()).append('\n');
        md.append("- mean recall: ").append(formatRatio(arm.meanRecall()))
                .append(" (n=").append(arm.coverageSampleCount()).append(")\n");
        md.append("- mean precision: ").append(formatRatio(arm.meanPrecision()))
                .append(" (n=").append(arm.precisionSampleCount()).append(")\n");
        md.append("- mean selectedCount: ").append(formatCount(arm.meanSelectedCount()))
                .append(" (n=").append(arm.coverageSampleCount()).append(")\n\n");

        md.append("### Reproduction rate\n\n");
        md.append("VALID_REPRODUCTION ").append(arm.validReproductions()).append(" / ").append(arm.runCount()).append('\n');
        md.append("Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.\n\n");

        md.append("### Cost\n\n");
        md.append("- generation token median: ").append(formatCount(arm.generationTokenMedian()))
                .append(" (n=").append(arm.generationTokenSampleCount()).append(")\n");
        md.append("- runs that never reached generation: ").append(arm.runsNeverReachedGeneration()).append('\n');
        md.append("- locating model tokens: ").append(tokenLabel(facts.getFirst().locating().locatingTokens())).append('\n');
        if (arm.locatingToolCallP50() == null) {
            md.append("- locating tool calls: —\n");
        } else {
            md.append("- locating tool calls p50 / p95: ")
                    .append(formatCount(arm.locatingToolCallP50()))
                    .append(" / ")
                    .append(formatCount(arm.locatingToolCallP95()))
                    .append('\n');
        }
        if (ContextOrigin.GRAPH_TOOLS.name().equals(arm.origin())) {
            md.append("- expand count: ").append(arm.expandCount()).append('\n');
            md.append("- graph-build duration p50 / cache hits: ")
                    .append(arm.graphBuildDurationP50() == null ? "—" : formatCount(arm.graphBuildDurationP50()))
                    .append(" / ")
                    .append(arm.graphBuildCacheHits() == null ? "—" : arm.graphBuildCacheHits() + " / 6")
                    .append('\n');
        }
        md.append('\n');
    }

    private static String tokenLabel(LocatingTokenAccounting accounting) {
        return switch (accounting) {
            case NONE -> "none";
            case UNKNOWN -> "unknown";
            case RECORDED -> "recorded";
        };
    }

    private static String coverageRow(Score score) {
        return switch (score) {
            case Score.NotApplicable ignored -> "N/A | N/A | N/A | N/A";
            case Score.Measured measured -> measured.anyHit()
                    + " | " + formatRatio(measured.recall())
                    + " | " + formatOptionalRatio(measured.precision())
                    + " | " + measured.selectedCount();
        };
    }

    private static String coverageInline(Score score) {
        return switch (score) {
            case Score.NotApplicable ignored -> "N/A / N/A / N/A / N/A";
            case Score.Measured measured -> measured.anyHit()
                    + " / " + formatRatio(measured.recall())
                    + " / " + formatOptionalRatio(measured.precision())
                    + " / " + measured.selectedCount();
        };
    }

    private static String formatOptionalRatio(OptionalDouble value) {
        return value.isPresent() ? formatRatio(value.getAsDouble()) : "N/A";
    }

    private static String formatRatio(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatCount(double value) {
        if (Math.rint(value) == value) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}

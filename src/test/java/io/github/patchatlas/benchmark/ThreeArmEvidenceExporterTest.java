package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.LocalizationCoverageEvaluator.Score;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.ArmCaseFact;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.ArmExport;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.ArmRejections;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.CaseFirstRound;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.FirstRound;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.FirstRoundRejectionLog;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingCost;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingTokenAccounting;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.RunState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Three-arm evidence report: limitations first, three metrics separate, no ranking claims. */
class ThreeArmEvidenceExporterTest {

    private static final Cohort COHORT = sampleCohort();
    private static final Path PROTOCOL =
            Path.of("benchmark-cases/batch5-three-arm/protocol.json");
    private static final Path CRITERIA =
            Path.of("benchmark-cases/batch5-three-arm/preregistered-criteria.json");

    @TempDir
    Path tempDir;

    @Test
    void reportOpensWithFrozenLimitationsAndKeepsThreeMetricsSeparate() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        int limitations = md.indexOf("## Known limitations");
        int heuristic = md.indexOf("## Arm HEURISTIC");
        int text = md.indexOf("## Arm TEXT_TOOLS");
        int graph = md.indexOf("## Arm GRAPH_TOOLS");
        int pairedCoverage = md.indexOf("## Paired localization coverage");
        int pairedCost = md.indexOf("## Paired cost");
        assertThat(limitations).isGreaterThanOrEqualTo(0);
        assertThat(limitations).isLessThan(heuristic);
        assertThat(heuristic).isLessThan(text);
        assertThat(text).isLessThan(graph);
        assertThat(graph).isLessThan(pairedCoverage);
        assertThat(pairedCoverage).isLessThan(pairedCost);
        assertThat(md.indexOf("没有可供遍历的边")).isBetween(limitations, heuristic);
        assertThat(md.indexOf("对文本臂有利")).isBetween(limitations, heuristic);
        assertThat(md.indexOf("不得用于臂间比较")).isBetween(limitations, heuristic);

        assertThat(md).contains("VALID_REPRODUCTION 0 / 6");
        assertThat(md).contains("Denominator: every AGENT_BENCHMARK run on this arm");
        assertThat(md).contains("anyHit");
        assertThat(md).contains("recall");
        assertThat(md).contains("precision");
        assertThat(md).contains("selectedCount");
        assertThat(md).contains("| false | 0.0000 | N/A | 0 |");
        assertThat(md).contains("locating model tokens: unknown");
        assertThat(md).doesNotContain("locating model tokens: 0");
        assertThat(md).contains("locating model tokens: none");

        String paired = md.substring(pairedCoverage);
        assertThat(paired).doesNotContain("VALID_REPRODUCTION");
        assertThat(md)
                .doesNotContain("综合得分")
                .doesNotContain("图更好")
                .doesNotContain("文本更好")
                .doesNotContain("更好")
                .doesNotContain("更差")
                .doesNotContain("/Users/")
                .doesNotContain("Task 0");

        String json = Files.readString(tempDir.resolve("results.json"));
        assertThat(json).doesNotContain("composite").doesNotContain("overallScore");
        assertThat(json).contains("\"anyHit\"");
        assertThat(json).contains("\"selectedCount\"");
        assertThat(json).contains("\"validReproductions\"");
        assertThat(json).contains("\"runCount\" : 6");
    }

    @Test
    void coverageMeansSkipNotApplicableAndDoNotDivideBySix() throws Exception {
        List<ArmCaseFact> facts = new ArrayList<>(facts(0));
        facts.set(0, withCoverage(facts.get(0), new Score.NotApplicable()));

        ThreeArmEvidenceExporter.ResultsExport export = new ThreeArmEvidenceExporter()
                .export(COHORT, PROTOCOL, CRITERIA, facts, rejections(true), tempDir);
        ArmExport heuristic = export.arms().getFirst();

        assertThat(heuristic.meanRecall()).isEqualTo(0.2);
        assertThat(heuristic.meanSelectedCount()).isEqualTo(9.6);
        assertThat(heuristic.meanPrecision()).isEqualTo(0.5);
        assertThat(heuristic.runCount()).isEqualTo(6);
    }

    @Test
    void precisionMeanDropsEmptySelectionButRecallKeepsTheZero() throws Exception {
        ThreeArmEvidenceExporter.ResultsExport export = new ThreeArmEvidenceExporter()
                .export(COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);
        ArmExport heuristic = export.arms().getFirst();

        assertThat(heuristic.meanRecall()).isEqualTo(1.25 / 6.0);
        assertThat(heuristic.meanPrecision()).isEqualTo(0.5);
        assertThat(heuristic.meanSelectedCount()).isEqualTo(10.0);
        assertThat(heuristic.runCount()).isEqualTo(6);
    }

    @Test
    void generationTokenMedianExcludesRunsThatNeverGenerated() throws Exception {
        ThreeArmEvidenceExporter.ResultsExport export = new ThreeArmEvidenceExporter()
                .export(COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);
        ArmExport heuristic = export.arms().getFirst();

        assertThat(heuristic.generationTokenMedian()).isEqualTo(3300.0);
        assertThat(heuristic.runsNeverReachedGeneration()).isEqualTo(1);
        assertThat(heuristic.generationTokenSampleCount()).isEqualTo(5);
    }

    @Test
    void reproductionDenominatorKeepsRunsThatNeverReachedGeneration() throws Exception {
        ThreeArmEvidenceExporter.ResultsExport export = new ThreeArmEvidenceExporter()
                .export(COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);
        ArmExport heuristic = export.arms().getFirst();

        assertThat(heuristic.cases().get(4).generationAttemptCount()).isZero();
        assertThat(heuristic.runCount()).isEqualTo(6);
        assertThat(heuristic.validReproductions()).isZero();
        assertThat(Files.readString(tempDir.resolve("evidence-report.md")))
                .contains("VALID_REPRODUCTION 0 / 6");
    }

    @Test
    void undefinedPrecisionRendersAsNaNotZero() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("| false | 0.0000 | N/A | 0 |");
        assertThat(md).contains("false / 0.0000 / N/A / 0");
        assertThat(md).doesNotContain("| false | 0.0000 | 0.0000 | 0 |");
        assertThat(md).doesNotContain("false / 0.0000 / 0.0000 / 0");
    }

    @Test
    void meanAndCostLinesShowSampleCountsAndNeverGeneratedRuns() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("mean precision: 0.5000 (n=5)");
        assertThat(md).contains("mean recall: 0.2083 (n=6)");
        assertThat(md).contains("mean selectedCount: 10 (n=6)");
        assertThat(md).contains("generation token median: 3300 (n=5)");
        assertThat(md).contains("runs that never reached generation: 1");
    }

    @Test
    void hunkCountMismatchHoldingCopiesFrozenIfHoldsText() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("hunk-count-mismatch");
        assertThat(md).contains("10 / 16");
        assertThat(md).contains("另开工作把 hunk 计数改为从正文重算");
        assertThat(md).doesNotContain("维持现状，不改补丁解析器的 hunk 计数规则。");
        String json = Files.readString(tempDir.resolve("results.json"));
        assertThat(json).contains("\"id\" : \"hunk-count-mismatch\"");
        assertThat(json).contains("\"holds\" : true");
    }

    @Test
    void hunkCountMismatchNotHoldingCopiesFrozenIfDoesNotHoldText() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(0), rejections(false), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("1 / 16");
        assertThat(md).contains("维持现状，不改补丁解析器的 hunk 计数规则。");
        assertThat(md).doesNotContain("另开工作把 hunk 计数改为从正文重算");
    }

    @Test
    void expandUnusedHoldingCopiesFrozenIfHoldsAndDoesNotClaimGraphHasNoSignal() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(0), rejections(true), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("graph-expand-unused");
        assertThat(md).contains("expand count: 0");
        assertThat(md).contains("确认本队列不提供沿边遍历机会");
        assertThat(md).contains("不得据此断言图没有信息量");
        assertThat(md).doesNotContain("报告 expand 的实际使用次数");
    }

    @Test
    void expandUsedCopiesIfDoesNotHoldWithoutRankingLanguage() throws Exception {
        new ThreeArmEvidenceExporter().export(
                COHORT, PROTOCOL, CRITERIA, facts(2), rejections(true), tempDir);

        String md = Files.readString(tempDir.resolve("evidence-report.md"));
        assertThat(md).contains("expand count: 2");
        assertThat(md).contains("报告 expand 的实际使用次数");
        assertThat(md)
                .doesNotContain("更好")
                .doesNotContain("更差")
                .doesNotContain("综合得分");
    }

    @Test
    void rejectsFactCountOtherThanEighteen() {
        assertThatThrownBy(() -> new ThreeArmEvidenceExporter().export(
                        COHORT, PROTOCOL, CRITERIA, facts(0).subList(0, 6), rejections(true), tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("18");
    }

    private static List<ArmCaseFact> facts(int expandOnFirstGraphCase) {
        List<ArmCaseFact> facts = new ArrayList<>(18);
        for (ContextOrigin origin : List.of(
                ContextOrigin.HEURISTIC, ContextOrigin.TEXT_TOOLS, ContextOrigin.GRAPH_TOOLS)) {
            for (int position = 1; position <= 6; position++) {
                facts.add(fact(position, origin, expandOnFirstGraphCase));
            }
        }
        return facts;
    }

    private static ArmCaseFact fact(int position, ContextOrigin origin, int expandOnFirstGraphCase) {
        boolean locatingMiss = origin == ContextOrigin.HEURISTIC && position == 5;
        boolean inconclusive = origin == ContextOrigin.GRAPH_TOOLS && position == 2;
        RunState state = locatingMiss || !inconclusive ? RunState.FAILED : RunState.COMPLETED;
        Optional<ReplayVerdict> verdict =
                inconclusive ? Optional.of(ReplayVerdict.INCONCLUSIVE) : Optional.empty();
        Optional<String> failure = locatingMiss
                ? Optional.of("LOCATING_NO_CONTEXT")
                : inconclusive ? Optional.empty() : Optional.of("GENERATION_EXHAUSTED");
        int selected = locatingMiss ? 0 : origin == ContextOrigin.HEURISTIC ? 12 : position;
        double precision = selected == 0 ? 0.0 : 0.5;
        double recall = selected == 0 ? 0.0 : 0.25;
        Score coverage = new Score.Measured(
                selected > 0 && position <= 2,
                recall,
                selected == 0 ? OptionalDouble.empty() : OptionalDouble.of(precision),
                selected);
        int expand = origin == ContextOrigin.GRAPH_TOOLS && position == 1 ? expandOnFirstGraphCase : 0;
        LocatingTokenAccounting tokens = origin == ContextOrigin.HEURISTIC
                ? LocatingTokenAccounting.NONE
                : LocatingTokenAccounting.UNKNOWN;
        OptionalLong build = origin == ContextOrigin.GRAPH_TOOLS ? OptionalLong.of(100L * position) : OptionalLong.empty();
        Optional<Boolean> cache =
                origin == ContextOrigin.GRAPH_TOOLS ? Optional.of(false) : Optional.empty();
        return new ArmCaseFact(
                position,
                "case-" + position,
                origin,
                UUID.randomUUID(),
                state,
                verdict,
                failure,
                locatingMiss ? 0 : inconclusive ? 1 : 3,
                "ollama",
                "glm-5.2",
                locatingMiss ? 0 : 1000L * position,
                locatingMiss ? 0 : 100L,
                locatingMiss ? 0 : 1100L * position,
                coverage,
                new LocatingCost(
                        origin == ContextOrigin.HEURISTIC ? 0 : position * 2,
                        expand,
                        build,
                        cache,
                        tokens));
    }

    private static FirstRoundRejectionLog rejections(boolean hunkHolds) {
        return new FirstRoundRejectionLog(List.of(
                arm("HEURISTIC", hunkHolds),
                arm("TEXT_TOOLS", hunkHolds),
                arm("GRAPH_TOOLS", hunkHolds)));
    }

    private static ArmRejections arm(String origin, boolean hunkHolds) {
        List<CaseFirstRound> cases = new ArrayList<>(6);
        for (int position = 1; position <= 6; position++) {
            cases.add(new CaseFirstRound("case-" + position, firstRound(origin, position, hunkHolds)));
        }
        return new ArmRejections(origin, cases);
    }

    private static FirstRound firstRound(String origin, int position, boolean hunkHolds) {
        if ("HEURISTIC".equals(origin) && position == 5) {
            return null;
        }
        if ("GRAPH_TOOLS".equals(origin) && position == 2) {
            return null;
        }
        if (!hunkHolds) {
            return position == 1 && "HEURISTIC".equals(origin)
                    ? new FirstRound("STRUCTURED_OUTPUT_INVALID", "hunk new count mismatch")
                    : new FirstRound("PATCH_APPLICATION_FAILED", "application failure");
        }
        return switch (origin) {
            case "HEURISTIC" -> position == 3
                    ? new FirstRound("COMPILATION_FAILED", "compilation failed on buggy")
                    : new FirstRound("STRUCTURED_OUTPUT_INVALID", "hunk new count mismatch");
            case "TEXT_TOOLS" -> position == 2 || position == 3
                    ? new FirstRound("STRUCTURED_OUTPUT_INVALID", "hunk new count mismatch")
                    : new FirstRound("PATCH_APPLICATION_FAILED", "application failure");
            case "GRAPH_TOOLS" -> position == 4
                    ? new FirstRound("PATCH_APPLICATION_FAILED", "application failure")
                    : new FirstRound("STRUCTURED_OUTPUT_INVALID", "hunk new count mismatch");
            default -> throw new IllegalArgumentException(origin);
        };
    }

    private static ArmCaseFact withCoverage(ArmCaseFact fact, Score coverage) {
        return new ArmCaseFact(
                fact.cohortPosition(),
                fact.caseId(),
                fact.origin(),
                fact.runId(),
                fact.state(),
                fact.verdict(),
                fact.failureCategory(),
                fact.generationAttemptCount(),
                fact.modelProvider(),
                fact.modelName(),
                fact.inputTokens(),
                fact.outputTokens(),
                fact.totalTokens(),
                coverage,
                fact.locating());
    }

    private static Cohort sampleCohort() {
        List<CohortCase> cases = List.of(
                caseAt(1), caseAt(2), caseAt(3), caseAt(4), caseAt(5), caseAt(6));
        return new Cohort(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                "a".repeat(40),
                BenchmarkArtifacts.cohortSha256(cases),
                cases,
                List.of());
    }

    private static CohortCase caseAt(int position) {
        return new CohortCase(
                position,
                position <= 3 ? BenchmarkArtifacts.Role.CALIBRATION : BenchmarkArtifacts.Role.AGENT_BENCHMARK,
                "case-" + position,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT",
                "",
                "17");
    }
}

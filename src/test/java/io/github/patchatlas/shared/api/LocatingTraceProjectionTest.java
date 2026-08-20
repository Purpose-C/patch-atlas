package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceOutcome;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.LocatingUsage;
import io.github.patchatlas.run.RecordedUsageStatus;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LocatingTraceProjectionTest {

    private static final Set<String> ORACLE_TOKENS = Set.of(
            "anyHit", "recall", "precision", "OracleMetadata", "LocalizationCoverage");

    @Test
    void stepsStayInSeqOrderAndKeepKnownFields() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                recordedUsage(2),
                List.of(
                        step(1, LocatingStepKind.READ, "src/B.java", "read", "{}"),
                        step(0, LocatingStepKind.SEARCH, "Foo", "search", "{\"hits\":3,\"pattern\":\"Foo\"}")));

        assertThat(locating.steps()).extracting(RunDetailResponse.LocatingStep::seq).containsExactly(0, 1);
        assertThat(locating.steps().getFirst().kind()).isEqualTo("SEARCH");
        assertThat(locating.steps().getFirst().subject()).isEqualTo("Foo");
        assertThat(locating.steps().getFirst().reason()).isEqualTo("search");
        assertThat(locating.steps().getFirst().outcome()).isEqualTo("OK");
        assertThat(locating.steps().getFirst().detail()).containsEntry("hits", 3);
        assertThat(locating.steps().getFirst().detail()).containsEntry("pattern", "Foo");
        assertThat(locating.contextOrigin()).isEqualTo("TEXT_TOOLS");
        assertThat(locating.stepKindCounts()).containsEntry("SEARCH", 1).containsEntry("READ", 1);
    }

    @Test
    void dtoAndJsonDoNotCarryOracleCoverageFields() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                recordedUsage(1),
                List.of(step(
                        0,
                        LocatingStepKind.SEARCH,
                        "q",
                        "search",
                        "{\"hits\":1,\"anyHit\":true,\"recall\":0.5,\"precision\":1.0}")));

        assertThat(locating.steps().getFirst().detail()).doesNotContainKeys("anyHit", "recall", "precision");
        String json = JsonMapper.shared().writeValueAsString(locating);
        for (String token : ORACLE_TOKENS) {
            assertThat(json).doesNotContain(token);
        }
        for (Class<?> type : List.of(
                RunDetailResponse.class,
                RunDetailResponse.Locating.class,
                RunDetailResponse.LocatingStep.class)) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
                    .doesNotContainAnyElementsOf(ORACLE_TOKENS);
        }
    }

    @Test
    void heuristicArmMarksToolCountNotApplicableInsteadOfZero() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.HEURISTIC,
                LocatingUsage.none(),
                List.of(
                        step(0, LocatingStepKind.SELECTION, "src/A.java", "PINNED", "{}"),
                        step(1, LocatingStepKind.EXCLUSION, "src/ATest.java", "TEST_SOURCE", "{}")));

        assertThat(locating.toolCallsApplicable()).isFalse();
        assertThat(locating.toolCallCount()).isNull();
        assertThat(locating.steps()).extracting(RunDetailResponse.LocatingStep::kind)
                .containsExactly("SELECTION", "EXCLUSION");
        assertThat(JsonMapper.shared().writeValueAsString(locating)).doesNotContain("\"toolCallCount\":0");
    }

    @Test
    void unrecordedLocatingUsageDoesNotSerializeTokenZeros() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                new LocatingUsage(3, 0, 0, 0, 0),
                List.of(step(0, LocatingStepKind.SEARCH, "q", "search", "{\"hits\":1}")));

        assertThat(locating.usageStatus()).isEqualTo(RecordedUsageStatus.NONE_RECORDED.name());
        assertThat(locating.inputTokens()).isNull();
        assertThat(locating.outputTokens()).isNull();
        assertThat(locating.totalTokens()).isNull();
        String json = JsonMapper.shared().writeValueAsString(locating);
        assertThat(json).doesNotContain("\"inputTokens\":0");
        assertThat(json).doesNotContain("\"outputTokens\":0");
        assertThat(json).doesNotContain("\"totalTokens\":0");
    }

    @Test
    void rowLimitMarksTruncationInsteadOfDroppingQuietly() {
        List<LocatingTraceStep> steps = new ArrayList<>();
        for (int i = 0; i < LocatingTraceProjector.STEP_LIMIT + 1; i++) {
            steps.add(step(i, LocatingStepKind.SELECTION, "src/F" + i + ".java", "PINNED", "{}"));
        }

        RunDetailResponse.Locating locating =
                LocatingTraceProjector.project(ContextOrigin.HEURISTIC, LocatingUsage.none(), steps);

        assertThat(locating.truncated()).isTrue();
        assertThat(locating.stepLimit()).isEqualTo(LocatingTraceProjector.STEP_LIMIT);
        assertThat(locating.steps()).hasSize(LocatingTraceProjector.STEP_LIMIT);
        assertThat(locating.stepKindCounts()).containsEntry("SELECTION", LocatingTraceProjector.STEP_LIMIT + 1);
        assertThat(locating.steps().getLast().seq()).isEqualTo(LocatingTraceProjector.STEP_LIMIT - 1);
    }

    @Test
    void hostAbsolutePathsAreRelativizedOrDropped() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                recordedUsage(2),
                List.of(
                        step(
                                0,
                                LocatingStepKind.READ,
                                "/tmp/ws-1/src/main/java/A.java",
                                "read",
                                "{\"path\":\"/tmp/ws-1/src/main/java/A.java\",\"lines\":4}"),
                        step(1, LocatingStepKind.READ, "/tmp/secret/token", "read", "{\"path\":\"/tmp/secret/token\"}")));

        assertThat(locating.steps()).hasSize(1);
        assertThat(locating.steps().getFirst().subject()).isEqualTo("src/main/java/A.java");
        assertThat(locating.steps().getFirst().detail()).containsEntry("path", "src/main/java/A.java");
        String json = JsonMapper.shared().writeValueAsString(locating);
        assertThat(json).doesNotContain("/tmp/ws-1");
        assertThat(json).doesNotContain("/tmp/secret");
    }

    @Test
    void budgetExhaustedIsDistinctInOverview() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                recordedUsage(1),
                List.of(
                        step(0, LocatingStepKind.SEARCH, "q", "search", "{\"hits\":1}"),
                        step(
                                1,
                                LocatingStepKind.BUDGET_EXHAUSTED,
                                ".",
                                "CALLS",
                                "{\"limit\":\"CALLS\",\"used\":35,\"maxCalls\":35}")));

        assertThat(locating.budgetEvents()).extracting(RunDetailResponse.BudgetEvent::kind)
                .containsExactly("BUDGET_EXHAUSTED");
        assertThat(locating.budgetEvents().getFirst().limit()).isEqualTo("CALLS");
        assertThat(locating.budgetEvents().getFirst().used()).isEqualTo(35);
    }

    @Test
    void errorOutcomeIsCountedAndKeptOnTheStep() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                recordedUsage(1),
                List.of(new LocatingTraceStep(
                        java.util.UUID.randomUUID(),
                        0,
                        LocatingStepKind.READ,
                        LocatingTraceOutcome.ERROR,
                        "src/A.java",
                        "read",
                        "{\"errorType\":\"IllegalArgumentException\",\"message\":\"path rejected\"}")));

        assertThat(locating.errorCount()).isEqualTo(1);
        assertThat(locating.steps().getFirst().outcome()).isEqualTo("ERROR");
        assertThat(locating.steps().getFirst().detail()).containsEntry("errorType", "IllegalArgumentException");
    }

    @Test
    void missingTraceIsEmptyNotAnError() {
        RunDetailResponse.Locating locating =
                LocatingTraceProjector.project(ContextOrigin.HEURISTIC, LocatingUsage.none(), List.of());

        assertThat(locating.steps()).isEmpty();
        assertThat(locating.truncated()).isFalse();
        assertThat(locating.errorCount()).isZero();
        assertThat(locating.recorded()).isFalse();
    }

    @Test
    void graphBuildCacheHitStaysUnknownWhenAbsent() {
        RunDetailResponse.Locating locating = LocatingTraceProjector.project(
                ContextOrigin.GRAPH_TOOLS,
                recordedUsage(1),
                List.of(step(
                        0, LocatingStepKind.GRAPH_BUILD, "graph", "GRAPH_BUILD", "{\"durationMs\":12}")));

        assertThat(locating.graphBuild()).isNotNull();
        assertThat(locating.graphBuild().durationMs()).isEqualTo(12L);
        assertThat(locating.graphBuild().cacheHit()).isNull();
    }

    @Test
    void coversRemainingProjectionBranches() {
        RunDetailResponse.Locating fromNulls =
                LocatingTraceProjector.project(null, null, null);
        assertThat(fromNulls.contextOrigin()).isNull();
        assertThat(fromNulls.toolCallsApplicable()).isFalse();
        assertThat(fromNulls.recorded()).isFalse();

        RunDetailResponse.Locating pinned = LocatingTraceProjector.project(
                ContextOrigin.PINNED, LocatingUsage.none(), List.of());
        assertThat(pinned.toolCallCount()).isNull();

        RunDetailResponse.Locating graph = LocatingTraceProjector.project(
                ContextOrigin.GRAPH_TOOLS,
                new LocatingUsage(2, 1, 8, 9, 17),
                List.of(
                        step(0, LocatingStepKind.FIND, "Foo", "find", "{\"query\":\"Foo\",\"hits\":2}"),
                        step(1, LocatingStepKind.EXPAND, "Foo", "expand",
                                "{\"entity\":\"Foo\",\"neighbors\":1,\"edgeKinds\":[\"CALLS\"],\"confidences\":[\"HIGH\"]}"),
                        step(2, LocatingStepKind.LIST, "src", "list", "{\"path\":\"src\",\"entries\":3}"),
                        step(
                                3,
                                LocatingStepKind.SUBMIT,
                                "src/Main.java",
                                "submit",
                                "{\"accepted\":true,\"paths\":1,\"submitted_not_read\":0,\"read_not_submitted\":0}"),
                        step(
                                4,
                                LocatingStepKind.BUDGET_WARNING,
                                ".",
                                "CALLS",
                                "{\"used\":30,\"maxCalls\":35}"),
                        step(5, LocatingStepKind.GRAPH_BUILD, "graph", "GRAPH_BUILD",
                                "{\"durationMs\":9,\"cacheHit\":true}"),
                        step(6, LocatingStepKind.GRAPH_BUILD, "graph", "GRAPH_BUILD", "{\"cacheHit\":false}"),
                        step(7, LocatingStepKind.SEARCH, "q", "search", "not-json"),
                        step(
                                8,
                                LocatingStepKind.READ,
                                "ok.java",
                                "read",
                                "{\"pathGlob\":\"/tmp/secret/nope\",\"truncated\":true}")));

        assertThat(graph.toolCallsApplicable()).isTrue();
        assertThat(graph.toolCallCount()).isEqualTo(5);
        assertThat(graph.usageStatus()).isEqualTo(RecordedUsageStatus.PARTIALLY_RECORDED.name());
        assertThat(graph.inputTokens()).isEqualTo(8L);
        assertThat(graph.graphBuild().cacheHit()).isTrue();
        assertThat(graph.graphBuild().durationMs()).isEqualTo(9L);
        assertThat(graph.budgetEvents()).extracting(RunDetailResponse.BudgetEvent::kind)
                .contains("BUDGET_WARNING");
        assertThat(graph.selectedPaths()).contains("src/Main.java");
        assertThat(graph.steps()).noneMatch(s -> "READ".equals(s.kind()) && "ok.java".equals(s.subject()));
        assertThat(graph.steps().stream().anyMatch(s -> "FIND".equals(s.kind()))).isTrue();

        assertThat(LocatingTraceProjector.relativize(null)).contains("");
        assertThat(LocatingTraceProjector.relativize("  ")).contains("  ");
        assertThat(LocatingTraceProjector.relativize("C:/repo/src/A.java")).contains("src/A.java");
        assertThat(LocatingTraceProjector.relativize("C:\\repo\\src\\A.java")).contains("src/A.java");
        assertThat(LocatingTraceProjector.relativize("C:/secret")).isEmpty();

        RunDetailResponse.Locating extras = LocatingTraceProjector.project(
                ContextOrigin.TEXT_TOOLS,
                recordedUsage(1),
                List.of(
                        step(0, LocatingStepKind.UNKNOWN_TOOL, "tool", "unknown",
                                "{\"tool\":\"x\",\"edgeKinds\":[\"CALLS\",1,null],\"confidences\":[],\"rejected\":true}"),
                        step(1, LocatingStepKind.SUBMIT, ".", "submit", "{\"accepted\":false}"),
                        step(0, LocatingStepKind.BUDGET_EXHAUSTED, ".", "CLOCK", "{}")));
        assertThat(extras.toolCallCount()).isEqualTo(1);
        assertThat(extras.selectedPaths()).isEmpty();
        assertThat(extras.steps().getFirst().detail()).containsEntry("rejected", true);
        assertThat(extras.steps().getFirst().detail()).containsEntry("edgeKinds", List.of("CALLS"));
        assertThat(extras.steps().getFirst().detail()).doesNotContainKey("confidences");
        assertThat(extras.budgetEvents().getFirst().limit()).isEqualTo("CLOCK");
        assertThat(extras.budgetEvents().getFirst().used()).isNull();
        assertThat(extras.budgetEvents().getFirst().maxCalls()).isNull();
    }

    @Test
    void existingDetailRecordComponentsRemain() {
        assertThat(Arrays.stream(RunDetailResponse.class.getRecordComponents()).map(RecordComponent::getName)
                        .toList())
                .contains(
                        "runId",
                        "mode",
                        "runPurpose",
                        "state",
                        "caseId",
                        "createdAt",
                        "updatedAt",
                        "completedAt",
                        "input",
                        "executionPolicy",
                        "generation",
                        "candidate",
                        "result",
                        "attempts")
                .contains("locating");
    }

    private static LocatingUsage recordedUsage(int calls) {
        return new LocatingUsage(calls, calls, 10, 20, 30);
    }

    private static LocatingTraceStep step(
            int seq, LocatingStepKind kind, String subject, String reason, String detail) {
        return LocatingTraceStep.of(seq, kind, subject, reason, detail);
    }
}

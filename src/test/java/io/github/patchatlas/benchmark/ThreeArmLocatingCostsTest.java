package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingCost;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingTokenAccounting;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceOutcome;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.LocatingUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThreeArmLocatingCostsTest {

    @Test
    void heuristicSelectionsAreNotToolCallsAndTokensAreNone() {
        LocatingCost cost = ThreeArmLocatingCosts.from(
                ContextOrigin.HEURISTIC,
                LocatingUsage.none(),
                List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.SELECTION, "a.java", "match", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.EXCLUSION, "b.java", "test", "{}")));

        assertThat(cost.toolCallCount()).isZero();
        assertThat(cost.expandCount()).isZero();
        assertThat(cost.graphBuildDurationMs()).isEmpty();
        assertThat(cost.locatingTokens()).isEqualTo(LocatingTokenAccounting.NONE);
    }

    @Test
    void textToolLoopCountsSearchReadSubmitAndMarksTokensUnknownWhenUsageMissing() {
        LocatingCost cost = ThreeArmLocatingCosts.from(
                ContextOrigin.TEXT_TOOLS,
                LocatingUsage.none(),
                List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.SEARCH, "q", "search", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.READ, "a.java", "read", "{}"),
                        LocatingTraceStep.of(2, LocatingStepKind.SUBMIT, "submit", "submit", "{}"),
                        LocatingTraceStep.of(
                                3, LocatingStepKind.BUDGET_WARNING, "budget", "warn", "{\"used\":8}")));

        assertThat(cost.toolCallCount()).isEqualTo(3);
        assertThat(cost.expandCount()).isZero();
        assertThat(cost.locatingTokens()).isEqualTo(LocatingTokenAccounting.UNKNOWN);
    }

    @Test
    void graphBuildIsCostNotAToolCallAndExpandIsCounted() {
        LocatingCost cost = ThreeArmLocatingCosts.from(
                ContextOrigin.GRAPH_TOOLS,
                LocatingUsage.none(),
                List.of(
                        LocatingTraceStep.of(
                                0,
                                LocatingStepKind.GRAPH_BUILD,
                                "graph",
                                "build",
                                "{\"durationMs\":328,\"cacheHit\":false}"),
                        LocatingTraceStep.of(1, LocatingStepKind.FIND, "Foo", "find", "{}"),
                        LocatingTraceStep.of(2, LocatingStepKind.EXPAND, "Foo", "expand", "{}"),
                        LocatingTraceStep.of(3, LocatingStepKind.READ, "a.java", "read", "{}")));

        assertThat(cost.toolCallCount()).isEqualTo(3);
        assertThat(cost.expandCount()).isEqualTo(1);
        assertThat(cost.graphBuildDurationMs()).hasValue(328L);
        assertThat(cost.graphBuildCacheHit()).contains(false);
        assertThat(cost.locatingTokens()).isEqualTo(LocatingTokenAccounting.UNKNOWN);
    }

    @Test
    void graphBuildErrorOutcomeStillParsesDuration() {
        LocatingCost cost = ThreeArmLocatingCosts.from(
                ContextOrigin.GRAPH_TOOLS,
                LocatingUsage.none(),
                List.of(new LocatingTraceStep(
                        java.util.UUID.randomUUID(),
                        0,
                        LocatingStepKind.GRAPH_BUILD,
                        LocatingTraceOutcome.ERROR,
                        "graph",
                        "failed",
                        "{\"durationMs\":12,\"cacheHit\":true}")));

        assertThat(cost.graphBuildDurationMs()).hasValue(12L);
        assertThat(cost.graphBuildCacheHit()).contains(true);
        assertThat(cost.toolCallCount()).isZero();
    }
}

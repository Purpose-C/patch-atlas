package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingCost;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingTokenAccounting;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.LocatingUsage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Locating cost from traces: tool-loop steps, expand count, graph-build, token accounting. */
final class ThreeArmLocatingCosts {

    private static final Set<LocatingStepKind> TOOL_CALLS = Set.of(
            LocatingStepKind.SEARCH,
            LocatingStepKind.LIST,
            LocatingStepKind.READ,
            LocatingStepKind.SUBMIT,
            LocatingStepKind.FIND,
            LocatingStepKind.EXPAND);

    private ThreeArmLocatingCosts() {}

    static LocatingCost from(ContextOrigin origin, LocatingUsage usage, List<LocatingTraceStep> steps) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(usage, "usage");
        List<LocatingTraceStep> copy = List.copyOf(Objects.requireNonNull(steps, "steps"));
        int toolCalls = 0;
        int expand = 0;
        OptionalLong buildMs = OptionalLong.empty();
        Optional<Boolean> cacheHit = Optional.empty();
        long buildTotal = 0;
        int buildCount = 0;
        boolean anyCacheHit = false;
        for (LocatingTraceStep step : copy) {
            if (TOOL_CALLS.contains(step.kind())) {
                toolCalls++;
            }
            if (step.kind() == LocatingStepKind.EXPAND) {
                expand++;
            }
            if (step.kind() == LocatingStepKind.GRAPH_BUILD) {
                GraphBuild parsed = parseGraphBuild(step.detailJson());
                buildTotal += parsed.durationMs;
                buildCount++;
                anyCacheHit |= parsed.cacheHit;
            }
        }
        if (buildCount > 0) {
            buildMs = OptionalLong.of(buildTotal);
            cacheHit = Optional.of(anyCacheHit);
        }
        return new LocatingCost(toolCalls, expand, buildMs, cacheHit, tokens(origin, usage, toolCalls));
    }

    private static LocatingTokenAccounting tokens(
            ContextOrigin origin, LocatingUsage usage, int toolCalls) {
        if (origin == ContextOrigin.HEURISTIC) {
            return LocatingTokenAccounting.NONE;
        }
        if (usage.unknown() || (usage.callCount() == 0 && toolCalls > 0)) {
            return LocatingTokenAccounting.UNKNOWN;
        }
        if (usage.callCount() == 0) {
            return LocatingTokenAccounting.NONE;
        }
        return LocatingTokenAccounting.RECORDED;
    }

    private static GraphBuild parseGraphBuild(String detailJson) {
        try {
            JsonNode node = JsonMapper.shared().readTree(detailJson == null || detailJson.isBlank() ? "{}" : detailJson);
            JsonNode duration = node.get("durationMs");
            JsonNode cache = node.get("cacheHit");
            long ms = duration != null && duration.isNumber() ? duration.asLong() : 0L;
            boolean hit = cache != null && cache.isBoolean() && cache.asBoolean();
            return new GraphBuild(ms, hit);
        } catch (RuntimeException ex) {
            return new GraphBuild(0L, false);
        }
    }

    private record GraphBuild(long durationMs, boolean cacheHit) {}
}

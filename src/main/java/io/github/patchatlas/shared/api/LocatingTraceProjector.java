package io.github.patchatlas.shared.api;

import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.LocatingUsage;
import io.github.patchatlas.run.RecordedUsageStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-side projection of locating_trace. Does not change locating or analysis. */
final class LocatingTraceProjector {

    static final int STEP_LIMIT = 200;

    private static final Set<LocatingStepKind> TOOL_CALLS = Set.of(
            LocatingStepKind.SEARCH,
            LocatingStepKind.LIST,
            LocatingStepKind.READ,
            LocatingStepKind.SUBMIT,
            LocatingStepKind.FIND,
            LocatingStepKind.EXPAND);

    private static final Set<String> PATH_KEYS = Set.of("path", "pathGlob");

    private LocatingTraceProjector() {}

    static RunDetailResponse.Locating project(
            ContextOrigin origin, LocatingUsage usage, List<LocatingTraceStep> steps) {
        LocatingUsage safeUsage = usage == null ? LocatingUsage.none() : usage;
        List<LocatingTraceStep> source = steps == null ? List.of() : steps;
        List<ProjectedStep> kept = new ArrayList<>();
        for (LocatingTraceStep step : source) {
            Optional<ProjectedStep> projected = projectStep(step);
            projected.ifPresent(kept::add);
        }
        kept.sort((a, b) -> Integer.compare(a.seq(), b.seq()));

        boolean applicable =
                origin == ContextOrigin.TEXT_TOOLS || origin == ContextOrigin.GRAPH_TOOLS;
        int toolCalls = 0;
        int errors = 0;
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        List<RunDetailResponse.BudgetEvent> budgetEvents = new ArrayList<>();
        RunDetailResponse.GraphBuild graphBuild = null;
        List<String> selectedPaths = new ArrayList<>();
        for (ProjectedStep step : kept) {
            kindCounts.merge(step.kind(), 1, Integer::sum);
            if (TOOL_CALLS.contains(LocatingStepKind.valueOf(step.kind()))) {
                toolCalls++;
            }
            if ("ERROR".equals(step.outcome())) {
                errors++;
            }
            if ("BUDGET_WARNING".equals(step.kind()) || "BUDGET_EXHAUSTED".equals(step.kind())) {
                budgetEvents.add(budgetEvent(step));
            }
            if ("GRAPH_BUILD".equals(step.kind()) && graphBuild == null) {
                graphBuild = graphBuild(step.detail());
            }
            if ("SELECTION".equals(step.kind()) || "SUBMIT".equals(step.kind())) {
                if (!step.subject().isBlank() && !".".equals(step.subject())) {
                    selectedPaths.add(step.subject());
                }
            }
        }

        boolean truncated = kept.size() > STEP_LIMIT;
        List<ProjectedStep> visible =
                truncated ? kept.subList(0, STEP_LIMIT) : kept;
        RecordedUsageStatus usageStatus =
                RecordedUsageStatus.from(safeUsage.usageRecordCount(), safeUsage.callCount());
        boolean showTokens = usageStatus == RecordedUsageStatus.PARTIALLY_RECORDED
                || usageStatus == RecordedUsageStatus.RECORDED_FOR_ALL_ATTEMPTS;

        return new RunDetailResponse.Locating(
                origin == null ? null : origin.name(),
                !kept.isEmpty(),
                applicable,
                applicable ? toolCalls : null,
                kindCounts,
                errors,
                usageStatus.name(),
                showTokens ? safeUsage.inputTokens() : null,
                showTokens ? safeUsage.outputTokens() : null,
                showTokens ? safeUsage.totalTokens() : null,
                budgetEvents,
                graphBuild,
                visible.stream()
                        .map(s -> new RunDetailResponse.LocatingStep(
                                s.seq(), s.kind(), s.subject(), s.reason(), s.outcome(), s.detail()))
                        .toList(),
                List.copyOf(selectedPaths),
                truncated,
                STEP_LIMIT);
    }

    private static Optional<ProjectedStep> projectStep(LocatingTraceStep step) {
        Optional<String> subject = relativize(step.subject());
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> detail = projectDetail(step.detailJson());
        if (detail.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectedStep(
                step.seq(),
                step.kind().name(),
                subject.get(),
                step.reason(),
                step.outcome().name(),
                detail.get()));
    }

    private static Optional<Map<String, Object>> projectDetail(String raw) {
        JsonNode node = parse(raw);
        Map<String, Object> detail = new LinkedHashMap<>();
        putText(detail, node, "pattern");
        putText(detail, node, "pathGlob");
        putText(detail, node, "path");
        putText(detail, node, "query");
        putText(detail, node, "entity");
        putText(detail, node, "errorType");
        putText(detail, node, "message");
        putText(detail, node, "rule");
        putText(detail, node, "reason");
        putText(detail, node, "tool");
        putText(detail, node, "limit");
        putInt(detail, node, "hits");
        putInt(detail, node, "entries");
        putInt(detail, node, "startLine");
        putInt(detail, node, "lines");
        putInt(detail, node, "bytes");
        putInt(detail, node, "paths");
        putInt(detail, node, "submitted_not_read", "submittedNotRead");
        putInt(detail, node, "read_not_submitted", "readNotSubmitted");
        putInt(detail, node, "used");
        putInt(detail, node, "maxCalls");
        putInt(detail, node, "neighbors");
        putInt(detail, node, "repeat_of", "repeatOf");
        putLong(detail, node, "durationMs");
        putBool(detail, node, "truncated");
        putBool(detail, node, "rejected");
        putBool(detail, node, "accepted");
        putBool(detail, node, "cacheHit");
        putStrings(detail, node, "edgeKinds");
        putStrings(detail, node, "confidences");
        for (String key : PATH_KEYS) {
            Object value = detail.get(key);
            if (value instanceof String path) {
                Optional<String> relative = relativize(path);
                if (relative.isEmpty()) {
                    return Optional.empty();
                }
                detail.put(key, relative.get());
            }
        }
        return Optional.of(Map.copyOf(detail));
    }

    static Optional<String> relativize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(raw == null ? "" : raw);
        }
        String normalized = raw.replace('\\', '/');
        if (!hostAbsolute(normalized)) {
            return Optional.of(normalized);
        }
        int src = normalized.indexOf("/src/");
        if (src >= 0) {
            return Optional.of(normalized.substring(src + 1));
        }
        return Optional.empty();
    }

    private static boolean hostAbsolute(String path) {
        if (path.startsWith("/")) {
            return true;
        }
        return path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && path.charAt(2) == '/';
    }

    private static RunDetailResponse.BudgetEvent budgetEvent(ProjectedStep step) {
        Object limit = step.detail().get("limit");
        Object used = step.detail().get("used");
        Object maxCalls = step.detail().get("maxCalls");
        return new RunDetailResponse.BudgetEvent(
                step.seq(),
                step.kind(),
                limit instanceof String text ? text : step.reason(),
                used instanceof Integer n ? n : null,
                maxCalls instanceof Integer n ? n : null);
    }

    private static RunDetailResponse.GraphBuild graphBuild(Map<String, Object> detail) {
        Object duration = detail.get("durationMs");
        Object cacheHit = detail.get("cacheHit");
        Long ms = duration instanceof Number n ? n.longValue() : null;
        Boolean hit = cacheHit instanceof Boolean b ? b : null;
        return new RunDetailResponse.GraphBuild(ms, hit);
    }

    private static JsonNode parse(String raw) {
        try {
            return JsonMapper.shared().readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (RuntimeException ex) {
            return JsonMapper.shared().createObjectNode();
        }
    }

    private static void putText(Map<String, Object> detail, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value != null && value.isString()) {
            detail.put(key, value.asString());
        }
    }

    private static void putInt(Map<String, Object> detail, JsonNode node, String key) {
        putInt(detail, node, key, key);
    }

    private static void putInt(Map<String, Object> detail, JsonNode node, String from, String to) {
        JsonNode value = node.get(from);
        if (value != null && value.isNumber()) {
            detail.put(to, value.asInt());
        }
    }

    private static void putLong(Map<String, Object> detail, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value != null && value.isNumber()) {
            detail.put(key, value.asLong());
        }
    }

    private static void putBool(Map<String, Object> detail, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value != null && value.isBoolean()) {
            detail.put(key, value.asBoolean());
        }
    }

    private static void putStrings(Map<String, Object> detail, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray()) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && item.isString()) {
                items.add(item.asString());
            }
        }
        if (!items.isEmpty()) {
            detail.put(key, List.copyOf(items));
        }
    }

    private record ProjectedStep(
            int seq, String kind, String subject, String reason, String outcome, Map<String, Object> detail) {}
}

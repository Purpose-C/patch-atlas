package io.github.patchatlas.analysis;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** locating_trace.detail 的诊断摘要；不含文件内容、工作区外路径或凭据。 */
final class LocatingTraceDetails {

    static final int MAX_BYTES = 8192;

    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(/Users/|/home/|[A-Za-z]:\\\\)[^\\s\"]+");

    private LocatingTraceDetails() {}

    static String error(RuntimeException ex) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("errorType", ex.getClass().getSimpleName());
        node.put("message", redact(ex.getMessage()));
        return clip(node);
    }

    static String submitRejected(String reason) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("rejected", true);
        node.put("rule", classifySubmit(reason));
        node.put("reason", redact(reason));
        return clip(node);
    }

    static String repeatOf(int seq) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("repeat_of", seq);
        return clip(node);
    }

    static String submitAccepted(int paths, int submittedNotRead, int readNotSubmitted) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("accepted", true);
        node.put("paths", paths);
        node.put("submitted_not_read", submittedNotRead);
        node.put("read_not_submitted", readNotSubmitted);
        return clip(node);
    }

    static String budgetWarning(int used, int maxCalls) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("used", used);
        node.put("maxCalls", maxCalls);
        return clip(node);
    }

    static String budget(boolean callsExhausted, int used, int maxCalls) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("limit", callsExhausted ? "CALLS" : "CLOCK");
        node.put("used", used);
        node.put("maxCalls", maxCalls);
        return clip(node);
    }

    static String fromToolResult(String name, String args, String body) {
        JsonNode argNode = parse(args);
        JsonNode bodyNode = parse(body);
        ObjectNode node = JsonMapper.shared().createObjectNode();
        switch (name) {
            case LocalizationToolCallingManager.SEARCH -> {
                putText(node, "pattern", text(argNode, "pattern"));
                String glob = text(argNode, "pathGlob");
                if (glob != null && !glob.isBlank()) {
                    node.put("pathGlob", glob);
                }
                node.put("hits", size(bodyNode, "hits"));
                putTruncated(node, bodyNode);
            }
            case LocalizationToolCallingManager.LIST -> {
                putText(node, "path", text(argNode, "path"));
                node.put("entries", size(bodyNode, "names"));
                putTruncated(node, bodyNode);
            }
            case LocalizationToolCallingManager.READ -> {
                putText(node, "path", text(argNode, "path"));
                if (bodyNode.get("startLine") != null && bodyNode.get("startLine").isNumber()) {
                    node.put("startLine", bodyNode.get("startLine").asInt());
                }
                node.put("lines", size(bodyNode, "lines"));
                node.put("bytes", utf8Size(bodyNode.get("lines")));
                putTruncated(node, bodyNode);
            }
            case GraphDiscoveryTools.FIND -> {
                putText(node, "query", text(argNode, "query"));
                node.put("hits", size(bodyNode, "entities"));
                putTruncated(node, bodyNode);
            }
            case GraphDiscoveryTools.EXPAND -> {
                putText(node, "entity", text(argNode, "entity"));
                node.put("neighbors", size(bodyNode, "neighbors"));
                putExpandEdges(node, bodyNode);
                putTruncated(node, bodyNode);
            }
            default -> node.put("tool", name);
        }
        return clip(node);
    }

    static String graphBuild(long durationMs, boolean cacheHit) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("durationMs", durationMs);
        node.put("cacheHit", cacheHit);
        return clip(node);
    }

    static String clip(ObjectNode node) {
        String raw = JsonMapper.shared().writeValueAsString(node);
        if (utf8(raw) <= MAX_BYTES) {
            return raw;
        }
        node.put("truncated", true);
        shrink(node, "pattern");
        shrink(node, "message");
        shrink(node, "reason");
        shrink(node, "pathGlob");
        shrink(node, "path");
        raw = JsonMapper.shared().writeValueAsString(node);
        if (utf8(raw) <= MAX_BYTES) {
            return raw;
        }
        return "{\"truncated\":true}";
    }

    private static void shrink(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return;
        }
        String text = value.asString();
        if (text.length() > 64) {
            node.put(field, text.substring(0, 64));
        }
    }

    private static String classifySubmit(String reason) {
        if (reason == null) {
            return "rejected";
        }
        if (reason.contains("does not exist")) {
            return "path does not exist";
        }
        if (reason.contains("at most 12")) {
            return "at most 12 paths";
        }
        if (reason.contains("256")) {
            return "source snapshots exceed 256 KiB";
        }
        if (reason.contains("64")) {
            return "file exceeds 64 KiB";
        }
        if (reason.contains("path rejected")) {
            return "path rejected";
        }
        if (reason.contains("array")) {
            return "paths must be an array of strings";
        }
        return redact(reason);
    }

    private static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return ABSOLUTE_PATH.matcher(text).replaceAll("[path]");
    }

    private static void putExpandEdges(ObjectNode node, JsonNode body) {
        JsonNode neighbors = body.get("neighbors");
        if (neighbors == null || !neighbors.isArray()) {
            return;
        }
        var kinds = JsonMapper.shared().createArrayNode();
        var confidences = JsonMapper.shared().createArrayNode();
        for (JsonNode neighbor : neighbors) {
            JsonNode kind = neighbor.get("edgeKind");
            if (kind != null && kind.isString()) {
                kinds.add(kind.asString());
            }
            JsonNode confidence = neighbor.get("confidence");
            if (confidence != null && confidence.isString()) {
                confidences.add(confidence.asString());
            }
        }
        if (!kinds.isEmpty()) {
            node.set("edgeKinds", kinds);
        }
        if (!confidences.isEmpty()) {
            node.set("confidences", confidences);
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void putTruncated(ObjectNode node, JsonNode body) {
        JsonNode truncated = body.get("truncated");
        if (truncated != null && truncated.isBoolean()) {
            node.put("truncated", truncated.asBoolean());
        }
    }

    private static JsonNode parse(String raw) {
        try {
            return JsonMapper.shared().readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (RuntimeException ex) {
            return JsonMapper.shared().createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static int size(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isArray() ? value.size() : 0;
    }

    private static int utf8Size(JsonNode lines) {
        if (lines == null || !lines.isArray()) {
            return 0;
        }
        int bytes = 0;
        for (JsonNode line : lines) {
            if (line != null && line.isString()) {
                bytes += utf8(line.asString());
                bytes += 1;
            }
        }
        return bytes;
    }

    private static int utf8(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8).length;
    }
}

package io.github.patchatlas.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Conservative (tool, args) fingerprint for repeat detection. Shared by both scaffolds. */
final class LocatingCallKey {

    private static final JsonMapper JSON = JsonMapper.shared();

    private LocatingCallKey() {}

    static String of(String name, String args) {
        return name + ":" + JSON.writeValueAsString(normalize(parse(args)));
    }

    private static JsonNode parse(String args) {
        try {
            return JSON.readTree(args == null || args.isBlank() ? "{}" : args);
        } catch (RuntimeException ex) {
            return JSON.createObjectNode().put("_raw", args == null ? "" : args.trim());
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return JSON.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            names.sort(Comparator.naturalOrder());
            for (String field : names) {
                out.set(field, normalizeField(field, node.get(field)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode item : node) {
                out.add(normalize(item));
            }
            return out;
        }
        if (node.isString()) {
            return JSON.getNodeFactory().textNode(trim(node.asString()));
        }
        return node;
    }

    private static JsonNode normalizeField(String field, JsonNode value) {
        if (value != null && value.isString()) {
            String text = trim(value.asString());
            if (isPathField(field)) {
                text = normalizePath(text);
            }
            return JSON.getNodeFactory().textNode(text);
        }
        if (value != null && value.isArray() && isPathField(field)) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode item : value) {
                if (item != null && item.isString()) {
                    out.add(normalizePath(trim(item.asString())));
                } else {
                    out.add(normalize(item));
                }
            }
            return out;
        }
        return normalize(value);
    }

    private static boolean isPathField(String field) {
        return "path".equals(field) || "pathGlob".equals(field) || "paths".equals(field);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizePath(String path) {
        String text = path.replace('\\', '/');
        while (text.contains("//")) {
            text = text.replace("//", "/");
        }
        if (text.startsWith("./")) {
            text = text.substring(2);
        }
        return text;
    }
}

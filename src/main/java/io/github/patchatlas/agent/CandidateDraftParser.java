package io.github.patchatlas.agent;

import io.github.patchatlas.replay.TargetTest;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Candidate Draft envelope 严格解析：仅三字段 JSON 对象，拒绝 fence/尾随文本/额外字段。
 */
public final class CandidateDraftParser {

    public static final int MAX_RESPONSE_BYTES = 96 * 1024;

    private final JsonMapper mapper;

    public CandidateDraftParser() {
        this(JsonMapper.shared());
    }

    CandidateDraftParser(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public sealed interface ParseResult permits ParseResult.Ok, ParseResult.Invalid {
        record Ok(CandidateDraft draft) implements ParseResult {}

        record Invalid(String reason) implements ParseResult {}
    }

    public ParseResult parse(String rawResponse) {
        if (rawResponse == null) {
            return new ParseResult.Invalid("response is null");
        }
        if (rawResponse.indexOf('\0') >= 0) {
            return new ParseResult.Invalid("response contains NUL");
        }
        byte[] bytes = rawResponse.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
            return new ParseResult.Invalid("response size out of bounds");
        }
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```") || trimmed.contains("```")) {
            return new ParseResult.Invalid("markdown fence not allowed");
        }
        final JsonNode root;
        try {
            root = mapper.readTree(trimmed);
        } catch (RuntimeException ex) {
            return new ParseResult.Invalid("not json");
        }
        // 拒绝尾随：整段必须是单一 JSON 值（重新序列化后与规范化比较太松；用 token 流）
        if (!isSingleJsonValue(trimmed)) {
            return new ParseResult.Invalid("trailing or multiple json values");
        }
        if (root == null || !root.isObject()) {
            return new ParseResult.Invalid("root must be object");
        }
        Iterator<String> names = root.propertyNames().iterator();
        int count = 0;
        while (names.hasNext()) {
            names.next();
            count++;
        }
        if (count != 3) {
            return new ParseResult.Invalid("exactly three fields required");
        }
        if (!root.has("patchText") || !root.has("targetClass") || !root.has("targetMethod")) {
            return new ParseResult.Invalid("missing required fields");
        }
        for (String name : new String[] {"patchText", "targetClass", "targetMethod"}) {
            JsonNode n = root.get(name);
            if (n == null || !n.isString()) {
                return new ParseResult.Invalid(name + " must be string");
            }
            if (n.stringValue().isEmpty()) {
                return new ParseResult.Invalid(name + " must not be empty");
            }
        }
        try {
            CandidateDraft draft = new CandidateDraft(
                    root.get("patchText").stringValue(),
                    new TargetTest(
                            root.get("targetClass").stringValue(),
                            root.get("targetMethod").stringValue()));
            return new ParseResult.Ok(draft);
        } catch (IllegalArgumentException ex) {
            return new ParseResult.Invalid(ex.getMessage());
        }
    }

    private boolean isSingleJsonValue(String text) {
        try {
            var parser = mapper.createParser(text);
            if (parser.nextToken() == null) {
                return false;
            }
            mapper.readTree(parser);
            return parser.nextToken() == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}

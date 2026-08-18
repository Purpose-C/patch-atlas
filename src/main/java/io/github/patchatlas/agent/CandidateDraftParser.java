package io.github.patchatlas.agent;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Candidate Draft envelope 严格解析：仅单字段 {@code patch}，目标测试由补丁推导。
 *
 * <p>拒绝 fence、尾随文本、额外字段；推导失败不猜测目标。
 */
public final class CandidateDraftParser {

    public static final int MAX_RESPONSE_BYTES = 96 * 1024;
    static final String PATCH_FIELD = "patch";

    private final JsonMapper mapper;
    private final TargetTestDeriver deriver;

    public CandidateDraftParser() {
        this(JsonMapper.shared(), new TargetTestDeriver());
    }

    CandidateDraftParser(JsonMapper mapper) {
        this(mapper, new TargetTestDeriver());
    }

    CandidateDraftParser(JsonMapper mapper, TargetTestDeriver deriver) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.deriver = Objects.requireNonNull(deriver, "deriver");
    }

    public sealed interface ParseResult
            permits ParseResult.Ok, ParseResult.Invalid, ParseResult.Rejected {
        record Ok(CandidateDraft draft) implements ParseResult {}

        record Invalid(String reason) implements ParseResult {}

        record Rejected(PatchRejectionCategory category, String reason) implements ParseResult {}
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
        if (count != 1) {
            return new ParseResult.Invalid("exactly one field required");
        }
        if (!root.has(PATCH_FIELD)) {
            return new ParseResult.Invalid("missing required fields");
        }
        JsonNode patchNode = root.get(PATCH_FIELD);
        if (patchNode == null || !patchNode.isString()) {
            return new ParseResult.Invalid("patch must be string");
        }
        if (patchNode.stringValue().isEmpty()) {
            return new ParseResult.Invalid("patch must not be empty");
        }
        String patch = patchNode.stringValue();
        return switch (deriver.derive(patch)) {
            case TargetTestDeriver.Result.Derived derived -> {
                try {
                    yield new ParseResult.Ok(new CandidateDraft(patch, derived.targetTest()));
                } catch (IllegalArgumentException ex) {
                    yield new ParseResult.Invalid(ex.getMessage());
                }
            }
            case TargetTestDeriver.Result.Rejected rejected ->
                    new ParseResult.Rejected(rejected.category(), rejected.reason());
        };
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

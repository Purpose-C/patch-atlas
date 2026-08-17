package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code source_snapshots} JSONB 显式 codec：仅 path + content 叶子，拒绝多态类名。
 *
 * <p>schema version 由调用方传入（与 {@code verification_run.input_schema_version} 对齐）。
 */
public final class SourceSnapshotsCodec {

    /** 快照 JSON 形状版本；v1/v2 入参语义不同，叶子格式相同。 */
    public static final int SCHEMA_VERSION = 1;

    /** 新建 Run 写入的 input_schema_version。 */
    public static final int CURRENT_INPUT_SCHEMA_VERSION = 2;

    private final JsonMapper mapper;

    public SourceSnapshotsCodec() {
        this(JsonMapper.shared());
    }

    SourceSnapshotsCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public String encode(List<SourceSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.size() > GenerationInput.MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("at most 12 source snapshots");
        }
        ArrayNode array = mapper.createArrayNode();
        for (SourceSnapshot snapshot : snapshots) {
            ObjectNode node = mapper.createObjectNode();
            node.put("relativePath", snapshot.relativePath());
            node.put("content", snapshot.content());
            array.add(node);
        }
        return mapper.writeValueAsString(array);
    }

    public List<SourceSnapshot> decode(String json, int schemaVersion) {
        Objects.requireNonNull(json, "json");
        if (schemaVersion != SCHEMA_VERSION && schemaVersion != CURRENT_INPUT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported source_snapshots schema version: " + schemaVersion);
        }
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed source_snapshots json", ex);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("source_snapshots must be a json array");
        }
        if (root.size() > GenerationInput.MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("at most 12 source snapshots");
        }
        List<SourceSnapshot> result = new ArrayList<>(root.size());
        for (JsonNode element : root) {
            if (element == null || !element.isObject()) {
                throw new IllegalArgumentException("source snapshot must be an object");
            }
            JsonNode pathNode = element.get("relativePath");
            JsonNode contentNode = element.get("content");
            if (pathNode == null || !pathNode.isString()) {
                throw new IllegalArgumentException("source snapshot relativePath required");
            }
            if (contentNode == null || !contentNode.isString()) {
                throw new IllegalArgumentException("source snapshot content required");
            }
            // 领域构造器再次强制大小与路径边界
            result.add(new SourceSnapshot(pathNode.stringValue(), contentNode.stringValue()));
        }
        return List.copyOf(result);
    }
}

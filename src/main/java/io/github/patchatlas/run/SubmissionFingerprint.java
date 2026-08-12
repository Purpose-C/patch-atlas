package io.github.patchatlas.run;

import io.github.patchatlas.agent.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 对规范化后的 {@link RunSubmission} 计算 SHA-256。
 *
 * <p>使用确定性 JSON 编码 Source Snapshots，避免分隔符歧义。
 */
public final class SubmissionFingerprint {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private SubmissionFingerprint() {}

    public static String sha256Hex(RunSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        return sha256(canonicalize(submission));
    }

    static String canonicalize(RunSubmission s) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("mode", s.mode().name());
        putNullable(root, "caseId", s.caseId());
        root.put("repositoryUrl", s.repositoryUrl());
        putNullable(root, "license", s.license());
        putNullable(root, "issueUrl", s.issueUrl());
        root.put("issueTitle", s.issueTitle());
        root.put("issueBody", s.issueBody());
        root.put("buggyRevision", s.buggyRevision());
        putNullable(root, "fixedRevision", s.fixedRevision());
        root.put("modulePath", s.modulePath());
        root.put("javaVersion", s.javaVersion());
        root.put("networkMode", s.networkMode().name());
        ArrayNode snaps = root.putArray("sourceSnapshots");
        for (SourceSnapshot snap : s.sourceSnapshots()) {
            ObjectNode n = snaps.addObject();
            n.put("relativePath", snap.relativePath());
            n.put("content", snap.content());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void putNullable(ObjectNode root, String field, String value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}

package io.github.patchatlas.agent;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Generation Input 中的有界源码快照（仓库相对路径 + UTF-8 内容）。 */
public record SourceSnapshot(String relativePath, String content) {

    public static final int MAX_CONTENT_BYTES = 64 * 1024;

    public SourceSnapshot {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(content, "content");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (relativePath.contains("\\") || relativePath.contains("\0") || relativePath.startsWith("/")) {
            throw new IllegalArgumentException("relativePath must be a safe repository-relative path");
        }
        if (relativePath.contains("..")) {
            throw new IllegalArgumentException("relativePath must not contain path traversal");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("source snapshot exceeds 64 KiB");
        }
    }
}

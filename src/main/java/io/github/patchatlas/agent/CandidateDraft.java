package io.github.patchatlas.agent;

import io.github.patchatlas.replay.TargetTest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 未提交的候选草稿：严格三字段 envelope 解析后的领域类型。
 *
 * <p>仍须完整通过 Patch Gate 才能成为 Candidate Test Patch。
 */
public record CandidateDraft(String patchText, TargetTest targetTest) {

    public static final int MAX_PATCH_BYTES = 64 * 1024;

    public CandidateDraft {
        Objects.requireNonNull(patchText, "patchText");
        Objects.requireNonNull(targetTest, "targetTest");
        if (patchText.isEmpty()) {
            throw new IllegalArgumentException("patchText must not be empty");
        }
        if (patchText.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("patchText must not contain NUL");
        }
        int bytes = patchText.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PATCH_BYTES) {
            throw new IllegalArgumentException("patchText exceeds 64 KiB");
        }
    }
}

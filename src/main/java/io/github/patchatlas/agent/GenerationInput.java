package io.github.patchatlas.agent;

import io.github.patchatlas.repository.CaseManifest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 生成器可见输入。类型表面只有 {@link CaseManifest.GeneratorContext}，不含 Oracle Data。
 */
public record GenerationInput(
        CaseManifest.GeneratorContext generatorContext,
        String issueTitle,
        String issueBody,
        List<SourceSnapshot> sourceSnapshots) {

    public static final int MAX_ISSUE_CHARS = 32 * 1024;
    public static final int MAX_SNAPSHOTS = 12;
    public static final int MAX_TOTAL_SOURCE_BYTES = 256 * 1024;

    public GenerationInput {
        Objects.requireNonNull(generatorContext, "generatorContext");
        Objects.requireNonNull(issueTitle, "issueTitle");
        Objects.requireNonNull(issueBody, "issueBody");
        sourceSnapshots = List.copyOf(Objects.requireNonNull(sourceSnapshots, "sourceSnapshots"));
        if (issueTitle.length() + issueBody.length() > MAX_ISSUE_CHARS) {
            throw new IllegalArgumentException("issue title and body exceed 32 KiB");
        }
        if (sourceSnapshots.size() > MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("at most 12 source snapshots");
        }
        int total = 0;
        for (SourceSnapshot snapshot : sourceSnapshots) {
            total += snapshot.content().getBytes(StandardCharsets.UTF_8).length;
            if (total > MAX_TOTAL_SOURCE_BYTES) {
                throw new IllegalArgumentException("source snapshots exceed 256 KiB total");
            }
        }
    }
}

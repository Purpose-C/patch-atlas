package io.github.patchatlas.run;

import java.util.Objects;

/** 未得到 ReplayResult 前的终态失败摘要。 */
public record RunFailure(FailureStage stage, FailureCategory category, String summary) {

    public static final int MAX_SUMMARY_CHARS = 512;

    public RunFailure {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }
        if (category == FailureCategory.RECOVERY_EXHAUSTED && stage != FailureStage.RECOVERY) {
            throw new IllegalArgumentException("RECOVERY_EXHAUSTED requires RECOVERY stage");
        }
    }
}

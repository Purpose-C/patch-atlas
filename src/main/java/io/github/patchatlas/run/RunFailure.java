package io.github.patchatlas.run;

import java.util.Objects;
import java.util.Set;

/** 未得到 ReplayResult 前的终态失败摘要。 */
public record RunFailure(FailureStage stage, FailureCategory category, String summary) {

    public static final int MAX_SUMMARY_CHARS = 512;

    private static final Set<FailureCategory> GENERATION_CATEGORIES = Set.of(
            FailureCategory.GENERATION_FAILURE,
            FailureCategory.GENERATION_EXHAUSTED,
            FailureCategory.MODEL_CONFIGURATION_ERROR,
            FailureCategory.MODEL_AUTHENTICATION_ERROR,
            FailureCategory.MODEL_UNAVAILABLE,
            FailureCategory.MODEL_REFUSED);

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
        requireStageCategory(stage, category);
    }

    public static boolean legalPair(FailureStage stage, FailureCategory category) {
        return switch (stage) {
            case LOCATING -> category == FailureCategory.LOCATING_NO_CONTEXT
                    || category == FailureCategory.LOCATING_NOT_CONFIGURED
                    || category == FailureCategory.LOCATING_TOOL_PROTOCOL_ERROR;
            case GENERATION -> GENERATION_CATEGORIES.contains(category);
            case PATCH_GATE -> category == FailureCategory.PATCH_REJECTED
                    || category == FailureCategory.WORKSPACE_UNSAFE;
            case WORKSPACE -> category == FailureCategory.WORKSPACE_UNSAFE
                    || category == FailureCategory.WORKSPACE_ERROR;
            case REPLAY -> category == FailureCategory.REPLAY_SYSTEM_ERROR;
            case RECOVERY -> category == FailureCategory.RECOVERY_EXHAUSTED;
        };
    }

    static void requireStageCategory(FailureStage stage, FailureCategory category) {
        if (!legalPair(stage, category)) {
            throw new IllegalArgumentException(
                    "illegal failure pair " + stage + " / " + category);
        }
    }
}

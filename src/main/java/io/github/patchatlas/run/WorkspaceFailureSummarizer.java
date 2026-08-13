package io.github.patchatlas.run;

import java.util.Objects;

/**
 * 工作区兜底失败：执行异常归为 {@link FailureCategory#WORKSPACE_ERROR}。
 * 仅 {@code DIAGNOSTIC} 回显异常 message；其余用途只保留类型名。
 */
final class WorkspaceFailureSummarizer {

    private static final String DEFAULT_PREFIX = "workspace: ";

    private WorkspaceFailureSummarizer() {}

    static RunFailure failure(Exception ex, RunPurpose purpose) {
        return failure(ex, purpose, DEFAULT_PREFIX);
    }

    static RunFailure failure(Exception ex, RunPurpose purpose, String prefix) {
        Objects.requireNonNull(ex, "ex");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(prefix, "prefix");
        return new RunFailure(FailureStage.WORKSPACE, FailureCategory.WORKSPACE_ERROR, summary(ex, purpose, prefix));
    }

    static String summary(Exception ex, RunPurpose purpose, String prefix) {
        Objects.requireNonNull(ex, "ex");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(prefix, "prefix");
        String base = prefix + ex.getClass().getSimpleName();
        if (purpose != RunPurpose.DIAGNOSTIC) {
            return bound(base);
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return bound(base);
        }
        return bound(base + ": " + message);
    }

    private static String bound(String summary) {
        if (summary.length() <= RunFailure.MAX_SUMMARY_CHARS) {
            return summary;
        }
        return summary.substring(0, RunFailure.MAX_SUMMARY_CHARS);
    }
}

package io.github.patchatlas.agent;

import java.util.Objects;

/**
 * 截断判定：finish_reason=length 时拒绝，且必须发生在补丁解析之前。
 */
public final class ResponseTruncationGuard {

    static final String LENGTH = "length";
    static final String REASON = "响应被截断";

    private ResponseTruncationGuard() {}

    public static boolean truncated(CompletionDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        return LENGTH.equals(diagnostics.finishReason());
    }

    public static PatchPreparationResult.RejectedCandidate rejection() {
        return new PatchPreparationResult.RejectedCandidate(
                PatchRejectionCategory.RESPONSE_TRUNCATED, REASON);
    }
}

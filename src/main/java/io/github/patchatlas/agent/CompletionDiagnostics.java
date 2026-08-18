package io.github.patchatlas.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 单次模型 completion 的诊断字段：finish_reason 与 completion 明细。
 *
 * <p>finish_reason 是判定输入：是否按截断拒绝、是否允许按正文重算 hunk 计数，都读这一字段。
 * 取值必须是短安全 token；取不到则为 {@code unknown} 字面量，入库时同样不得用 NULL 假装没发生。
 */
public record CompletionDiagnostics(String finishReason, String reasoningTokens, String textTokens) {

    public static final String UNKNOWN = "unknown";

    private static final Pattern FINISH_REASON = Pattern.compile("[a-z0-9_-]{1,32}");

    public CompletionDiagnostics {
        finishReason = sanitizeFinish(finishReason);
        reasoningTokens = sanitizeCount(reasoningTokens);
        textTokens = sanitizeCount(textTokens);
    }

    public static CompletionDiagnostics unknown() {
        return new CompletionDiagnostics(UNKNOWN, UNKNOWN, UNKNOWN);
    }

    public static CompletionDiagnostics of(String finishReason, String reasoningTokens, String textTokens) {
        return new CompletionDiagnostics(finishReason, reasoningTokens, textTokens);
    }

    /**
     * 是否拿到「响应已完成」的正面证据。仅此时允许按正文重算 hunk 计数。
     * {@code unknown} 与截断（{@code length}）都不是完成。
     */
    public boolean indicatesComplete() {
        return "stop".equals(finishReason) || "tool_calls".equals(finishReason);
    }

    private static String sanitizeFinish(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!FINISH_REASON.matcher(s).matches()) {
            return UNKNOWN;
        }
        return s;
    }

    private static String sanitizeCount(String raw) {
        if (raw == null || raw.isBlank() || UNKNOWN.equalsIgnoreCase(raw.trim())) {
            return UNKNOWN;
        }
        try {
            long n = Long.parseLong(raw.trim());
            if (n < 0) {
                return UNKNOWN;
            }
            return Long.toString(n);
        } catch (NumberFormatException ignored) {
            return UNKNOWN;
        }
    }
}

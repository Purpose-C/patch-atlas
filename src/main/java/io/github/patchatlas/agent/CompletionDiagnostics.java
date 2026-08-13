package io.github.patchatlas.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 单次模型 completion 的诊断字段：finish_reason 与 completion 明细。
 *
 * <p>只用于结构化日志，不是账本事实；取值必须是短安全 token，取不到则为 {@code unknown}。
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

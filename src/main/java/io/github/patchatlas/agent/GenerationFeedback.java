package io.github.patchatlas.agent;

import java.util.Objects;
import java.util.regex.Pattern;

/** 有界 Buggy 侧反馈；不得含 Fixed/密钥/绝对路径/完整 patch。 */
public record GenerationFeedback(GenerationFeedbackCategory category, String summary) {

    public static final int MAX_SUMMARY_CHARS = 512;

    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?:/|\\\\)(?:Users|home|tmp|var|opt)/\\S+");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(api[_-]?key|password|secret|token)\\s*[=:]\\s*\\S+");
    private static final Pattern URL_WITH_USERINFO = Pattern.compile("https?://[^\\s/]+:[^\\s/]+@");

    public GenerationFeedback {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        summary = sanitize(summary);
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }
    }

    static String sanitize(String raw) {
        String s = raw.replace('\0', ' ');
        s = ABSOLUTE_PATH.matcher(s).replaceAll("[path]");
        s = CREDENTIAL.matcher(s).replaceAll("$1=[redacted]");
        s = URL_WITH_USERINFO.matcher(s).replaceAll("https://[redacted]@");
        return s.trim();
    }
}

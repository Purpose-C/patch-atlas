package io.github.patchatlas.agent;

/** 供应商返回的 token 用量；未知时为空 Optional，不伪造。 */
public record ModelUsage(long inputTokens, long outputTokens, long totalTokens) {

    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public static ModelUsage zeros() {
        return new ModelUsage(0, 0, 0);
    }
}

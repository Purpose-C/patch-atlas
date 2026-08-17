package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import java.util.Optional;

/** 定位阶段模型用量：与 Generation Attempt 分账。 */
public record LocatingUsage(
        int callCount, Integer usageRecordCount, long inputTokens, long outputTokens, long totalTokens) {

    public LocatingUsage {
        if (callCount < 0) {
            throw new IllegalArgumentException("callCount must not be negative");
        }
        if (usageRecordCount != null && usageRecordCount < 0) {
            throw new IllegalArgumentException("usageRecordCount must not be negative");
        }
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public static LocatingUsage none() {
        return new LocatingUsage(0, 0, 0, 0, 0);
    }

    public boolean unknown() {
        if (callCount == 0) {
            return false;
        }
        if (usageRecordCount == null) {
            return true;
        }
        return usageRecordCount < callCount;
    }

    public Optional<ModelUsage> reportedTokens() {
        if (unknown() || callCount == 0) {
            return Optional.empty();
        }
        return Optional.of(new ModelUsage(inputTokens, outputTokens, totalTokens));
    }

    public String reportLabel() {
        if (callCount == 0) {
            return "none";
        }
        if (unknown()) {
            return "unknown";
        }
        return inputTokens + "/" + outputTokens + "/" + totalTokens;
    }
}

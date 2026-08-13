package io.github.patchatlas.observability;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RecordedUsageStatus;
import java.util.HashMap;
import java.util.Map;

final class ScriptedRunAggregateReader implements RunAggregateReader {

    private final Map<String, Long> values = new HashMap<>();
    private boolean failReads;

    void completed(VerificationMode mode, ReplayVerdict verdict, long count) {
        values.put("completed:" + mode + ":" + verdict, count);
    }

    void failed(VerificationMode mode, FailureStage stage, FailureCategory category, long count) {
        values.put("failed:" + mode + ":" + stage + ":" + category, count);
    }

    void attempts(String provider, long count) {
        values.put("attempts:" + provider, count);
    }

    void usageRecords(String provider, long count) {
        values.put("records:" + provider, count);
    }

    void usageRuns(String provider, RecordedUsageStatus status, long count) {
        values.put("usageRuns:" + provider + ":" + status, count);
    }

    void tokens(String provider, String type, long count) {
        values.put("tokens:" + provider + ":" + type, count);
    }

    void tokensForModelSnapshot(String provider, String model, long input, long output, long total) {
        values.put("tokensSnapshot:" + provider + ":" + model + ":input", input);
        values.put("tokensSnapshot:" + provider + ":" + model + ":output", output);
        values.put("tokensSnapshot:" + provider + ":" + model + ":total", total);
    }

    void failReads() {
        this.failReads = true;
    }

    @Override
    public long completedRuns(VerificationMode mode, ReplayVerdict verdict) {
        return get("completed:" + mode + ":" + verdict);
    }

    @Override
    public long failedRuns(VerificationMode mode, FailureStage stage, FailureCategory category) {
        return get("failed:" + mode + ":" + stage + ":" + category);
    }

    @Override
    public long generationAttempts(String provider) {
        return get("attempts:" + provider);
    }

    @Override
    public long usageRecords(String provider) {
        return get("records:" + provider);
    }

    @Override
    public long usageRuns(String provider, RecordedUsageStatus status) {
        return get("usageRuns:" + provider + ":" + status);
    }

    @Override
    public long tokens(String provider, String type) {
        return get("tokens:" + provider + ":" + type);
    }

    @Override
    public TokenSnapshot tokensForModelSnapshot(String provider, String model) {
        return new TokenSnapshot(
                get("tokensSnapshot:" + provider + ":" + model + ":input"),
                get("tokensSnapshot:" + provider + ":" + model + ":output"),
                get("tokensSnapshot:" + provider + ":" + model + ":total"));
    }

    private long get(String key) {
        if (failReads) {
            throw new IllegalStateException("aggregate query failed");
        }
        return values.getOrDefault(key, 0L);
    }
}

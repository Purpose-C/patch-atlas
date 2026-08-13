package io.github.patchatlas.observability;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RecordedUsageStatus;

/** 从已持久化 Verification Run 事实只读派生 Run Aggregate Metrics。 */
public interface RunAggregateReader {

    long completedRuns(VerificationMode mode, ReplayVerdict verdict);

    long failedRuns(VerificationMode mode, FailureStage stage, FailureCategory category);

    long generationAttempts(String provider);

    long usageRecords(String provider);

    long usageRuns(String provider, RecordedUsageStatus status);

    long tokens(String provider, String type);

    /** 单条 SQL 返回指定 provider/model 的 input、output、total token，保证同一数据库快照。 */
    TokenSnapshot tokensForModelSnapshot(String provider, String model);

    record TokenSnapshot(long input, long output, long total) {}
}

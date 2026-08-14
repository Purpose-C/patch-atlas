package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayResult;
import java.util.Objects;

/**
 * Formal Replay 的持久化 seam：打开轮次、完成、失败。
 *
 * <p>生产由 {@link LeaseHeartbeat} 串行化；测试使用内存实现。协调器不得持有 Run Store。
 */
public interface ReplayRunSession extends AutoCloseable {

    record Opened(
            ClaimedRun claim, ReplayWorkspaceProjection projection, RunPurpose purpose) {
        public Opened {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    Opened openRound();

    RunDetails complete(ReplayResult result);

    RunDetails fail(RunFailure failure);

    @Override
    default void close() {}
}

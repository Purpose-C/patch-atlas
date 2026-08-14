package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayResult;
import java.time.Duration;
import java.util.Objects;

/**
 * 生产 {@link ReplayRunSession}：所有写均在 {@link LeaseHeartbeat} 锁内完成。
 */
public final class LeaseHeartbeatReplayRunSession implements ReplayRunSession {

    private final LeaseHeartbeat heartbeat;
    private final PostgresRunStore store;
    private final RunPurpose purpose;
    private final boolean closeHeartbeat;

    public LeaseHeartbeatReplayRunSession(
            LeaseHeartbeat heartbeat, PostgresRunStore store, RunPurpose purpose) {
        this(heartbeat, store, purpose, false);
    }

    private LeaseHeartbeatReplayRunSession(
            LeaseHeartbeat heartbeat,
            PostgresRunStore store,
            RunPurpose purpose,
            boolean closeHeartbeat) {
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.store = Objects.requireNonNull(store, "store");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.closeHeartbeat = closeHeartbeat;
    }

    /**
     * 打开一段独立的 Replay 租约心跳，并在 {@link #close()} 时停止。
     *
     * <p>Worker 生成阶段结束后会关掉上一段心跳；校准入口没有 Worker，用此工厂补齐。
     */
    public static LeaseHeartbeatReplayRunSession open(
            PostgresRunStore store,
            ClaimedRun claimed,
            String owner,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(owner, "owner");
        RunPurpose purpose = store.findRunDetail(claimed.runId())
                .orElseThrow(() -> new IllegalStateException("run disappeared: " + claimed.runId()))
                .purpose();
        LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval);
        return new LeaseHeartbeatReplayRunSession(beat, store, purpose, true);
    }

    @Override
    public Opened openRound() {
        ClaimedRun opened = heartbeat.openReplayRound();
        RunEvents.replayStarted(opened.runId(), opened.replayRound());
        ReplayWorkspaceProjection projection = store.loadReplayWorkspaceProjection(opened.runId());
        return new Opened(opened, projection, purpose);
    }

    @Override
    public RunDetails complete(ReplayResult result) {
        RunDetails completed = heartbeat.complete(result);
        completed.verdict()
                .ifPresent(verdict ->
                        RunEvents.runCompleted(completed.runId(), completed.mode(), verdict));
        return completed;
    }

    @Override
    public RunDetails fail(RunFailure failure) {
        RunDetails details = heartbeat.fail(failure);
        RunEvents.runFailed(details.runId(), details.mode(), failure);
        return details;
    }

    @Override
    public void close() {
        if (closeHeartbeat) {
            heartbeat.close();
        }
    }
}

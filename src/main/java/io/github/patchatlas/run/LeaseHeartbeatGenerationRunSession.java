package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import java.util.Objects;

/**
 * 生产 {@link GenerationRunSession}：所有写与 currentClaim 均在 {@link LeaseHeartbeat} 锁内完成。
 */
public final class LeaseHeartbeatGenerationRunSession implements GenerationRunSession {

    private final PostgresRunStore store;
    private final LeaseHeartbeat heartbeat;

    public LeaseHeartbeatGenerationRunSession(PostgresRunStore store, LeaseHeartbeat heartbeat) {
        this.store = Objects.requireNonNull(store, "store");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
    }

    @Override
    public ReserveResult reserveGenerationAttempt(String provider, String modelName) {
        try {
            // 预占与读 count 同锁：runTransition 内部持锁
            ClaimedRun next = heartbeat.reserveGenerationAttempt(provider, modelName);
            // ordinal 来自预占后 claim 的同事务结果：再读 count 必须仍在锁内
            int ordinal = heartbeat.runLocked(h -> store.loadGenerationAttemptCount(h.runId()));
            return new ReserveResult.Reserved(next, ordinal);
        } catch (GenerationAttemptsExhaustedException exhausted) {
            RunDetails failed = heartbeat.fail(new RunFailure(
                    FailureStage.GENERATION,
                    FailureCategory.GENERATION_EXHAUSTED,
                    "generation attempts exhausted"));
            return new ReserveResult.Exhausted(failed);
        } catch (StaleClaimException stale) {
            return new ReserveResult.Stale(stale);
        }
    }

    @Override
    public ClaimedRun recordModelUsage(ModelUsage usage) {
        return heartbeat.recordModelUsage(usage);
    }

    @Override
    public ClaimedRun commitCandidate(GatedCandidate gated) {
        return heartbeat.commitCandidate(gated);
    }

    @Override
    public RunDetails fail(RunFailure failure) {
        return heartbeat.fail(failure);
    }

    /**
     * 在心跳锁内根据当前 handle 重建 ClaimedRun，避免「读 handle → 心跳 bump → findClaimed stale」。
     */
    @Override
    public ClaimedRun currentClaim() {
        return heartbeat.runLocked(handle -> store.findClaimed(handle)
                .orElseThrow(() -> new StaleClaimException(handle.runId(), "claim not found")));
    }
}

package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import java.util.Objects;

/**
 * 生产 {@link GenerationRunSession}：所有写均在 {@link LeaseHeartbeat} 锁内完成。
 */
public final class LeaseHeartbeatGenerationRunSession implements GenerationRunSession {

    private final LeaseHeartbeat heartbeat;

    public LeaseHeartbeatGenerationRunSession(LeaseHeartbeat heartbeat) {
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
    }

    @Override
    public ReserveResult reserveGenerationAttempt(String provider, String modelName) {
        try {
            PostgresRunStore.ReservedGenerationAttempt reserved =
                    heartbeat.reserveGenerationAttempt(provider, modelName);
            return new ReserveResult.Reserved(reserved.claim(), reserved.ordinal());
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

}

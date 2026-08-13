package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.observability.RunEvents;
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
            RunEvents.generationAttemptReserved(
                    reserved.claim().runId(), reserved.ordinal(), provider, modelName);
            return new ReserveResult.Reserved(reserved.claim(), reserved.ordinal());
        } catch (GenerationAttemptsExhaustedException exhausted) {
            RunDetails failed = heartbeat.fail(new RunFailure(
                    FailureStage.GENERATION,
                    FailureCategory.GENERATION_EXHAUSTED,
                    "generation attempts exhausted"));
            RunEvents.runFailed(failed.runId(), failed.mode(), failed.failure().orElseThrow());
            return new ReserveResult.Exhausted(failed);
        } catch (StaleClaimException stale) {
            return new ReserveResult.Stale(stale);
        }
    }

    @Override
    public ClaimedRun recordModelUsage(ModelUsage usage) {
        ClaimedRun claimed = heartbeat.recordModelUsage(usage);
        RunEvents.generationUsageRecorded(
                claimed.runId(), usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), null);
        return claimed;
    }

    @Override
    public ClaimedRun commitCandidate(GatedCandidate gated) {
        ClaimedRun claimed = heartbeat.commitCandidate(gated);
        RunEvents.candidateCommitted(claimed.runId());
        return claimed;
    }

    @Override
    public RunDetails fail(RunFailure failure) {
        RunDetails details = heartbeat.fail(failure);
        RunEvents.runFailed(details.runId(), details.mode(), failure);
        return details;
    }

}

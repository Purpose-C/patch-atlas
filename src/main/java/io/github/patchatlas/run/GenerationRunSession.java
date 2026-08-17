package io.github.patchatlas.run;

import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.ModelUsage;
import java.util.UUID;

/**
 * 编排器唯一持久化 seam：预占、usage、提交候选、失败。
 *
 * <p>生产由 {@link LeaseHeartbeat} 串行化；测试使用内存实现。
 */
public interface GenerationRunSession {

    sealed interface ReserveResult
            permits ReserveResult.Reserved, ReserveResult.Exhausted, ReserveResult.Stale {
        record Reserved(ClaimedRun claim, int attemptOrdinal) implements ReserveResult {}

        record Exhausted(RunDetails failedRun) implements ReserveResult {}

        record Stale(UUID runId, String reason) implements ReserveResult {
            StaleClaimException toException() {
                return new StaleClaimException(runId, reason);
            }
        }
    }

    ReserveResult reserveGenerationAttempt(String provider, String modelName);

    ClaimedRun recordModelUsage(ModelUsage usage);

    default ClaimedRun recordModelUsage(ModelUsage usage, CompletionDiagnostics diagnostics) {
        return recordModelUsage(usage);
    }

    ClaimedRun commitCandidate(GatedCandidate gated);

    RunDetails fail(RunFailure failure);

    RunPurpose purpose();
}

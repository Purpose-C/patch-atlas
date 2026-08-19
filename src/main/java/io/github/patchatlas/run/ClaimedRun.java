package io.github.patchatlas.run;

import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.replay.VerificationMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 短事务领取成功后的运行态句柄：持有 lease token + version，供后续续租/提交。
 *
 * <p>{@code completionDiagnostics} 只来自持久化的 {@code model_finish_reason}；
 * 取不到或字面量为 unknown 时为 {@link CompletionDiagnostics#unknown()}，不得从 provenance 推导。
 */
public record ClaimedRun(
        UUID runId,
        VerificationMode mode,
        RunState state,
        long version,
        RunLease lease,
        int recoveryCount,
        int replayRound,
        Optional<PersistedCandidatePatch> candidate,
        CompletionDiagnostics completionDiagnostics) {

    public ClaimedRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(completionDiagnostics, "completionDiagnostics");
        if (!state.holdsLease()) {
            throw new IllegalArgumentException("ClaimedRun must be in a lease-holding state");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (recoveryCount < 0) {
            throw new IllegalArgumentException("recoveryCount must not be negative");
        }
        if (replayRound < 0) {
            throw new IllegalArgumentException("replayRound must not be negative");
        }
        if (state == RunState.REPLAYING && candidate.isEmpty()) {
            throw new IllegalArgumentException("REPLAYING claim requires a candidate");
        }
        if ((state == RunState.LOCATING || state == RunState.GENERATING) && candidate.isPresent()) {
            throw new IllegalArgumentException(state + " claim must not carry a candidate");
        }
        RunLeaseRules.requireLeaseShape(state, lease);
    }

    public ClaimedRun(
            UUID runId,
            VerificationMode mode,
            RunState state,
            long version,
            RunLease lease,
            int recoveryCount,
            int replayRound,
            Optional<PersistedCandidatePatch> candidate) {
        this(
                runId,
                mode,
                state,
                version,
                lease,
                recoveryCount,
                replayRound,
                candidate,
                CompletionDiagnostics.unknown());
    }
}

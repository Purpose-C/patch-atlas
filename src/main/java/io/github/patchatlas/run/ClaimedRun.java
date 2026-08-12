package io.github.patchatlas.run;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 短事务领取成功后的运行态句柄：持有 lease token + version，供后续续租/提交。
 */
public record ClaimedRun(
        UUID runId,
        VerificationMode mode,
        RunState state,
        long version,
        RunLease lease,
        int recoveryCount,
        int replayRound,
        Optional<PersistedCandidatePatch> candidate) {

    public ClaimedRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(candidate, "candidate");
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
        if (state == RunState.GENERATING && candidate.isPresent()) {
            throw new IllegalArgumentException("GENERATING claim must not carry a candidate");
        }
        RunLeaseRules.requireLeaseShape(state, lease);
    }
}

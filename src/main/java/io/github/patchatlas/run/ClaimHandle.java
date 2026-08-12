package io.github.patchatlas.run;

import java.util.Objects;
import java.util.UUID;

/**
 * 写操作所需的最小凭证：runId + lease token + expected version + expected state。
 */
public record ClaimHandle(UUID runId, UUID leaseToken, long version, RunState state) {

    public ClaimHandle {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(state, "state");
        if (!state.holdsLease()) {
            throw new IllegalArgumentException("claim handle requires a lease-holding state");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public static ClaimHandle from(ClaimedRun claimed) {
        Objects.requireNonNull(claimed, "claimed");
        return new ClaimHandle(
                claimed.runId(),
                claimed.lease().token(),
                claimed.version(),
                claimed.state());
    }
}

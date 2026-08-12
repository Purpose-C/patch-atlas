package io.github.patchatlas.run;

import java.time.Instant;
import java.util.Objects;

/**
 * 租约形状、版本递增与恢复次数上限（V1：最多 3 次成功接管）。
 */
public final class RunLeaseRules {

    /** 规格：最多接管同一 Run 三次；第四次写 FAILED/RECOVERY_EXHAUSTED。 */
    public static final int MAX_RECOVERY_COUNT = 3;

    private RunLeaseRules() {}

    public static void requireLeaseShape(RunState state, RunLease lease) {
        Objects.requireNonNull(state, "state");
        if (state.holdsLease()) {
            if (lease == null) {
                throw new IllegalArgumentException("lease required for " + state);
            }
            return;
        }
        if (lease != null) {
            throw new IllegalArgumentException(state + " must not hold a lease");
        }
    }

    public static boolean isExpired(RunLease lease, Instant databaseNow) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(databaseNow, "databaseNow");
        return !lease.expiresAt().isAfter(databaseNow);
    }

    public static long nextVersion(long currentVersion) {
        if (currentVersion < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        return currentVersion + 1;
    }

    public static boolean canReclaim(int recoveryCount) {
        if (recoveryCount < 0) {
            throw new IllegalArgumentException("recoveryCount must not be negative");
        }
        return recoveryCount < MAX_RECOVERY_COUNT;
    }

    public static int nextRecoveryCount(int recoveryCount) {
        if (!canReclaim(recoveryCount)) {
            throw new IllegalStateException("recovery exhausted: count=" + recoveryCount);
        }
        return recoveryCount + 1;
    }
}

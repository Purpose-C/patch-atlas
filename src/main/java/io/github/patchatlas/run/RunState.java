package io.github.patchatlas.run;

/**
 * Verification Run 生命周期状态。与 {@link io.github.patchatlas.replay.ReplayVerdict} 分离。
 */
public enum RunState {
    QUEUED,
    LOCATING,
    GENERATING,
    REPLAYING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean holdsLease() {
        return this == LOCATING || this == GENERATING || this == REPLAYING;
    }

    /** 首次领取：仅 QUEUED。 */
    public boolean canBeClaimed() {
        return this == QUEUED;
    }

    /** 租约过期后可被其他 owner 接管（状态不倒退）。 */
    public boolean canBeReclaimedWhenLeaseExpired() {
        return this == LOCATING || this == GENERATING || this == REPLAYING;
    }
}

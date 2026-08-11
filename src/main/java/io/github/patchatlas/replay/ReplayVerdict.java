package io.github.patchatlas.replay;

/**
 * Replay Engine 在 Live 或跨 revision 稳定证据上的最终机械裁决。
 *
 * <p>与 {@link RunOutcome}（单侧分类）分层，不得混用。
 */
public enum ReplayVerdict {
    VALID_REPRODUCTION,
    REPRODUCTION_CANDIDATE,
    FAILED_ON_BOTH_COMMITS,
    NOT_REPRODUCED,
    INCONCLUSIVE
}

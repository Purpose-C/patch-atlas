package io.github.patchatlas.replay;

/**
 * 单次执行相对 Target Test 的可裁决证据。
 *
 * <p>尚未跨多次尝试稳定化；稳定化见 {@link SideEvidenceStabilizer}。
 */
public enum SingleAttemptEvidence {
    TARGET_PASSED,
    TARGET_ASSERTION_FAILURE,
    INVALID
}

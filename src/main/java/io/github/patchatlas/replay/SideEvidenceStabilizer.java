package io.github.patchatlas.replay;

import java.util.List;
import java.util.Objects;

/**
 * 将 V1 固定的两次单次目标证据归约为 {@link StableSideEvidence}。
 *
 * <p>任一无效、或两次不一致 → {@link StableSideEvidence#OTHER_OR_INVALID}（含 flaky）。
 */
public final class SideEvidenceStabilizer {

    public static final int REQUIRED_ATTEMPTS = 2;

    public StableSideEvidence stabilize(List<SingleAttemptEvidence> attempts) {
        Objects.requireNonNull(attempts, "attempts");
        if (attempts.size() != REQUIRED_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "V1 requires exactly " + REQUIRED_ATTEMPTS + " attempts, got " + attempts.size());
        }
        SingleAttemptEvidence first = Objects.requireNonNull(attempts.get(0), "attempt");
        SingleAttemptEvidence second = Objects.requireNonNull(attempts.get(1), "attempt");

        if (first == SingleAttemptEvidence.INVALID || second == SingleAttemptEvidence.INVALID) {
            return StableSideEvidence.OTHER_OR_INVALID;
        }
        if (first != second) {
            return StableSideEvidence.OTHER_OR_INVALID;
        }
        return switch (first) {
            case TARGET_PASSED -> StableSideEvidence.TARGET_PASSED;
            case TARGET_ASSERTION_FAILURE -> StableSideEvidence.TARGET_ASSERTION_FAILURE;
            case INVALID -> StableSideEvidence.OTHER_OR_INVALID;
        };
    }
}

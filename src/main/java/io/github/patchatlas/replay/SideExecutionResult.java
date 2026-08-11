package io.github.patchatlas.replay;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 某一 revision 侧两次尝试后的证据与稳定归约。
 *
 * <p>构造时根据 attempts 重算稳定证据并校验 {@link #aggregatedOutcome()} 一致性。
 */
public record SideExecutionResult(
        List<AttemptRecord> attempts,
        StableSideEvidence stableEvidence,
        Optional<RunOutcome> aggregatedOutcome) {

    public SideExecutionResult {
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        Objects.requireNonNull(stableEvidence, "stableEvidence");
        aggregatedOutcome = Objects.requireNonNull(aggregatedOutcome, "aggregatedOutcome");
        if (attempts.size() != SideEvidenceStabilizer.REQUIRED_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "side result requires exactly "
                            + SideEvidenceStabilizer.REQUIRED_ATTEMPTS
                            + " attempts");
        }

        List<SingleAttemptEvidence> evidences =
                attempts.stream().map(AttemptRecord::targetEvidence).toList();
        StableSideEvidence expectedStable = new SideEvidenceStabilizer().stabilize(evidences);
        if (aggregatedOutcome.isPresent() && aggregatedOutcome.get() == RunOutcome.FLAKY_FAILURE) {
            expectedStable = StableSideEvidence.OTHER_OR_INVALID;
        }
        if (stableEvidence != expectedStable) {
            throw new IllegalArgumentException(
                    "stableEvidence inconsistent with attempts: expected " + expectedStable);
        }

        Optional<RunOutcome> first = attempts.get(0).outcome();
        Optional<RunOutcome> second = attempts.get(1).outcome();
        if (first.isEmpty() || second.isEmpty()) {
            if (aggregatedOutcome.isPresent()) {
                throw new IllegalArgumentException(
                        "aggregatedOutcome must be empty when any attempt lacks outcome");
            }
        } else {
            if (aggregatedOutcome.isEmpty()) {
                throw new IllegalArgumentException(
                        "aggregatedOutcome required when both attempts have outcomes");
            }
            RunOutcome expectedAgg =
                    first.get() == second.get() ? first.get() : RunOutcome.FLAKY_FAILURE;
            if (aggregatedOutcome.get() != expectedAgg) {
                throw new IllegalArgumentException(
                        "aggregatedOutcome inconsistent with attempt outcomes");
            }
        }
    }
}

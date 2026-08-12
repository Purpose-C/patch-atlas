package io.github.patchatlas.replay;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 某一 revision 侧两次尝试后的证据与稳定归约。
 *
 * <p>attempts 是唯一原始事实；稳定证据与聚合结果由本类型统一派生。
 */
public record SideExecutionResult(List<AttemptRecord> attempts) {

    public SideExecutionResult {
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        if (attempts.size() != SideEvidenceStabilizer.REQUIRED_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "side result requires exactly "
                            + SideEvidenceStabilizer.REQUIRED_ATTEMPTS
                            + " attempts");
        }

    }

    public StableSideEvidence stableEvidence() {
        if (aggregatedOutcome().filter(outcome -> outcome == RunOutcome.FLAKY_FAILURE).isPresent()) {
            return StableSideEvidence.OTHER_OR_INVALID;
        }
        List<SingleAttemptEvidence> evidences =
                attempts.stream().map(AttemptRecord::targetEvidence).toList();
        return new SideEvidenceStabilizer().stabilize(evidences);
    }

    public Optional<RunOutcome> aggregatedOutcome() {
        Optional<RunOutcome> first = attempts.get(0).outcome();
        Optional<RunOutcome> second = attempts.get(1).outcome();
        if (first.isEmpty() || second.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionClassifier().classifyAttempts(List.of(first.get(), second.get())));
    }
}

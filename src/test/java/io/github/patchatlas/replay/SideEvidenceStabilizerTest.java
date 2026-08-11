package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class SideEvidenceStabilizerTest {

    private final SideEvidenceStabilizer stabilizer = new SideEvidenceStabilizer();

    @Test
    void twoTargetPassesStabilizeToTargetPassed() {
        assertThat(stabilizer.stabilize(List.of(
                        SingleAttemptEvidence.TARGET_PASSED, SingleAttemptEvidence.TARGET_PASSED)))
                .isEqualTo(StableSideEvidence.TARGET_PASSED);
    }

    @Test
    void twoTargetAssertionFailuresStabilize() {
        assertThat(stabilizer.stabilize(List.of(
                        SingleAttemptEvidence.TARGET_ASSERTION_FAILURE,
                        SingleAttemptEvidence.TARGET_ASSERTION_FAILURE)))
                .isEqualTo(StableSideEvidence.TARGET_ASSERTION_FAILURE);
    }

    @Test
    void mixedPassAndFailureIsOtherOrInvalid() {
        assertThat(stabilizer.stabilize(List.of(
                        SingleAttemptEvidence.TARGET_ASSERTION_FAILURE,
                        SingleAttemptEvidence.TARGET_PASSED)))
                .isEqualTo(StableSideEvidence.OTHER_OR_INVALID);
    }

    @Test
    void anyInvalidAttemptMakesSideInvalid() {
        assertThat(stabilizer.stabilize(List.of(
                        SingleAttemptEvidence.TARGET_ASSERTION_FAILURE, SingleAttemptEvidence.INVALID)))
                .isEqualTo(StableSideEvidence.OTHER_OR_INVALID);
        assertThat(stabilizer.stabilize(
                        List.of(SingleAttemptEvidence.INVALID, SingleAttemptEvidence.INVALID)))
                .isEqualTo(StableSideEvidence.OTHER_OR_INVALID);
    }

    @Test
    void requiresExactlyTwoAttemptsForV1() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> stabilizer.stabilize(List.of(SingleAttemptEvidence.TARGET_PASSED)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> stabilizer.stabilize(List.of(
                        SingleAttemptEvidence.TARGET_PASSED,
                        SingleAttemptEvidence.TARGET_PASSED,
                        SingleAttemptEvidence.TARGET_PASSED)));
    }
}

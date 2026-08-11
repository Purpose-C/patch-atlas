package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReplayResultInvariantTest {

    private final TargetTest target = ReplayTestFixtures.TARGET;

    @Test
    void liveCannotCarryFixedNotExecutedReason() {
        SideExecutionResult side = ReplayTestFixtures.targetPassedSide();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayResult(
                        VerificationMode.LIVE,
                        ReplayVerdict.NOT_REPRODUCED,
                        target,
                        side,
                        Optional.empty(),
                        Optional.of("should not exist")));
    }

    @Test
    void liveCannotHaveValidReproduction() {
        SideExecutionResult side = ReplayTestFixtures.targetAssertionFailureSide();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayResult(
                        VerificationMode.LIVE,
                        ReplayVerdict.VALID_REPRODUCTION,
                        target,
                        side,
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void liveReproductionCandidateRequiresTargetAssertionFailure() {
        SideExecutionResult passed = ReplayTestFixtures.targetPassedSide();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayResult(
                        VerificationMode.LIVE,
                        ReplayVerdict.REPRODUCTION_CANDIDATE,
                        target,
                        passed,
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void historicalRequiresFixedOrShortCircuitReason() {
        SideExecutionResult side = ReplayTestFixtures.targetPassedSide();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayResult(
                        VerificationMode.HISTORICAL,
                        ReplayVerdict.NOT_REPRODUCED,
                        target,
                        side,
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void historicalCannotHaveReproductionCandidate() {
        SideExecutionResult side = ReplayTestFixtures.targetAssertionFailureSide();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayResult(
                        VerificationMode.HISTORICAL,
                        ReplayVerdict.REPRODUCTION_CANDIDATE,
                        target,
                        side,
                        Optional.of(ReplayTestFixtures.targetPassedSide()),
                        Optional.empty()));
    }

    @Test
    void historicalValidReproductionRequiresMatchingSides() {
        // buggy pass + fixed pass 不能叫 VALID
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayResult(
                        VerificationMode.HISTORICAL,
                        ReplayVerdict.VALID_REPRODUCTION,
                        target,
                        ReplayTestFixtures.targetPassedSide(),
                        Optional.of(ReplayTestFixtures.targetPassedSide()),
                        Optional.empty()));
    }

    @Test
    void sideResultRejectsStableEvidenceInconsistentWithAttempts() {
        AttemptRecord pre = AttemptRecord.preExecutionFailure("x");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SideExecutionResult(
                        java.util.List.of(pre, pre),
                        StableSideEvidence.TARGET_PASSED,
                        Optional.empty()));
    }
}

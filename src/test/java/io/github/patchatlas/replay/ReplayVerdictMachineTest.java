package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 纯状态机：只吃归一化的 {@link StableSideEvidence} 与真实 {@link FixedSide}，
 * 不依赖 Docker / 文件系统 / Spring。
 */
class ReplayVerdictMachineTest {

    private final ReplayVerdictMachine machine = new ReplayVerdictMachine();

    @ParameterizedTest(name = "Live {0} → {1}")
    @MethodSource("liveCases")
    void liveVerdictTable(StableSideEvidence defectSide, ReplayVerdict expected) {
        assertThat(machine.decideLive(defectSide)).isEqualTo(expected);
    }

    static Stream<Arguments> liveCases() {
        return Stream.of(
                Arguments.of(StableSideEvidence.TARGET_ASSERTION_FAILURE, ReplayVerdict.REPRODUCTION_CANDIDATE),
                Arguments.of(StableSideEvidence.TARGET_PASSED, ReplayVerdict.NOT_REPRODUCED),
                Arguments.of(StableSideEvidence.OTHER_OR_INVALID, ReplayVerdict.INCONCLUSIVE));
    }

    @ParameterizedTest(name = "Historical buggy={0} fixed={1} → {2}")
    @MethodSource("historicalCases")
    void historicalVerdictTable(
            StableSideEvidence buggySide, FixedSide fixedSide, ReplayVerdict expected) {
        assertThat(machine.decideHistorical(buggySide, fixedSide)).isEqualTo(expected);
    }

    static Stream<Arguments> historicalCases() {
        return Stream.of(
                Arguments.of(
                        StableSideEvidence.TARGET_PASSED,
                        FixedSide.notExecuted(),
                        ReplayVerdict.NOT_REPRODUCED),
                Arguments.of(
                        StableSideEvidence.OTHER_OR_INVALID,
                        FixedSide.notExecuted(),
                        ReplayVerdict.INCONCLUSIVE),
                Arguments.of(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE,
                        FixedSide.executed(StableSideEvidence.TARGET_PASSED),
                        ReplayVerdict.VALID_REPRODUCTION),
                Arguments.of(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE,
                        FixedSide.executed(StableSideEvidence.TARGET_ASSERTION_FAILURE),
                        ReplayVerdict.FAILED_ON_BOTH_COMMITS),
                Arguments.of(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE,
                        FixedSide.executed(StableSideEvidence.OTHER_OR_INVALID),
                        ReplayVerdict.INCONCLUSIVE));
    }

    @Test
    void shortCircuitMustUseNotExecutedNotForgedFixedEvidence() {
        assertThat(machine.decideHistorical(
                        StableSideEvidence.TARGET_PASSED, FixedSide.notExecuted()))
                .isEqualTo(ReplayVerdict.NOT_REPRODUCED);

        // P2：短路时传入伪造 Fixed 证据应被拒绝，而不是静默忽略
        assertThatIllegalArgumentException()
                .isThrownBy(() -> machine.decideHistorical(
                        StableSideEvidence.TARGET_PASSED,
                        FixedSide.executed(StableSideEvidence.TARGET_ASSERTION_FAILURE)));
    }

    @Test
    void assertionFailureRequiresExecutedFixedSide() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> machine.decideHistorical(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE, FixedSide.notExecuted()));
    }

    @Test
    void validReproductionRequiresBuggyFailureAndFixedPass() {
        assertThat(machine.decideHistorical(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE,
                        FixedSide.executed(StableSideEvidence.TARGET_PASSED)))
                .isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(machine.decideHistorical(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE,
                        FixedSide.executed(StableSideEvidence.TARGET_ASSERTION_FAILURE)))
                .isNotEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(machine.decideLive(StableSideEvidence.TARGET_ASSERTION_FAILURE))
                .isNotEqualTo(ReplayVerdict.VALID_REPRODUCTION);
    }

    @Test
    void reproductionCandidateOnlyFromLiveTargetAssertionFailure() {
        assertThat(machine.decideLive(StableSideEvidence.TARGET_ASSERTION_FAILURE))
                .isEqualTo(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(machine.decideHistorical(
                        StableSideEvidence.TARGET_ASSERTION_FAILURE,
                        FixedSide.executed(StableSideEvidence.TARGET_PASSED)))
                .isNotEqualTo(ReplayVerdict.REPRODUCTION_CANDIDATE);
    }
}

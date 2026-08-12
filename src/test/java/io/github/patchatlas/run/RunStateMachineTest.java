package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ��Run 生命周期迁移表（tasks/specs/016-postgresql-run-recovery.md）。
 */
class RunStateMachineTest {

    private final RunStateMachine machine = new RunStateMachine();

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("legalTransitions")
    void appliesLegalTransitions(RunState from, RunTransition transition, RunState expected) {
        assertThat(machine.canApply(from, transition)).isTrue();
        assertThat(machine.apply(from, transition)).isEqualTo(expected);
    }

    static Stream<Arguments> legalTransitions() {
        return Stream.of(
                Arguments.of(RunState.QUEUED, RunTransition.CLAIM, RunState.GENERATING),
                Arguments.of(RunState.GENERATING, RunTransition.COMMIT_CANDIDATE, RunState.REPLAYING),
                Arguments.of(RunState.GENERATING, RunTransition.FAIL, RunState.FAILED),
                Arguments.of(RunState.REPLAYING, RunTransition.COMPLETE, RunState.COMPLETED),
                Arguments.of(RunState.REPLAYING, RunTransition.FAIL, RunState.FAILED));
    }

    @ParameterizedTest
    @MethodSource("illegalTransitions")
    void rejectsIllegalTransitions(RunState from, RunTransition transition) {
        assertThat(machine.canApply(from, transition)).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> machine.apply(from, transition));
    }

    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
                // 不能跳过阶段
                Arguments.of(RunState.QUEUED, RunTransition.COMMIT_CANDIDATE),
                Arguments.of(RunState.QUEUED, RunTransition.COMPLETE),
                Arguments.of(RunState.QUEUED, RunTransition.FAIL),
                Arguments.of(RunState.GENERATING, RunTransition.COMPLETE),
                Arguments.of(RunState.GENERATING, RunTransition.CLAIM),
                Arguments.of(RunState.REPLAYING, RunTransition.CLAIM),
                Arguments.of(RunState.REPLAYING, RunTransition.COMMIT_CANDIDATE),
                // 终态不可迁移
                Arguments.of(RunState.COMPLETED, RunTransition.CLAIM),
                Arguments.of(RunState.COMPLETED, RunTransition.FAIL),
                Arguments.of(RunState.COMPLETED, RunTransition.COMPLETE),
                Arguments.of(RunState.FAILED, RunTransition.CLAIM),
                Arguments.of(RunState.FAILED, RunTransition.COMMIT_CANDIDATE),
                Arguments.of(RunState.FAILED, RunTransition.COMPLETE),
                Arguments.of(RunState.FAILED, RunTransition.FAIL));
    }

    @Test
    void reclaimDoesNotChangeState() {
        assertThat(machine.apply(RunState.GENERATING, RunTransition.RECLAIM))
                .isEqualTo(RunState.GENERATING);
        assertThat(machine.apply(RunState.REPLAYING, RunTransition.RECLAIM))
                .isEqualTo(RunState.REPLAYING);
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"QUEUED", "COMPLETED", "FAILED"})
    void reclaimIllegalFromNonRunningStates(RunState state) {
        assertThat(machine.canApply(state, RunTransition.RECLAIM)).isFalse();
        assertThatIllegalStateException()
                .isThrownBy(() -> machine.apply(state, RunTransition.RECLAIM));
    }

    @Test
    void terminalStatesHoldNoLeaseAndCannotBeClaimed() {
        assertThat(RunState.COMPLETED.isTerminal()).isTrue();
        assertThat(RunState.FAILED.isTerminal()).isTrue();
        assertThat(RunState.COMPLETED.holdsLease()).isFalse();
        assertThat(RunState.FAILED.holdsLease()).isFalse();
        assertThat(RunState.COMPLETED.canBeClaimed()).isFalse();
        assertThat(RunState.FAILED.canBeClaimed()).isFalse();
    }

    @Test
    void runningStatesHoldLeaseAndQueuedIsClaimable() {
        assertThat(RunState.GENERATING.holdsLease()).isTrue();
        assertThat(RunState.REPLAYING.holdsLease()).isTrue();
        assertThat(RunState.QUEUED.holdsLease()).isFalse();
        assertThat(RunState.QUEUED.canBeClaimed()).isTrue();
        assertThat(RunState.GENERATING.canBeReclaimedWhenLeaseExpired()).isTrue();
        assertThat(RunState.REPLAYING.canBeReclaimedWhenLeaseExpired()).isTrue();
        assertThat(RunState.QUEUED.canBeReclaimedWhenLeaseExpired()).isFalse();
    }
}

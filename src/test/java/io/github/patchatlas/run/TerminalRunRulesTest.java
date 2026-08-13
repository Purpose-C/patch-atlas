package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.patchatlas.replay.ReplayVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * COMPLETED / FAILED 与 ReplayVerdict、failure 字段互斥（纯领域规则）。
 */
class TerminalRunRulesTest {

    @ParameterizedTest
    @EnumSource(ReplayVerdict.class)
    void completedRequiresVerdictAndForbidsFailure(ReplayVerdict verdict) {
        // INCONCLUSIVE 也是合法 COMPLETED 裁决
        assertThatCode(() -> TerminalRunRules.requireCompleted(verdict, null))
                .doesNotThrowAnyException();

        RunFailure failure = new RunFailure(
                FailureStage.GENERATION, FailureCategory.GENERATION_FAILURE, "nope");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TerminalRunRules.requireCompleted(verdict, failure));
    }

    @Test
    void completedRejectsNullVerdict() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TerminalRunRules.requireCompleted(null, null));
    }

    @Test
    void failedRequiresFailureAndForbidsVerdict() {
        RunFailure failure = new RunFailure(
                FailureStage.PATCH_GATE, FailureCategory.PATCH_REJECTED, "path out of scope");

        assertThatCode(() -> TerminalRunRules.requireFailed(null, failure)).doesNotThrowAnyException();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> TerminalRunRules.requireFailed(ReplayVerdict.INCONCLUSIVE, failure));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TerminalRunRules.requireFailed(null, null));
    }

    @Test
    void workspaceStageAllowsUnsafeAndError() {
        assertThat(RunFailure.legalPair(FailureStage.WORKSPACE, FailureCategory.WORKSPACE_UNSAFE))
                .isTrue();
        assertThat(RunFailure.legalPair(FailureStage.WORKSPACE, FailureCategory.WORKSPACE_ERROR))
                .isTrue();
        assertThatCode(() -> new RunFailure(
                        FailureStage.WORKSPACE,
                        FailureCategory.WORKSPACE_ERROR,
                        "workspace: IllegalArgumentException"))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RunFailure(
                        FailureStage.WORKSPACE, FailureCategory.PATCH_REJECTED, "nope"));
    }

    @Test
    void recoveryExhaustedIsFailedWithRecoveryStage() {
        RunFailure failure = new RunFailure(
                FailureStage.RECOVERY, FailureCategory.RECOVERY_EXHAUSTED, "max recoveries reached");
        assertThatCode(() -> TerminalRunRules.requireFailed(null, failure)).doesNotThrowAnyException();
        assertThat(failure.stage()).isEqualTo(FailureStage.RECOVERY);
        assertThat(failure.category()).isEqualTo(FailureCategory.RECOVERY_EXHAUSTED);
    }
}

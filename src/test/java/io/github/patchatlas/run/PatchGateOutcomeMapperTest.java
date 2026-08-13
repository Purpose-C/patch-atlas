package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.GenerationFeedbackCategory;
import io.github.patchatlas.agent.PatchRejectionCategory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PatchGateOutcomeMapperTest {

    @ParameterizedTest
    @EnumSource(PatchRejectionCategory.class)
    void mapsEveryCategory(PatchRejectionCategory category) {
        PatchGateOutcomeMapper.Outcome outcome = PatchGateOutcomeMapper.map(category, "r");
        assertThat(outcome).isNotNull();
        switch (category) {
            case WORKSPACE_UNSAFE ->
                    assertThat(outcome).isInstanceOf(PatchGateOutcomeMapper.Outcome.Terminal.class);
            default ->
                    assertThat(outcome).isInstanceOf(PatchGateOutcomeMapper.Outcome.Correctable.class);
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"UNSUPPORTED_CHANGE_TYPE", "UNSAFE_OR_OUT_OF_SCOPE_PATH"})
    void policyRejectionsAreCorrectable(PatchRejectionCategory category) {
        PatchGateOutcomeMapper.Outcome outcome = PatchGateOutcomeMapper.map(category, "bad path");
        assertThat(outcome).isInstanceOf(PatchGateOutcomeMapper.Outcome.Correctable.class);
        var correctable = (PatchGateOutcomeMapper.Outcome.Correctable) outcome;
        assertThat(correctable.feedback().category())
                .isEqualTo(GenerationFeedbackCategory.PATCH_POLICY_REJECTED);
    }

    @org.junit.jupiter.api.Test
    void workspaceUnsafeRemainsTerminal() {
        PatchGateOutcomeMapper.Outcome outcome =
                PatchGateOutcomeMapper.map(PatchRejectionCategory.WORKSPACE_UNSAFE, "symlink attack");
        assertThat(outcome).isInstanceOf(PatchGateOutcomeMapper.Outcome.Terminal.class);
        var terminal = (PatchGateOutcomeMapper.Outcome.Terminal) outcome;
        assertThat(terminal.failure().stage()).isEqualTo(FailureStage.WORKSPACE);
        assertThat(terminal.failure().category()).isEqualTo(FailureCategory.WORKSPACE_UNSAFE);
    }
}

package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PatchGateOutcomeMapperTest {

    @ParameterizedTest
    @EnumSource(PatchRejectionCategory.class)
    void mapsEveryCategory(PatchRejectionCategory category) {
        PatchGateOutcomeMapper.Outcome outcome = PatchGateOutcomeMapper.map(category, "r");
        assertThat(outcome).isNotNull();
        switch (category) {
            case UNSAFE_OR_OUT_OF_SCOPE_PATH, UNSUPPORTED_CHANGE_TYPE, WORKSPACE_UNSAFE ->
                    assertThat(outcome).isInstanceOf(PatchGateOutcomeMapper.Outcome.Terminal.class);
            default ->
                    assertThat(outcome).isInstanceOf(PatchGateOutcomeMapper.Outcome.Correctable.class);
        }
    }
}

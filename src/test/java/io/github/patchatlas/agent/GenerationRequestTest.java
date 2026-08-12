package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationRequestTest {

    @Test
    void feedbackOnlyAllowsFeedbackWithoutPreviousDraft() {
        GenerationFeedback feedback = new GenerationFeedback(
                GenerationFeedbackCategory.STRUCTURED_OUTPUT_INVALID, "not json");
        GenerationRequest req = GenerationRequest.feedbackOnly(sampleInput(), 2, feedback);
        assertThat(req.hasFeedback()).isTrue();
        assertThat(req.previousDraft()).isEmpty();
        assertThat(req.isCorrection()).isFalse();
        assertThat(req.generationFeedback()).contains(feedback);
    }

    @Test
    void previousDraftRequiresFeedback() {
        CandidateDraft draft =
                new CandidateDraft("diff", new TargetTest("c.T", "m"));
        assertThatThrownBy(() -> new GenerationRequest(
                        sampleInput(), 1, java.util.Optional.of(draft), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("previousDraft requires generationFeedback");
    }

    private static GenerationInput sampleInput() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "a".repeat(40),
                        "",
                        "21"),
                "t",
                "b",
                List.of());
    }
}

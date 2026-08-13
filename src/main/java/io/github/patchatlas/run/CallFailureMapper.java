package io.github.patchatlas.run;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.GenerationFeedbackCategory;
import java.util.Objects;
import java.util.Optional;

/** 单次调用失败 → 可修正反馈或终态 RunFailure。 */
final class CallFailureMapper {

    public sealed interface Outcome permits Outcome.Correctable, Outcome.Terminal {
        record Correctable(GenerationFeedback feedback) implements Outcome {}

        record Terminal(RunFailure failure) implements Outcome {}
    }

    private CallFailureMapper() {}

    /** 将 Outcome 适配为统一编排决策（Correctable → Retry，Terminal → Fail）。 */
    public static CandidateGenerationCoordinator.AttemptDecision toDecision(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Correctable c -> new CandidateGenerationCoordinator.AttemptDecision.Retry(
                    Optional.empty(), c.feedback());
            case Outcome.Terminal t -> new CandidateGenerationCoordinator.AttemptDecision.Fail(t.failure());
        };
    }

    public static Outcome map(CallFailureCategory category, String summary, boolean hasRemainingQuota) {
        Objects.requireNonNull(category, "category");
        String safe = summary == null || summary.isBlank() ? category.name() : summary;
        return switch (category) {
            case STRUCTURED_OUTPUT_INVALID -> {
                if (hasRemainingQuota) {
                    yield new Outcome.Correctable(new GenerationFeedback(
                            GenerationFeedbackCategory.STRUCTURED_OUTPUT_INVALID, safe));
                }
                yield new Outcome.Terminal(new RunFailure(
                        FailureStage.GENERATION, FailureCategory.GENERATION_EXHAUSTED, safe));
            }
            case MODEL_CONFIGURATION_ERROR -> new Outcome.Terminal(new RunFailure(
                    FailureStage.GENERATION, FailureCategory.MODEL_CONFIGURATION_ERROR, safe));
            case MODEL_AUTHENTICATION_ERROR -> new Outcome.Terminal(new RunFailure(
                    FailureStage.GENERATION, FailureCategory.MODEL_AUTHENTICATION_ERROR, safe));
            case MODEL_UNAVAILABLE -> new Outcome.Terminal(new RunFailure(
                    FailureStage.GENERATION, FailureCategory.MODEL_UNAVAILABLE, safe));
            case MODEL_REFUSED -> new Outcome.Terminal(new RunFailure(
                    FailureStage.GENERATION, FailureCategory.MODEL_REFUSED, safe));
        };
    }
}

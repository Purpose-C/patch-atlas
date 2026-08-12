package io.github.patchatlas.agent;

import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RunFailure;
import java.util.Objects;

/** 单次调用失败 → 可修正反馈或终态 RunFailure。 */
public final class CallFailureMapper {

    public sealed interface Outcome permits Outcome.Correctable, Outcome.Terminal {
        record Correctable(GenerationFeedback feedback) implements Outcome {}

        record Terminal(RunFailure failure) implements Outcome {}
    }

    private CallFailureMapper() {}

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

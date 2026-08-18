package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.GenerationFeedbackCategory;
import io.github.patchatlas.agent.PatchRejectionCategory;
import java.util.Objects;
import java.util.Optional;

/**
 * PatchRejectionCategory → 可修正反馈 / 立即终态 的封闭映射（规格表冻结）。
 */
final class PatchGateOutcomeMapper {

    public sealed interface Outcome permits Outcome.Correctable, Outcome.Terminal {
        record Correctable(GenerationFeedback feedback) implements Outcome {}

        record Terminal(RunFailure failure) implements Outcome {}
    }

    private PatchGateOutcomeMapper() {}

    /** 将 Outcome 适配为统一编排决策（Correctable → Retry，Terminal → Fail）。
     * Retry 的 draft 由调用方提供（gate 拒绝时保留当前 draft 以便修正）。 */
    public static CandidateGenerationCoordinator.AttemptDecision toDecision(
            Outcome outcome, CandidateDraft draft) {
        return switch (outcome) {
            case Outcome.Correctable c -> new CandidateGenerationCoordinator.AttemptDecision.Retry(
                    Optional.of(draft), c.feedback());
            case Outcome.Terminal t -> new CandidateGenerationCoordinator.AttemptDecision.Fail(t.failure());
        };
    }

    public static Outcome map(PatchRejectionCategory category, String reason) {
        Objects.requireNonNull(category, "category");
        String safe = reason == null || reason.isBlank() ? category.name() : reason;
        return switch (category) {
            case MALFORMED_OR_OVERSIZED_PATCH, FILE_OR_LINE_LIMIT_EXCEEDED, TARGET_NOT_CHANGED_BY_PATCH,
                 TARGET_TEST_NOT_DERIVABLE, UNSUPPORTED_CHANGE_TYPE, UNSAFE_OR_OUT_OF_SCOPE_PATH,
                 RESPONSE_TRUNCATED ->
                    new Outcome.Correctable(
                            new GenerationFeedback(GenerationFeedbackCategory.PATCH_POLICY_REJECTED, safe));
            case APPLICATION_FAILURE -> new Outcome.Correctable(
                    new GenerationFeedback(GenerationFeedbackCategory.PATCH_APPLICATION_FAILED, safe));
            case WORKSPACE_UNSAFE -> new Outcome.Terminal(
                    new RunFailure(FailureStage.WORKSPACE, FailureCategory.WORKSPACE_UNSAFE, safe));
        };
    }
}

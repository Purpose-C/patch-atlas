package io.github.patchatlas.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * 单次模型调用请求。
 *
 * <p>约束：
 * <ul>
 *   <li>有 previousDraft 时必须有 generationFeedback（修正轮）；
 *   <li>允许仅有 generationFeedback、无 previousDraft（如 STRUCTURED_OUTPUT_INVALID 后重试）；
 *   <li>二者皆空为首次/无上下文轮。
 * </ul>
 */
public record GenerationRequest(
        GenerationInput generationInput,
        int attemptOrdinal,
        Optional<CandidateDraft> previousDraft,
        Optional<GenerationFeedback> generationFeedback) {

    public static final int MAX_ATTEMPTS = 3;

    public GenerationRequest {
        Objects.requireNonNull(generationInput, "generationInput");
        Objects.requireNonNull(previousDraft, "previousDraft");
        Objects.requireNonNull(generationFeedback, "generationFeedback");
        if (attemptOrdinal < 1 || attemptOrdinal > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attemptOrdinal must be 1..3");
        }
        if (previousDraft.isPresent() && generationFeedback.isEmpty()) {
            throw new IllegalArgumentException("previousDraft requires generationFeedback");
        }
    }

    public static GenerationRequest first(GenerationInput input, int attemptOrdinal) {
        return new GenerationRequest(input, attemptOrdinal, Optional.empty(), Optional.empty());
    }

    /** 有合法上一草稿 + 反馈的修正轮。 */
    public static GenerationRequest correction(
            GenerationInput input,
            int attemptOrdinal,
            CandidateDraft previousDraft,
            GenerationFeedback feedback) {
        return new GenerationRequest(
                input, attemptOrdinal, Optional.of(previousDraft), Optional.of(feedback));
    }

    /**
     * 仅反馈、无合法 previous draft。
     *
     * <p>仅用于未能形成 {@link CandidateDraft} 的结构错误重试（如
     * {@link GenerationFeedbackCategory#STRUCTURED_OUTPUT_INVALID}）。Gate/预验证反馈必须走
     * {@link #correction}。
     */
    public static GenerationRequest feedbackOnly(
            GenerationInput input, int attemptOrdinal, GenerationFeedback feedback) {
        return new GenerationRequest(
                input, attemptOrdinal, Optional.empty(), Optional.of(feedback));
    }

    public boolean isCorrection() {
        return previousDraft.isPresent();
    }

    public boolean hasFeedback() {
        return generationFeedback.isPresent();
    }
}

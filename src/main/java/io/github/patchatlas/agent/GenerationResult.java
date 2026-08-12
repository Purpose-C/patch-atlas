package io.github.patchatlas.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * 单次 {@link TestGenerator} 调用结果：草稿或调用失败（含可选 usage）。
 */
public sealed interface GenerationResult
        permits GenerationResult.GeneratedDraft, GenerationResult.GenerationCallFailure {

    Optional<ModelUsage> usage();

    record GeneratedDraft(CandidateDraft draft, Optional<ModelUsage> usage) implements GenerationResult {
        public GeneratedDraft {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(usage, "usage");
        }

        public GeneratedDraft(CandidateDraft draft) {
            this(draft, Optional.empty());
        }

        public GeneratedDraft(CandidateDraft draft, ModelUsage usage) {
            this(draft, Optional.of(usage));
        }
    }

    record GenerationCallFailure(
            CallFailureCategory category, String summary, Optional<ModelUsage> usage)
            implements GenerationResult {
        public static final int MAX_SUMMARY_CHARS = 512;

        public GenerationCallFailure {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(usage, "usage");
            if (summary.isBlank()) {
                throw new IllegalArgumentException("summary must not be blank");
            }
            if (summary.length() > MAX_SUMMARY_CHARS) {
                summary = summary.substring(0, MAX_SUMMARY_CHARS);
            }
        }

        public GenerationCallFailure(CallFailureCategory category, String summary) {
            this(category, summary, Optional.empty());
        }
    }
}

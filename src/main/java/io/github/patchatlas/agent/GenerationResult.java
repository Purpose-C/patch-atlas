package io.github.patchatlas.agent;

import io.github.patchatlas.replay.TargetTest;
import java.util.Objects;

/** {@link TestGenerator} 的一次生成结果。 */
public sealed interface GenerationResult permits GenerationResult.GeneratedCandidate, GenerationResult.GenerationFailure {

    /**
     * 候选补丁载体。越界/NUL/畸形文本不在此抛系统异常，由 Patch Gate 解析后返回
     * {@link PatchPreparationResult.RejectedCandidate}（不可信模型输出）。
     */
    record GeneratedCandidate(String patchText, TargetTest targetTest) implements GenerationResult {
        public GeneratedCandidate {
            Objects.requireNonNull(patchText, "patchText");
            Objects.requireNonNull(targetTest, "targetTest");
        }
    }

    record GenerationFailure(String reason) implements GenerationResult {
        public static final int MAX_REASON_CHARS = 512;

        public GenerationFailure {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            if (reason.length() > MAX_REASON_CHARS) {
                reason = reason.substring(0, MAX_REASON_CHARS);
            }
        }
    }
}

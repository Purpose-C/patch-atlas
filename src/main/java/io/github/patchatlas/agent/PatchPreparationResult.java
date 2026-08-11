package io.github.patchatlas.agent;

import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.nio.file.Path;
import java.util.Objects;

/** Patch Gate 结果：准备就绪可进 Replay，或结构化拒绝。 */
public sealed interface PatchPreparationResult
        permits PatchPreparationResult.PreparedCandidate, PatchPreparationResult.RejectedCandidate {

    record PreparedCandidate(
            Path workspace,
            String modulePath,
            TargetTest targetTest,
            MavenTestCommand command)
            implements PatchPreparationResult {
        public PreparedCandidate {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(targetTest, "targetTest");
            Objects.requireNonNull(command, "command");
        }
    }

    record RejectedCandidate(PatchRejectionCategory category, String reason)
            implements PatchPreparationResult {
        public static final int MAX_REASON_CHARS = 256;

        public RejectedCandidate {
            Objects.requireNonNull(category, "category");
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

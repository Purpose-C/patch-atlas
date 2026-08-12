package io.github.patchatlas.run;

import java.util.Objects;

/**
 * Replay/验证投影：含 repository URL 与 revision(s)，可持有 Fixed SHA。
 *
 * <p>仅供 run 包 workspace 准备与 Replay 使用；<strong>不得</strong>传入 {@link
 * io.github.patchatlas.agent.TestGenerator}。
 */
public sealed interface ReplayWorkspaceProjection
        permits ReplayWorkspaceProjection.Live, ReplayWorkspaceProjection.Historical {

    VerificationMode mode();

    String repositoryUrl();

    String buggyRevision();

    String modulePath();

    /** Live：仅 Buggy/current 一侧。 */
    record Live(String repositoryUrl, String buggyRevision, String modulePath)
            implements ReplayWorkspaceProjection {
        public Live {
            Objects.requireNonNull(repositoryUrl, "repositoryUrl");
            Objects.requireNonNull(buggyRevision, "buggyRevision");
            Objects.requireNonNull(modulePath, "modulePath");
        }

        @Override
        public VerificationMode mode() {
            return VerificationMode.LIVE;
        }
    }

    /** Historical：Buggy + Fixed 两侧 revision。 */
    record Historical(
            String repositoryUrl, String buggyRevision, String fixedRevision, String modulePath)
            implements ReplayWorkspaceProjection {
        public Historical {
            Objects.requireNonNull(repositoryUrl, "repositoryUrl");
            Objects.requireNonNull(buggyRevision, "buggyRevision");
            Objects.requireNonNull(fixedRevision, "fixedRevision");
            Objects.requireNonNull(modulePath, "modulePath");
            if (buggyRevision.equals(fixedRevision)) {
                throw new IllegalArgumentException("buggy and fixed revisions must differ");
            }
        }

        @Override
        public VerificationMode mode() {
            return VerificationMode.HISTORICAL;
        }
    }
}

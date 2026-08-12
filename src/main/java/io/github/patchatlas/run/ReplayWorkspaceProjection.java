package io.github.patchatlas.run;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
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

    MavenExecutionPolicy executionPolicy();

    /** Live：仅 Buggy/current 一侧。 */
    record Live(
            String repositoryUrl,
            String buggyRevision,
            String modulePath,
            MavenExecutionPolicy executionPolicy)
            implements ReplayWorkspaceProjection {
        public Live {
            Objects.requireNonNull(repositoryUrl, "repositoryUrl");
            Objects.requireNonNull(buggyRevision, "buggyRevision");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(executionPolicy, "executionPolicy");
        }

        public Live(String repositoryUrl, String buggyRevision, String modulePath) {
            this(
                    repositoryUrl,
                    buggyRevision,
                    modulePath,
                    new MavenExecutionPolicy(
                            MavenExecutionPolicy.DEFAULT_JAVA_VERSION, MavenNetworkMode.OFFLINE));
        }

        @Override
        public VerificationMode mode() {
            return VerificationMode.LIVE;
        }
    }

    /** Historical：Buggy + Fixed 两侧 revision。 */
    record Historical(
            String repositoryUrl,
            String buggyRevision,
            String fixedRevision,
            String modulePath,
            MavenExecutionPolicy executionPolicy)
            implements ReplayWorkspaceProjection {
        public Historical {
            Objects.requireNonNull(repositoryUrl, "repositoryUrl");
            Objects.requireNonNull(buggyRevision, "buggyRevision");
            Objects.requireNonNull(fixedRevision, "fixedRevision");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(executionPolicy, "executionPolicy");
            if (buggyRevision.equals(fixedRevision)) {
                throw new IllegalArgumentException("buggy and fixed revisions must differ");
            }
        }

        public Historical(
                String repositoryUrl,
                String buggyRevision,
                String fixedRevision,
                String modulePath) {
            this(
                    repositoryUrl,
                    buggyRevision,
                    fixedRevision,
                    modulePath,
                    new MavenExecutionPolicy(
                            MavenExecutionPolicy.DEFAULT_JAVA_VERSION, MavenNetworkMode.OFFLINE));
        }

        @Override
        public VerificationMode mode() {
            return VerificationMode.HISTORICAL;
        }
    }
}

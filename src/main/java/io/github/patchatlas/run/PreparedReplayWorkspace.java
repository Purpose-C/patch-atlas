package io.github.patchatlas.run;

import io.github.patchatlas.replay.WorkspaceTrust;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 已 materialize 并应用 candidate 的一次性 Replay 工作区。
 *
 * <p>Live 单侧；Historical 双侧（独立目录，同一 candidate 已分别应用）。
 */
public sealed interface PreparedReplayWorkspace
        permits PreparedReplayWorkspace.Live, PreparedReplayWorkspace.Historical {

    String modulePath();

    MavenNetworkMode networkMode();

    record Live(Path workspace, String modulePath, MavenNetworkMode networkMode)
            implements PreparedReplayWorkspace {
        public Live {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(networkMode, "networkMode");
        }
    }

    record Historical(
            Path buggyWorkspace,
            Path fixedWorkspace,
            String modulePath,
            MavenNetworkMode networkMode)
            implements PreparedReplayWorkspace {
        public Historical {
            Objects.requireNonNull(buggyWorkspace, "buggyWorkspace");
            Objects.requireNonNull(fixedWorkspace, "fixedWorkspace");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(networkMode, "networkMode");
            WorkspaceTrust.requireDistinctWorkspaces(buggyWorkspace, fixedWorkspace);
        }
    }
}

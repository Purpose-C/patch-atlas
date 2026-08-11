package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenTestCommand;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Historical Verification 请求：必须同时具备 Buggy 与 Fixed 工作区。
 *
 * <p>与 {@link LiveReplayRequest} 类型分离；构造时拒绝指向同一真实目录（含符号链接别名）。
 */
public record HistoricalReplayRequest(
        Path buggyWorkspace, Path fixedWorkspace, MavenTestCommand command, TargetTest targetTest) {

    public HistoricalReplayRequest {
        Objects.requireNonNull(buggyWorkspace, "buggyWorkspace");
        Objects.requireNonNull(fixedWorkspace, "fixedWorkspace");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(targetTest, "targetTest");
        WorkspaceTrust.requireDistinctWorkspaces(buggyWorkspace, fixedWorkspace);
    }
}

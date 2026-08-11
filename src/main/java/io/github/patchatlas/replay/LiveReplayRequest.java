package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenTestCommand;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Live Issue 请求：仅有当前缺陷工作区；类型上不存在 Fixed 工作区。
 */
public record LiveReplayRequest(Path workspace, MavenTestCommand command, TargetTest targetTest) {

    public LiveReplayRequest {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(targetTest, "targetTest");
    }
}

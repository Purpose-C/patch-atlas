package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayResult;

/**
 * Replay 执行 seam：接收<strong>已准备</strong>的 workspace（含已应用的 candidate）。
 *
 * <p>调用方（{@link Issue2TestWorker}）负责从持久化 URL/SHA 新建工作区并再次 Gate 应用
 * 同一 candidate；replayer 不得假设宿主上残留旧 workspace。
 */
@FunctionalInterface
public interface RunReplayer {

    ReplayResult replay(
            ClaimedRun claimed,
            PersistedCandidatePatch candidate,
            PreparedReplayWorkspace workspace);
}

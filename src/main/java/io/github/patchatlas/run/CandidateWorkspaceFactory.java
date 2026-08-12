package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.file.Path;

/**
 * 为 Patch Gate / Replay 提供一次性 workspace（位于可信根下）。
 *
 * <p>{@link #openForRevision} 可按任意完整 SHA materialize（Buggy 或 Fixed）；生成阶段继续用
 * {@link #open(ClaimedRun, GenerationInput)}，仅暴露 Buggy 侧。
 */
public interface CandidateWorkspaceFactory {

    /**
     * 按精确 revision materialize 新目录。
     *
     * @param revision 完整 40 位 SHA（Buggy 或 Fixed）
     */
    WorkspaceSession openForRevision(
            ClaimedRun run, String repositoryUrl, String revision, String modulePath)
            throws Exception;

    /** 生成阶段：仅 Buggy revision（来自 {@link GenerationInput}，无 Fixed）。 */
    default WorkspaceSession open(ClaimedRun run, GenerationInput input) throws Exception {
        String modulePath = input.generatorContext().modulePath() == null
                ? ""
                : input.generatorContext().modulePath();
        return openForRevision(
                run,
                input.generatorContext().repositoryUrl(),
                input.generatorContext().buggyRevision(),
                modulePath);
    }

    interface WorkspaceSession extends AutoCloseable {
        Path workspace();

        String modulePath();

        MavenNetworkMode networkMode();

        @Override
        void close();
    }
}

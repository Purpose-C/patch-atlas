package io.github.patchatlas.run;

import java.nio.file.Path;

/**
 * 将持久化的 repository URL + Buggy SHA 物化为本地 workspace（clone/checkout）。
 *
 * <p>生产使用真实 {@link io.github.patchatlas.repository.RepositoryCloner}；测试可注入本地
 * fixture fetcher，避免默认 CI 依赖外网。
 */
@FunctionalInterface
public interface RepositoryWorkspaceFetcher {

    /**
     * 在 {@code parentDir/directoryName} 创建全新仓库工作区并 checkout 到 {@code buggyRevision}。
     *
     * @param directoryName 单段安全目录名（不得已存在）
     * @return 工作区根路径（git work tree）
     */
    Path materialize(
            String repositoryUrl, String buggyRevision, Path parentDir, String directoryName)
            throws Exception;
}

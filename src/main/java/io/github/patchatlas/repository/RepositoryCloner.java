package io.github.patchatlas.repository;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;

/**
 * 匿名克隆公开 HTTPS 仓库到 gitignored 临时工作区。
 *
 * <p>只读事实层:不写第三方源码进 PatchAtlas Git 历史,不携带私有凭据。
 */
public class RepositoryCloner {

    private static final String SAFE_DIRECTORY_NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    /**
     * 克隆到 {@code parentDir/directoryName}。调用方应把 parentDir 放在
     * {@code samples/} 或 {@code java.io.tmpdir} 下已被 ignore 的路径。
     */
    public CloneResult clonePublic(String repositoryUrl, Path parentDir, String directoryName) {
        if (parentDir == null) {
            return new CloneResult.RejectedInput("parentDir is required");
        }
        if (directoryName == null || !directoryName.matches(SAFE_DIRECTORY_NAME)) {
            return new CloneResult.RejectedInput("directoryName must be one safe path segment");
        }
        if (!isSupportedRepositoryUrl(repositoryUrl)) {
            return new CloneResult.RejectedInput(
                    "repositoryUrl must be an anonymous public GitHub HTTPS repository URL");
        }

        Path root = parentDir.toAbsolutePath().normalize();
        Path target = root.resolve(directoryName).normalize();
        if (!target.startsWith(root)) {
            return new CloneResult.RejectedInput("clone target must stay inside parentDir");
        }
        Instant started = Instant.now();
        try {
            Files.createDirectories(root);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return new CloneResult.RejectedInput("clone target already exists");
            }
            try (Git ignored = Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(target.toFile())
                    .setCloneAllBranches(false)
                    .call()) {
                return new CloneResult.Success(target, Duration.between(started, Instant.now()));
            }
        } catch (TransportException ex) {
            return new CloneResult.Unreachable(repositoryUrl, ex.getMessage());
        } catch (GitAPIException | java.io.IOException ex) {
            return new CloneResult.Unreachable(repositoryUrl, ex.getMessage());
        }
    }

    private boolean isSupportedRepositoryUrl(String repositoryUrl) {
        return RepositoryUrls.isAnonymousGithubHttps(repositoryUrl);
    }
}

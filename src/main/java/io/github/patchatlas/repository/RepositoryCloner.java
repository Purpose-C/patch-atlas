package io.github.patchatlas.repository;

import java.net.URI;
import java.net.URISyntaxException;
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
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return false;
        }

        try {
            URI uri = new URI(repositoryUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return false;
            }

            String path = uri.getPath();
            if (path == null
                    || path.isEmpty()
                    || path.endsWith("/")
                    || !path.equals(uri.getRawPath())) {
                return false;
            }
            String[] segments = path.substring(1).split("/", -1);
            if (segments.length != 2) {
                return false;
            }
            String repositoryName = segments[1].endsWith(".git")
                    ? segments[1].substring(0, segments[1].length() - 4)
                    : segments[1];
            return isSafeGithubSegment(segments[0]) && isSafeGithubSegment(repositoryName);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private boolean isSafeGithubSegment(String segment) {
        return !segment.equals(".")
                && !segment.equals("..")
                && segment.matches("[A-Za-z0-9_.-]+");
    }
}

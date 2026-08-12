package io.github.patchatlas.run;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每次 open 创建<strong>唯一新目录</strong>，并从 repository URL + 精确 SHA materialize 仓库。
 *
 * <p>不复用 {@code runId} 目录。session close 时仅清理位于可信根下的路径；越界只拒绝不删除。
 */
public final class TempCandidateWorkspaceFactory implements CandidateWorkspaceFactory {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final Path allowedRoot;
    private final RepositoryWorkspaceFetcher fetcher;
    private final MavenNetworkMode networkMode;

    public TempCandidateWorkspaceFactory(Path allowedRoot) {
        this(allowedRoot, new GitCloneWorkspaceFetcher(), MavenNetworkMode.OFFLINE);
    }

    public TempCandidateWorkspaceFactory(Path allowedRoot, RepositoryWorkspaceFetcher fetcher) {
        this(allowedRoot, fetcher, MavenNetworkMode.OFFLINE);
    }

    public TempCandidateWorkspaceFactory(
            Path allowedRoot, RepositoryWorkspaceFetcher fetcher, MavenNetworkMode networkMode) {
        this.allowedRoot =
                Objects.requireNonNull(allowedRoot, "allowedRoot").toAbsolutePath().normalize();
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.networkMode = Objects.requireNonNull(networkMode, "networkMode");
        if (!Files.isDirectory(this.allowedRoot)) {
            throw new IllegalArgumentException("workspace root must be an existing directory");
        }
    }

    @Override
    public WorkspaceSession openForRevision(
            ClaimedRun run, String repositoryUrl, String revision, String modulePath)
            throws Exception {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(modulePath, "modulePath");

        String directoryName = uniqueDirectoryName(run.runId());
        Path workspace =
                fetcher.materialize(repositoryUrl, revision, allowedRoot, directoryName)
                        .toAbsolutePath()
                        .normalize();
        if (!workspace.startsWith(allowedRoot)) {
            throw new IllegalStateException(
                    "materialized workspace escaped allowed root: " + workspace);
        }

        return new WorkspaceSession() {
            @Override
            public Path workspace() {
                return workspace;
            }

            @Override
            public String modulePath() {
                return modulePath;
            }

            @Override
            public MavenNetworkMode networkMode() {
                return networkMode;
            }

            @Override
            public void close() {
                if (workspace.startsWith(allowedRoot)) {
                    deleteRecursivelyQuietly(workspace);
                }
            }
        };
    }

    static String uniqueDirectoryName(java.util.UUID runId) {
        String runPart = runId.toString().replace("-", "");
        long seq = SEQUENCE.incrementAndGet();
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "w" + runPart + "n" + seq + rand;
    }

    static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                                throws IOException {
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            // 尽力清理
        }
    }
}

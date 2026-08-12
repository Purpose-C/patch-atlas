package io.github.patchatlas.run;

import io.github.patchatlas.repository.CloneResult;
import io.github.patchatlas.repository.CommitId;
import io.github.patchatlas.repository.RepositoryCloner;
import io.github.patchatlas.repository.RevisionCheckResult;
import io.github.patchatlas.repository.RevisionValidator;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;

/**
 * 公开 GitHub HTTPS clone + 硬重置到精确 Buggy SHA。
 */
public final class GitCloneWorkspaceFetcher implements RepositoryWorkspaceFetcher {

    private final RepositoryCloner cloner;
    private final RevisionValidator revisionValidator;

    public GitCloneWorkspaceFetcher() {
        this(new RepositoryCloner(), new RevisionValidator());
    }

    public GitCloneWorkspaceFetcher(RepositoryCloner cloner, RevisionValidator revisionValidator) {
        this.cloner = Objects.requireNonNull(cloner, "cloner");
        this.revisionValidator = Objects.requireNonNull(revisionValidator, "revisionValidator");
    }

    @Override
    public Path materialize(
            String repositoryUrl, String buggyRevision, Path parentDir, String directoryName)
            throws Exception {
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(buggyRevision, "buggyRevision");
        Objects.requireNonNull(parentDir, "parentDir");
        Objects.requireNonNull(directoryName, "directoryName");

        String sha = new CommitId(buggyRevision).sha();
        CloneResult cloneResult = cloner.clonePublic(repositoryUrl, parentDir, directoryName);
        if (!(cloneResult instanceof CloneResult.Success success)) {
            String reason =
                    switch (cloneResult) {
                        case CloneResult.RejectedInput rejected -> rejected.reason();
                        case CloneResult.Unreachable unreachable -> unreachable.reason();
                        case CloneResult.Success ignored -> "unreachable";
                    };
            throw new IllegalStateException("workspace clone failed: " + reason);
        }

        Path workDir = success.workDir();
        hardResetTo(workDir, sha);

        RevisionCheckResult check = revisionValidator.check(workDir.toFile(), sha);
        if (!(check instanceof RevisionCheckResult.Found found) || !found.commitId().sha().equals(sha)) {
            throw new IllegalStateException("buggy revision not present after checkout: " + sha);
        }
        return workDir;
    }

    static void hardResetTo(Path repositoryDir, String fullSha) throws Exception {
        try (Git git = Git.open(repositoryDir.toFile())) {
            ObjectId objectId = git.getRepository().resolve(fullSha);
            if (objectId == null) {
                throw new IllegalStateException("revision not found in clone: " + fullSha);
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(fullSha).call();
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null || !head.getName().equals(fullSha)) {
                throw new IllegalStateException(
                        "HEAD is not buggy revision after reset: expected " + fullSha);
            }
        }
    }
}

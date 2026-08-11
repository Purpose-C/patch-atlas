package io.github.patchatlas.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RevisionValidatorTest {

    /**
     * 格式合法(40 位十六进制)但几乎不可能存在的 SHA。
     * 固定值而非随机:测试必须可复现。不用全 0,因为全零在 git 里是 null object id,有特殊含义。
     */
    private static final String MISSING_REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void reportsNotFoundWhenRevisionIsAbsentFromRepository(@TempDir Path workspace) throws Exception {
        Path repository = createRepositoryWithOneCommit(workspace);

        RevisionCheckResult result = new RevisionValidator().check(repository.toFile(), MISSING_REVISION);

        assertThat(result).isEqualTo(new RevisionCheckResult.NotFound(MISSING_REVISION));
    }

    @Test
    void reportsFoundWhenRevisionExists(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("sample-repo");
        RevCommit commit;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("README.md"), "fixture\n");
            git.add().addFilepattern("README.md").call();
            commit = git.commit()
                    .setMessage("initial commit")
                    .setAuthor("PatchAtlas Test", "test@example.com")
                    .setCommitter("PatchAtlas Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        RevisionCheckResult result =
                new RevisionValidator().check(repository.toFile(), commit.getName());

        assertThat(result).isEqualTo(new RevisionCheckResult.Found(new CommitId(commit.getName())));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "HEAD",
                "main",
                "v1.0.0",
                "0123456",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
            })
    void rejectsRevisionThatIsNotAFullObjectId(String revision, @TempDir Path workspace) throws Exception {
        Path repository = createRepositoryWithOneCommit(workspace);

        RevisionCheckResult result = new RevisionValidator().check(repository.toFile(), revision);

        assertThat(result).isEqualTo(new RevisionCheckResult.InvalidRevision());
    }

    @Test
    void rejectsExistingGitObjectThatIsNotACommit(@TempDir Path workspace) throws Exception {
        Path repository = createRepositoryWithOneCommit(workspace);
        String treeId;
        try (Git git = Git.open(repository.toFile())) {
            treeId = git.getRepository().resolve("HEAD^{tree}").getName();
        }

        RevisionCheckResult result = new RevisionValidator().check(repository.toFile(), treeId);

        assertThat(result).isEqualTo(new RevisionCheckResult.NotCommit(treeId));
    }

    @Test
    void reportsRepositoryUnreadableWhenPathIsNotAGitRepo(@TempDir Path workspace) {
        Path notARepo = workspace.resolve("empty");
        notARepo.toFile().mkdirs();

        RevisionCheckResult result = new RevisionValidator().check(notARepo.toFile(), MISSING_REVISION);

        assertThat(result).isInstanceOf(RevisionCheckResult.RepositoryUnreadable.class);
    }

    private Path createRepositoryWithOneCommit(Path workspace) throws Exception {
        Path repository = workspace.resolve("sample-repo");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("README.md"), "fixture\n");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("initial commit")
                    .setAuthor("PatchAtlas Test", "test@example.com")
                    .setCommitter("PatchAtlas Test", "test@example.com")
                    .setSign(false)
                    .call();
        }
        return repository;
    }
}

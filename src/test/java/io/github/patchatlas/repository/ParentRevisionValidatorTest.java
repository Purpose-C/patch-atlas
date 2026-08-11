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

class ParentRevisionValidatorTest {

    private static final String MISSING = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void reportsMatchWhenFixedParentEqualsBuggy(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit buggy;
        RevCommit fixed;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            buggy = commit(git, "buggy");

            Files.writeString(repository.resolve("a.txt"), "2\n");
            git.add().addFilepattern("a.txt").call();
            fixed = commit(git, "fixed");
        }

        ParentRevisionCheckResult result = new ParentRevisionValidator()
                .check(repository.toFile(), buggy.getName(), fixed.getName());

        assertThat(result)
                .isEqualTo(new ParentRevisionCheckResult.Match(
                        new CommitId(buggy.getName()), new CommitId(fixed.getName())));
    }

    @Test
    void reportsParentMismatchWhenFixedParentIsDifferent(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit first;
        RevCommit other;
        RevCommit fixed;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            first = commit(git, "first");

            Files.writeString(repository.resolve("a.txt"), "other\n");
            git.add().addFilepattern("a.txt").call();
            other = commit(git, "other");

            Files.writeString(repository.resolve("a.txt"), "fixed\n");
            git.add().addFilepattern("a.txt").call();
            fixed = commit(git, "fixed");
        }

        // claim buggy=first, but fixed^ is actually "other"
        ParentRevisionCheckResult result = new ParentRevisionValidator()
                .check(repository.toFile(), first.getName(), fixed.getName());

        assertThat(result).isInstanceOf(ParentRevisionCheckResult.ParentMismatch.class);
        ParentRevisionCheckResult.ParentMismatch mismatch =
                (ParentRevisionCheckResult.ParentMismatch) result;
        assertThat(mismatch.expectedBuggy().sha()).isEqualTo(first.getName());
        assertThat(mismatch.actualParent().sha()).isEqualTo(other.getName());
    }

    @Test
    void reportsRevisionMissingWhenShaAbsent(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit only;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            only = commit(git, "only");
        }

        ParentRevisionCheckResult result =
                new ParentRevisionValidator().check(repository.toFile(), only.getName(), MISSING);

        assertThat(result).isEqualTo(new ParentRevisionCheckResult.RevisionMissing("fixed", MISSING));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"HEAD", "main", "v1.0.0", "0123456"})
    void rejectsBuggyRevisionThatIsNotAFullObjectId(String revision, @TempDir Path workspace)
            throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit commit;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            commit = commit(git, "only");
        }

        ParentRevisionCheckResult result =
                new ParentRevisionValidator().check(repository.toFile(), revision, commit.getName());

        assertThat(result).isEqualTo(new ParentRevisionCheckResult.InvalidRevision("buggy"));
    }

    @Test
    void rejectsFixedRevisionThatIsNotAFullObjectId(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit commit;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            commit = commit(git, "only");
        }

        ParentRevisionCheckResult result =
                new ParentRevisionValidator().check(repository.toFile(), commit.getName(), "HEAD");

        assertThat(result).isEqualTo(new ParentRevisionCheckResult.InvalidRevision("fixed"));
    }

    @Test
    void rejectsFixedRevisionThatPointsToTreeObject(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("repo");
        RevCommit commit;
        String treeId;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("a.txt"), "1\n");
            git.add().addFilepattern("a.txt").call();
            commit = commit(git, "only");
            treeId = git.getRepository().resolve("HEAD^{tree}").getName();
        }

        ParentRevisionCheckResult result =
                new ParentRevisionValidator().check(repository.toFile(), commit.getName(), treeId);

        assertThat(result).isEqualTo(new ParentRevisionCheckResult.NotCommit("fixed", treeId));
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        return git.commit()
                .setMessage(message)
                .setAuthor("PatchAtlas Test", "test@example.com")
                .setCommitter("PatchAtlas Test", "test@example.com")
                .setSign(false)
                .call();
    }
}

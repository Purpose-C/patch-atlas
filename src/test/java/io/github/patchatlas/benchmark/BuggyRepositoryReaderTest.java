package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuggyRepositoryReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsOnlyCommittedJavaBlobsAtExpectedHead() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        String revision;
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve("src"));
            Files.writeString(repo.resolve("src/A.java"), "class A {}", StandardCharsets.UTF_8);
            Files.writeString(repo.resolve("README.md"), "ignore", StandardCharsets.UTF_8);
            Files.writeString(
                    repo.resolve("src/Huge.java"),
                    "x".repeat(SourceSnapshot.MAX_CONTENT_BYTES + 1),
                    StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            var who = new PersonIdent("fixture", "fixture@example.com");
            revision = git.commit()
                    .setMessage("fixture")
                    .setAuthor(who)
                    .setCommitter(who)
                    .call()
                    .getName();
        }

        var files = new BuggyRepositoryReader().readJavaFiles(repo, revision);

        assertThat(files).extracting(BuggyOnlyGeneratorContextBuilder.BuggyFile::relativePath)
                .containsExactly("src/A.java", "src/Huge.java");
        assertThat(files.getFirst().content()).isEqualTo("class A {}");
        assertThat(files.getFirst().blobId()).matches("[0-9a-f]{40}");
        assertThat(files.get(1).content().getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(SourceSnapshot.MAX_CONTENT_BYTES);
    }
}

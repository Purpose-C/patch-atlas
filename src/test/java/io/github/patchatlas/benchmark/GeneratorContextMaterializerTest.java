package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import io.github.patchatlas.repository.CaseManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratorContextMaterializerTest {

    private static final String CONTENT = "class Foo {}";
    private static final String TAMPERED = "class Fox {}";
    private static final String REPOSITORY_URL = "https://github.com/ex/repo.git";

    @TempDir
    Path tempDir;

    @Test
    void materializeReturnsSnapshotsMatchingFrozenSources() throws Exception {
        Fixture fixture = writeRepo(tempDir.resolve("happy"), CONTENT);
        GeneratorContextMaterializer materializer = materializer(fixture.workspace());

        List<SourceSnapshot> snapshots = materializer.materialize(
                context(fixture, CONTENT), REPOSITORY_URL, "case-1");

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().relativePath()).isEqualTo("src/main/java/Foo.java");
        assertThat(snapshots.getFirst().content()).isEqualTo(CONTENT);
    }

    @Test
    void selectFromIssueUsesBuggyOnlyBuilderOnIssueMentions() throws Exception {
        Fixture fixture = writeRepo(tempDir.resolve("issue-ctx"), CONTENT);
        GeneratorContextMaterializer materializer = materializer(fixture.workspace());
        var generatorContext = new CaseManifest.GeneratorContext(
                "case-1",
                REPOSITORY_URL,
                "MIT",
                "https://github.com/ex/repo/issues/1",
                fixture.revision(),
                "",
                "17");

        List<SourceSnapshot> snapshots = materializer.selectFromIssue(
                generatorContext, "Foo fails", "See src/main/java/Foo.java");

        assertThat(snapshots).extracting(SourceSnapshot::relativePath)
                .containsExactly("src/main/java/Foo.java");
        assertThat(snapshots.getFirst().content()).isEqualTo(CONTENT);
    }

    @Test
    void materializeRejectsSingleByteContentTamper() throws Exception {
        Fixture fixture = writeRepo(tempDir.resolve("tampered"), TAMPERED);
        GeneratorContextMaterializer materializer = materializer(fixture.workspace());

        assertThatThrownBy(() -> materializer.materialize(
                        context(fixture, CONTENT), REPOSITORY_URL, "case-1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("contentSha256");
    }

    private static GeneratorContextMaterializer materializer(Path workspace) {
        BenchmarkGitWorkspace.CommandRunner runner = (command, timeout) -> {
            try {
                if (command.contains("clone")) {
                    Files.createDirectories(Path.of(command.getLast()));
                } else if (command.contains("worktree")) {
                    Path target = Path.of(command.get(command.size() - 2));
                    copyDirectory(workspace, target);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            return new BenchmarkGitWorkspace.CommandResult(0, false, "");
        };
        return new GeneratorContextMaterializer(
                new BenchmarkGitWorkspace(workspace.getParent().resolve("cache"), runner),
                new BuggyRepositoryReader());
    }

    private static GeneratorContextMetadata context(Fixture fixture, String expectedContent) {
        return new GeneratorContextMetadata(
                "case-1",
                "https://github.com/ex/repo/issues/1",
                "a".repeat(64),
                fixture.revision(),
                List.of(new SourceReference(
                        "src/main/java/Foo.java",
                        fixture.blobId(),
                        BenchmarkArtifacts.sha256(expectedContent),
                        "ISSUE_PATH_MATCH")),
                List.of());
    }

    private static Fixture writeRepo(Path repo, String content) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/Foo.java"), content, StandardCharsets.UTF_8);
        String revision;
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            PersonIdent who = new PersonIdent(
                    "fixture", "fixture@example.com", Instant.EPOCH, ZoneOffset.UTC);
            revision = git.commit()
                    .setMessage("fixture")
                    .setAuthor(who)
                    .setCommitter(who)
                    .call()
                    .getName();
        }
        var files = new BuggyRepositoryReader().readJavaFiles(repo, revision);
        assertThat(files).hasSize(1);
        return new Fixture(repo, revision, files.getFirst().blobId());
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.walk(source).forEach(path -> {
            try {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private record Fixture(Path workspace, String revision, String blobId) {}
}

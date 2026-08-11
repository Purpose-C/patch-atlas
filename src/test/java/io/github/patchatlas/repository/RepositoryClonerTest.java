package io.github.patchatlas.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryClonerTest {

    @Test
    void rejectsDirectoryTraversalBeforeCloning(@TempDir Path workspace) {
        CloneResult result = new RepositoryCloner()
                .clonePublic("https://github.com/Purpose-C/patch-atlas.git", workspace, "../outside");

        assertThat(result).isInstanceOf(CloneResult.RejectedInput.class);
        assertThat(workspace.getParent().resolve("outside")).doesNotExist();
    }

    @Test
    void rejectsAbsoluteCloneDirectory(@TempDir Path workspace) {
        CloneResult result = new RepositoryCloner()
                .clonePublic(
                        "https://github.com/Purpose-C/patch-atlas.git",
                        workspace,
                        workspace.resolve("outside").toString());

        assertThat(result).isInstanceOf(CloneResult.RejectedInput.class);
        assertThat(workspace.resolve("outside")).doesNotExist();
    }

    @Test
    void rejectsExistingCloneTarget(@TempDir Path workspace) throws Exception {
        Path existingTarget = Files.createDirectory(workspace.resolve("repo"));
        Files.writeString(existingTarget.resolve("keep.txt"), "user data");

        CloneResult result = new RepositoryCloner()
                .clonePublic("https://github.com/Purpose-C/patch-atlas.git", workspace, "repo");

        assertThat(result).isInstanceOf(CloneResult.RejectedInput.class);
        assertThat(existingTarget.resolve("keep.txt")).hasContent("user data");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "git@github.com:example/repo.git",
                "http://github.com/example/repo.git",
                "https://gitlab.com/example/repo.git",
                "https://user:secret@github.com/example/repo.git",
                "https://github.com:443/example/repo.git",
                "https://github.com/example/repo.git?ref=main",
                "https://github.com/example/repo.git#readme",
                " ",
                "https://github.com",
                "https://github.com/",
                "https://github.com/example/repo/extra",
                "https://github.com/example/%2e%2e"
            })
    void rejectsUrlsOutsidePublicGithubHttpsBoundaryWithoutTouchingNetwork(
            String repositoryUrl, @TempDir Path workspace) {
        CloneResult result = new RepositoryCloner()
                .clonePublic(repositoryUrl, workspace, "repo");

        assertThat(result).isInstanceOf(CloneResult.RejectedInput.class);
        assertThat(workspace.resolve("repo")).doesNotExist();
    }
}

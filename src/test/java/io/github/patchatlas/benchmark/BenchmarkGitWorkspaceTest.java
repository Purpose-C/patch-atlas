package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkGitWorkspaceTest {

    @TempDir
    Path cache;

    @Test
    void clonesBareCacheAndCreatesDetachedExactRevisionWorktree() {
        List<List<String>> commands = new ArrayList<>();
        BenchmarkGitWorkspace.CommandRunner runner = (command, timeout) -> {
            commands.add(command);
            try {
                if (command.contains("clone")) {
                    Files.createDirectories(Path.of(command.getLast()));
                } else if (command.contains("worktree")) {
                    Files.createDirectories(Path.of(command.get(command.size() - 2)));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new BenchmarkGitWorkspace.CommandResult(0, false, "");
        };
        BenchmarkGitWorkspace workspaces = new BenchmarkGitWorkspace(cache, runner);

        var result = workspaces.checkout(
                "https://github.com/Org/Repo.git", "a".repeat(40), "Org-Repo-aaaaaaaaaaaa");

        assertThat(result).isInstanceOf(BenchmarkGitWorkspace.CheckoutResult.Success.class);
        assertThat(commands).hasSize(2);
        assertThat(commands.getFirst())
                .containsExactly(
                        "git",
                        "clone",
                        "--bare",
                        "--filter=blob:none",
                        "https://github.com/Org/Repo.git",
                        cache.resolve("repos/Org-Repo.git").toString());
        assertThat(commands.get(1)).contains("worktree", "add", "--detach", "a".repeat(40));
    }

    @Test
    void rejectsCredentialedUrlAndInvalidRevisionBeforeStartingGit() {
        BenchmarkGitWorkspace workspaces = new BenchmarkGitWorkspace(
                cache,
                (command, timeout) -> {
                    throw new AssertionError("git must not run");
                });

        assertThatThrownBy(() -> workspaces.checkout(
                        "https://user:secret@github.com/Org/Repo.git",
                        "a".repeat(40),
                        "case"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workspaces.checkout(
                        "https://github.com/Org/Repo.git", "main", "case"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

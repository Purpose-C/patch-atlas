package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxRunnerTest {

    @Test
    void executesHardenedOfflineContainerAndReturnsFacts(@TempDir Path workspace) throws Exception {
        FakeCommandExecutor executor = new FakeCommandExecutor(
                completed(0, "Docker ready"),
                completed(0, "image ready"),
                completed(7, "test failed"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(execution.exitCode()).isEqualTo(7);
        assertThat(execution.timedOut()).isFalse();
        assertThat(execution.logSummary()).isEqualTo("test failed");
        assertThat(execution.networkMode()).isEqualTo(MavenNetworkMode.OFFLINE);
        assertThat(execution.image()).isEqualTo("maven:3.9-eclipse-temurin-21");
        assertThat(execution.limits()).isEqualTo(new SandboxLimits(1.0, 1_073_741_824L, 256));

        assertThat(execution.command())
                .isEqualTo(List.of(
                        "mvn",
                        "-B",
                        "-Dmaven.repo.local=/maven-cache/repository",
                        "-o",
                        "-Dtest=fixtures.StringUtilsTest",
                        "test"));

        List<String> dockerCommand = executor.commands.get(2);
        assertThat(dockerCommand)
                .containsSubsequence("docker", "run", "--rm", "--name", "patch-atlas-test");
        assertThat(dockerCommand).contains("--user", "501:20");
        assertThat(dockerCommand).contains(
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--cpus=1.0",
                "--memory=1073741824",
                "--pids-limit=256",
                "HOME=/tmp",
                "MAVEN_CONFIG=/maven-cache/config",
                "--network=none",
                workspace.toRealPath() + ":/workspace:rw",
                "maven:3.9-eclipse-temurin-21");
        assertThat(dockerCommand).containsSubsequence(
                "mvn",
                "-B",
                "-Dmaven.repo.local=/maven-cache/repository",
                "-o",
                "-Dtest=fixtures.StringUtilsTest",
                "test");
        String home = Path.of(System.getProperty("user.home")).toRealPath().toString();
        assertThat(dockerCommand)
                .doesNotContain(home + ":/workspace:rw", home + ":/maven-cache:rw");
        assertThat(dockerCommand).noneMatch(argument -> argument.contains("docker.sock"));
        assertThat(dockerCommand).noneMatch(argument -> argument.contains("OPENAI"));
    }

    @Test
    void recordsExplicitOnlineExecution(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor(
                completed(0, "Docker ready"),
                completed(0, "image ready"),
                completed(0, "BUILD SUCCESS"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.ONLINE));

        assertThat(execution.networkMode()).isEqualTo(MavenNetworkMode.ONLINE);
        assertThat(executor.commands.get(2))
                .contains("--network=bridge")
                .doesNotContain("--network=none");
    }

    @Test
    void reportsDockerUnavailableBeforeBuildingContainerCommand(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor(completed(1, "daemon unavailable"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.DOCKER_UNAVAILABLE);
        assertThat(execution.exitCode()).isEqualTo(1);
        assertThat(execution.logSummary()).isEqualTo("daemon unavailable");
        assertThat(executor.commands).hasSize(1);
    }

    @Test
    void reportsImageUnavailableWhenPullFails(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor(
                completed(0, "Docker ready"),
                completed(1, "image missing"),
                completed(1, "pull denied"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.IMAGE_UNAVAILABLE);
        assertThat(execution.logSummary()).isEqualTo("pull denied");
        assertThat(executor.commands.get(2))
                .isEqualTo(List.of("docker", "pull", "maven:3.9-eclipse-temurin-21"));
    }

    @Test
    void rejectsMissingWorkspaceWithoutInvokingDocker(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                workspace.resolve("missing"),
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.WORKSPACE_UNAVAILABLE);
        assertThat(execution.command()).isEmpty();
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void rejectsWorkspaceOutsideConfiguredRoot(@TempDir Path workspaceRoot) throws Exception {
        Path allowed = Files.createDirectory(workspaceRoot.resolve("allowed"));
        Path outside = Files.createDirectory(workspaceRoot.resolve("outside"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        DockerSandboxRunner runner = runner(allowed, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                outside,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.WORKSPACE_UNAVAILABLE);
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void reportsUnwritableCacheBoundaryWithoutInvokingDocker(@TempDir Path workspace)
            throws Exception {
        Path cacheRoot = Files.createDirectory(workspace.resolve(".patch-atlas-cache"));
        Path cacheFile = Files.writeString(cacheRoot.resolve("cache-file"), "not a directory");
        FakeCommandExecutor executor = new FakeCommandExecutor();
        DockerSandboxConfig config = new DockerSandboxConfig(
                "maven:3.9-eclipse-temurin-21",
                Duration.ofSeconds(5),
                64 * 1024,
                workspace,
                cacheFile,
                SandboxLimits.defaults());
        DockerSandboxRunner runner =
                new DockerSandboxRunner(config, executor, ignored -> "501:20", () -> "unused");

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.CACHE_UNAVAILABLE);
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void rejectsCacheSymlinkThatEscapesDedicatedDirectory(@TempDir Path workspace)
            throws Exception {
        Path cacheRoot = Files.createDirectory(workspace.resolve(".patch-atlas-cache"));
        Path outside = Files.createDirectory(workspace.resolve("outside-cache"));
        Path cacheLink = cacheRoot.resolve("maven");
        Files.createSymbolicLink(cacheLink, outside);
        FakeCommandExecutor executor = new FakeCommandExecutor();
        DockerSandboxConfig config = new DockerSandboxConfig(
                "maven:3.9-eclipse-temurin-21",
                Duration.ofSeconds(5),
                64 * 1024,
                workspace,
                cacheLink,
                SandboxLimits.defaults());
        DockerSandboxRunner runner =
                new DockerSandboxRunner(config, executor, ignored -> "501:20", () -> "unused");

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.CACHE_UNAVAILABLE);
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void preservesTimedOutFactWhenDockerPreflightTimesOut(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor(timedOut("docker info timed out"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofSeconds(5));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.DOCKER_UNAVAILABLE);
        assertThat(execution.timedOut()).isTrue();
    }

    @Test
    void forceRemovesContainerAfterTimeout(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor(
                completed(0, "Docker ready"),
                completed(0, "image ready"),
                timedOut("partial output"),
                completed(0, "removed"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofMillis(100));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.TIMED_OUT);
        assertThat(execution.timedOut()).isTrue();
        assertThat(execution.exitCode()).isNull();
        assertThat(executor.commands.get(3))
                .isEqualTo(List.of("docker", "rm", "-f", "patch-atlas-test"));
    }

    @Test
    void reportsCleanupFailureSeparatelyFromExecutionTimeout(@TempDir Path workspace) {
        FakeCommandExecutor executor = new FakeCommandExecutor(
                completed(0, "Docker ready"),
                completed(0, "image ready"),
                timedOut("partial output"),
                completed(1, "cannot remove container"));
        DockerSandboxRunner runner = runner(workspace, executor, Duration.ofMillis(100));

        SandboxExecution execution = runner.execute(
                workspace,
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE));

        assertThat(execution.status()).isEqualTo(SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED);
        assertThat(execution.timedOut()).isTrue();
    }

    private static DockerSandboxRunner runner(
            Path workspace, FakeCommandExecutor executor, Duration timeout) {
        DockerSandboxConfig config = new DockerSandboxConfig(
                "maven:3.9-eclipse-temurin-21",
                timeout,
                64 * 1024,
                workspace,
                workspace.resolve(".patch-atlas-cache"),
                new SandboxLimits(1.0, 1_073_741_824L, 256));
        return new DockerSandboxRunner(config, executor, ignored -> "501:20", () -> "patch-atlas-test");
    }

    private static CommandExecution completed(int exitCode, String output) {
        return new CommandExecution(exitCode, Duration.ofMillis(10), false, output, null);
    }

    private static CommandExecution timedOut(String output) {
        return new CommandExecution(null, Duration.ofMillis(100), true, output, null);
    }

    private static final class FakeCommandExecutor implements CommandExecutor {

        private final Deque<CommandExecution> results;
        private final List<List<String>> commands = new ArrayList<>();

        private FakeCommandExecutor(CommandExecution... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public CommandExecution execute(List<String> command, Duration timeout, int maxOutputBytes) {
            commands.add(List.copyOf(command));
            return results.removeFirst();
        }
    }
}

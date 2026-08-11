package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("docker")
class DockerSandboxRunnerIntegrationTest {

    private static final String IMAGE = "maven:3.9-eclipse-temurin-21";

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void warmsCacheRunsFixtureOfflineAndRemovesTimedOutContainer() throws Exception {
        Path workspace = Path.of("fixtures/off-by-one").toRealPath();
        Path cache = Path.of(".patch-atlas-cache/maven-integration").toAbsolutePath();
        DockerSandboxRunner runner =
                new DockerSandboxRunner(config(workspace.getParent(), cache, Duration.ofMinutes(3)));

        SandboxExecution warmup =
                runner.execute(
                        workspace,
                        new MavenDependencyWarmupCommand("", "fixtures.StringUtilsTest"));
        assertThat(warmup.status())
                .withFailMessage("warmup status failed:%n%s", warmup.logSummary())
                .isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(warmup.exitCode())
                .withFailMessage("warmup command failed:%n%s", warmup.logSummary())
                .isZero();
        assertThat(warmup.networkMode()).isEqualTo(MavenNetworkMode.ONLINE);

        MavenTestCommand offlineTest =
                new MavenTestCommand("", "fixtures.StringUtilsTest", MavenNetworkMode.OFFLINE);
        SandboxExecution first = runner.execute(workspace, offlineTest);
        SandboxExecution second = runner.execute(workspace, offlineTest);

        assertThat(first.status())
                .withFailMessage("first offline run failed:%n%s", first.logSummary())
                .isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(first.exitCode())
                .withFailMessage("first offline run failed:%n%s", first.logSummary())
                .isZero();
        assertThat(first.networkMode()).isEqualTo(MavenNetworkMode.OFFLINE);
        assertThat(first.command()).contains("-o").doesNotContain("--network=none");
        assertThat(second.status())
                .withFailMessage("second offline run failed:%n%s", second.logSummary())
                .isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(second.exitCode())
                .withFailMessage("second offline run failed:%n%s", second.logSummary())
                .isZero();
        assertThat(second.elapsed()).isLessThan(Duration.ofSeconds(30));

        DockerSandboxRunner timeoutRunner =
                new DockerSandboxRunner(config(workspace.getParent(), cache, Duration.ofMillis(500)));
        SandboxExecution timedOut = timeoutRunner.execute(workspace, offlineTest);

        assertThat(timedOut.status()).isEqualTo(SandboxExecutionStatus.TIMED_OUT);
        assertThat(timedOut.timedOut()).isTrue();
        assertThat(listPatchAtlasContainers()).isBlank();

        System.out.printf(
                "Sandbox cache evidence: warmup=%dms first=%dms second=%dms%n",
                warmup.elapsed().toMillis(),
                first.elapsed().toMillis(),
                second.elapsed().toMillis());
    }

    private static DockerSandboxConfig config(
            Path workspaceRoot, Path cache, Duration timeout) {
        return new DockerSandboxConfig(
                IMAGE,
                timeout,
                64 * 1024,
                workspaceRoot,
                cache,
                SandboxLimits.defaults());
    }

    private static String listPatchAtlasContainers() throws Exception {
        Process process = new ProcessBuilder(
                        "docker",
                        "ps",
                        "-a",
                        "--filter",
                        "name=patch-atlas-",
                        "--format",
                        "{{.Names}}")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return output.trim();
    }
}

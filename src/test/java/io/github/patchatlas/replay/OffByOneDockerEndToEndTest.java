package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker 沙箱层端到端：off-by-one fixture 走通预热 → Buggy 预验证 → Historical Replay 双跑。
 */
@Tag("docker")
class OffByOneDockerEndToEndTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void warmupPrevalidationAndHistoricalReplayYieldValidReproduction() throws Exception {
        Path fixture = Path.of("fixtures/off-by-one").toRealPath();
        Path fixedWorkspace = tempDir.resolve("fixed");
        Path buggyWorkspace = tempDir.resolve("buggy");
        copyTree(fixture, fixedWorkspace);
        copyTree(fixture, buggyWorkspace);
        applyBuggyPatch(fixture.resolve("buggy.patch"), buggyWorkspace);

        Path cache = Path.of(".patch-atlas-cache/maven-replay-it").toAbsolutePath();
        Files.createDirectories(cache);
        DockerSandboxRunner runner = new DockerSandboxRunner(new DockerSandboxConfig(
                Duration.ofMinutes(5),
                64 * 1024,
                tempDir,
                cache,
                SandboxLimits.defaults()));

        TargetTest target =
                new TargetTest("fixtures.StringUtilsTest", "lastCharReturnsFinalCharacter");
        MavenTestCommand command = new MavenTestCommand(
                "",
                "fixtures.StringUtilsTest#lastCharReturnsFinalCharacter",
                MavenNetworkMode.OFFLINE);

        var warmupFixed = runner.execute(
                fixedWorkspace, new MavenDependencyWarmupCommand("", "fixtures.StringUtilsTest"));
        assertThat(warmupFixed.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        var warmupBuggy = runner.execute(
                buggyWorkspace, new MavenDependencyWarmupCommand("", "fixtures.StringUtilsTest"));
        assertThat(warmupBuggy.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);

        SideExecutionResult prevalidation =
                new SideReplayRunner(runner, tempDir).runSide(buggyWorkspace, command, target);
        assertThat(prevalidation.stableEvidence())
                .withFailMessage(
                        "prevalidation evidence: %s attempts=%s",
                        prevalidation.stableEvidence(),
                        prevalidation.attempts().stream()
                                .map(a -> a.outcome() + "/" + a.targetEvidence())
                                .toList())
                .isEqualTo(StableSideEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(prevalidation.attempts()).hasSize(2);

        ReplayResult result = new HistoricalReplayEngine(runner, tempDir)
                .verify(new HistoricalReplayRequest(buggyWorkspace, fixedWorkspace, command, target));

        assertThat(result.verdict())
                .withFailMessage("unexpected verdict: %s%n%s", result.verdict(), summarize(result))
                .isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(result.fixedSide()).isPresent();
        assertThat(result.primarySide().attempts()).hasSize(2);
        assertThat(result.fixedSide().orElseThrow().attempts()).hasSize(2);
    }

    private static String summarize(ReplayResult result) {
        return "buggy="
                + result.primarySide().stableEvidence()
                + " fixed="
                + result.fixedSide().map(SideExecutionResult::stableEvidence).orElse(null)
                + " attempts="
                + result.primarySide().attempts().stream()
                        .map(a -> a.outcome() + "/" + a.targetEvidence())
                        .toList();
    }

    private static void applyBuggyPatch(Path patchFile, Path workspace) throws Exception {
        Process process = new ProcessBuilder("patch", "-sp1", "-d", workspace.toString())
                .redirectInput(patchFile.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .withFailMessage("patch failed: %s", output)
                .isZero();
    }

    private static void copyTree(Path source, Path target) throws Exception {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws java.io.IOException {
                Path rel = source.relativize(dir);
                if (rel.toString().startsWith("target")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws java.io.IOException {
                Path rel = source.relativize(file);
                if (rel.toString().startsWith("target")) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

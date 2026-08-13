package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.StableSideEvidence;
import io.github.patchatlas.replay.TargetTest;
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
 * 单候选 Buggy Docker 预验证。
 *
 * <p>必须经过 Candidate Draft + Patch Gate；不得直接运行 fixture 已知触发测试冒充 Agent 结果。
 * fixture 的 buggy 生产缺陷通过独立 agent 风格候选测试方法复现。
 */
@Tag("docker")
class CandidateBuggyPrevalidationDockerTest {


    /**
     * Agent 风格新测试方法（非 fixture 自带的 lastCharReturnsFinalCharacter）。
     */
    private static final String AGENT_CANDIDATE_PATCH =
            """
            diff --git a/src/test/java/fixtures/StringUtilsTest.java b/src/test/java/fixtures/StringUtilsTest.java
            --- a/src/test/java/fixtures/StringUtilsTest.java
            +++ b/src/test/java/fixtures/StringUtilsTest.java
            @@ -11,4 +11,10 @@
                 void lastCharReturnsFinalCharacter() {
                     assertEquals('c', StringUtils.lastChar("abc"));
                 }
            +
            +    /** Agent candidate: reproduces off-by-one on buggy revision. */
            +    @Test
            +    void agentCandidateReproducesOffByOne() {
            +        assertEquals('z', StringUtils.lastChar("xyz"));
            +    }
             }
            """;

    private static final TargetTest AGENT_TARGET =
            new TargetTest("fixtures.StringUtilsTest", "agentCandidateReproducesOffByOne");

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void agentCandidateThroughPatchGateYieldsTargetAssertionFailureOnBuggy() throws Exception {
        Path fixture = Path.of("fixtures/off-by-one").toRealPath();
        Path workspace = tempDir.resolve("candidate-ws");
        copyTree(fixture, workspace);
        // 先让生产代码处于 buggy 修订态
        applyBuggyPatch(fixture.resolve("buggy.patch"), workspace);

        // Candidate Draft → 真实 Patch Gate（不是直接跑已知触发测试）
        CandidateDraft draft = new CandidateDraft(AGENT_CANDIDATE_PATCH, AGENT_TARGET);
        PatchGate gate = new PatchGate(tempDir);
        PatchPreparationResult prepared =
                gate.prepare(workspace, "", draft, MavenNetworkMode.OFFLINE);
        assertThat(prepared).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        PatchPreparationResult.PreparedCandidate ok =
                (PatchPreparationResult.PreparedCandidate) prepared;
        assertThat(ok.targetTest()).isEqualTo(AGENT_TARGET);
        assertThat(Files.readString(workspace.resolve("src/test/java/fixtures/StringUtilsTest.java")))
                .contains("agentCandidateReproducesOffByOne")
                .contains("lastCharReturnsFinalCharacter"); // 已知触发仍在文件中，但不是候选目标

        Path cache = Path.of(".patch-atlas-cache/maven-preval-it").toAbsolutePath();
        Files.createDirectories(cache);
        DockerSandboxRunner runner = new DockerSandboxRunner(new DockerSandboxConfig(
                Duration.ofMinutes(5),
                64 * 1024,
                tempDir,
                cache,
                SandboxLimits.defaults()));

        var warmup = runner.execute(
                workspace, new MavenDependencyWarmupCommand("", "fixtures.StringUtilsTest"));
        assertThat(warmup.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);

        SideReplayRunner side = new SideReplayRunner(runner, tempDir);
        MavenTestCommand command = ok.command();
        // 确认命令指向 Agent 目标，而非已知触发方法
        assertThat(command.testSelector()).contains("agentCandidateReproducesOffByOne");
        assertThat(command.testSelector()).doesNotContain("lastCharReturnsFinalCharacter");

        SideExecutionResult result = side.runSide(workspace, command, AGENT_TARGET);
        assertThat(result.stableEvidence())
                .withFailMessage(
                        "prevalidation evidence: %s attempts=%s",
                        result.stableEvidence(),
                        result.attempts().stream()
                                .map(a -> a.outcome() + "/" + a.targetEvidence())
                                .toList())
                .isEqualTo(StableSideEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(result.attempts()).hasSize(2);

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

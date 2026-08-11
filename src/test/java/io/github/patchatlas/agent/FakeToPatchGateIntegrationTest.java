package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.FakeSandboxRunner;
import io.github.patchatlas.replay.LiveReplayEngine;
import io.github.patchatlas.replay.LiveReplayRequest;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 离线证明 seam 可衔接：Fake → Patch Gate → LiveReplayEngine（Fake sandbox）。
 */
class FakeToPatchGateIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fakeGeneratePrepareAndLiveReplaySeam() throws Exception {
        Path workspace = tempDir.resolve("case");
        Files.createDirectories(workspace);

        TargetTest target = new TargetTest("fixtures.NewTest", "works");
        FakeTestGenerator generator = new FakeTestGenerator(new GenerationResult.GeneratedCandidate(
                FakeTestGeneratorTest.minimalCreatePatch(), target));

        GenerationResult generated = generator.generate(FakeTestGeneratorTest.sampleInput());
        assertThat(generated).isInstanceOf(GenerationResult.GeneratedCandidate.class);

        PatchGate gate = new PatchGate(tempDir);
        PatchPreparationResult prepared = gate.prepare(
                workspace,
                "",
                (GenerationResult.GeneratedCandidate) generated,
                MavenNetworkMode.OFFLINE);
        assertThat(prepared).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        PatchPreparationResult.PreparedCandidate ready =
                (PatchPreparationResult.PreparedCandidate) prepared;

        FakeSandboxRunner fake = new FakeSandboxRunner();
        // 双跑：断言失败 → REPRODUCTION_CANDIDATE
        String surefire =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="fixtures.NewTest" tests="1" errors="0" skipped="0" failures="1">
                  <testcase name="works" classname="fixtures.NewTest" time="0.01">
                    <failure message="expected" type="org.opentest4j.AssertionFailedError"/>
                  </testcase>
                </testsuite>
                """;
        fake.enqueue(completed(1), surefire);
        fake.enqueue(completed(1), surefire);

        ReplayResult replay = new LiveReplayEngine(fake, tempDir)
                .verify(new LiveReplayRequest(ready.workspace(), ready.command(), ready.targetTest()));

        assertThat(replay.verdict()).isEqualTo(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(Files.exists(workspace.resolve("src/test/java/fixtures/NewTest.java"))).isTrue();
    }

    private static SandboxExecution completed(int exit) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exit,
                Duration.ofMillis(10),
                false,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }
}

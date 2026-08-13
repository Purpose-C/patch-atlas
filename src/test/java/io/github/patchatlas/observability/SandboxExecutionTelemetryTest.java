package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 两个真实执行点记录 Timer；预执行失败不记；observer 失败不改结果。 */
class SandboxExecutionTelemetryTest {

    private static final TargetTest TARGET = new TargetTest("fixtures.OldTest", "added");
    private static final MavenTestCommand OFFLINE =
            new MavenTestCommand("", "fixtures.OldTest#added", MavenNetworkMode.OFFLINE);

    @TempDir
    Path tempDir;

    @Test
    void warmupAndSideReplayRecordElapsedWithoutSleeping() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxExecutionObserver observer = new MicrometerSandboxExecutionObserver(registry);
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.completed(0),
                ScriptedSandboxRunner.completed(1),
                ScriptedSandboxRunner.completed(1));

        assertThat(new DependencyWarmupRunner(sandbox, tempDir, observer).warm(workspace, OFFLINE))
                .isEmpty();
        new SideReplayRunner(sandbox, tempDir, observer).runSide(workspace, OFFLINE, TARGET);

        Timer warmup = registry.find("patchatlas.sandbox.execution.duration")
                .tags("command_type", "dependency_warmup", "network_mode", "offline", "status", "completed", "timed_out", "false")
                .timer();
        Timer test = registry.find("patchatlas.sandbox.execution.duration")
                .tags("command_type", "test", "network_mode", "offline", "status", "completed", "timed_out", "false")
                .timer();
        assertThat(warmup).isNotNull();
        assertThat(test).isNotNull();
        assertThat(warmup.count()).isEqualTo(1);
        assertThat(test.count()).isEqualTo(2);
        assertThat(warmup.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(10);
        assertThat(test.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(20);
    }

    @Test
    void preExecutionTrustFailureDoesNotRecordDuration() throws Exception {
        Path outside = Files.createTempDirectory("outside-sandbox-obs-");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxExecutionObserver observer = new MicrometerSandboxExecutionObserver(registry);
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));

        var result = new SideReplayRunner(sandbox, tempDir, observer).runSide(outside, OFFLINE, TARGET);

        assertThat(result.attempts().getFirst().execution()).isEmpty();
        assertThat(sandbox.callCount()).isZero();
        assertThat(registry.find("patchatlas.sandbox.execution.duration")
                .tags("command_type", "test", "network_mode", "offline", "status", "completed", "timed_out", "false")
                .timer().count()).isZero();
    }

    @Test
    void observerFailureKeepsOriginalExecutionAndReplayOutcome() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        AtomicInteger calls = new AtomicInteger();
        SandboxExecutionObserver boom = (command, execution) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("meter registry exploded");
        };
        SandboxExecution scripted = ScriptedSandboxRunner.completed(1);
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(scripted);

        var side = new SideReplayRunner(sandbox, tempDir, boom).runSide(workspace, OFFLINE, TARGET);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(side.attempts()).hasSize(2);
        assertThat(side.attempts().getFirst().execution()).contains(scripted);
        assertThat(side.stableEvidence().name()).contains("TARGET_ASSERTION_FAILURE");
    }

    @Test
    void onlineWarmupDoesNotExecuteOrRecord() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxExecutionObserver observer = new MicrometerSandboxExecutionObserver(registry);
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(0));
        MavenTestCommand online = new MavenTestCommand("", "fixtures.OldTest#added", MavenNetworkMode.ONLINE);

        assertThat(new DependencyWarmupRunner(sandbox, tempDir, observer).warm(workspace, online))
                .isEmpty();
        assertThat(sandbox.callCount()).isZero();
        assertThat(registry.find("patchatlas.sandbox.execution.duration")
                .tags("command_type", "dependency_warmup", "network_mode", "online", "status", "completed", "timed_out", "false")
                .timer().count()).isZero();
    }
}

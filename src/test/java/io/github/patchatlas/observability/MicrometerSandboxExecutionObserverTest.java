package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** ��Timer 记录 SandboxExecution.elapsed，不测墙钟。 */
class MicrometerSandboxExecutionObserverTest {

    private static final MavenTestCommand TEST =
            new MavenTestCommand("", "fixtures.T#m", MavenNetworkMode.OFFLINE);

    @Test
    void recordsElapsedAndTagsFromReturnedExecution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSandboxExecutionObserver observer = new MicrometerSandboxExecutionObserver(registry);
        observer.record(TEST, execution(SandboxExecutionStatus.COMPLETED, false, Duration.ofMillis(250)));

        Timer timer = registry.find("patchatlas.sandbox.execution.duration")
                .tags(
                        "command_type",
                        "test",
                        "network_mode",
                        "offline",
                        "status",
                        "completed",
                        "timed_out",
                        "false")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
    }

    @Test
    void timeoutStatusesShareTimedOutTrueCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSandboxExecutionObserver observer = new MicrometerSandboxExecutionObserver(registry);
        observer.record(TEST, execution(SandboxExecutionStatus.TIMED_OUT, true, Duration.ofSeconds(1)));
        observer.record(
                TEST, execution(SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED, true, Duration.ofSeconds(1)));

        double timedOut = registry.find("patchatlas.sandbox.execution.duration").timers().stream()
                .filter(timer -> "true".equals(timer.getId().getTag("timed_out")))
                .mapToLong(Timer::count)
                .sum();
        assertThat(timedOut).isEqualTo(2);
    }

    @Test
    void statusTagFollowsCurrentEnumNames() {
        assertThat(SandboxExecutionStatus.values()).hasSize(10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSandboxExecutionObserver observer = new MicrometerSandboxExecutionObserver(registry);
        for (SandboxExecutionStatus status : SandboxExecutionStatus.values()) {
            boolean timedOut = status == SandboxExecutionStatus.TIMED_OUT
                    || status == SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED;
            observer.record(TEST, execution(status, timedOut, Duration.ofMillis(1)));
        }
        assertThat(registry.find("patchatlas.sandbox.execution.duration").timers().stream()
                        .filter(timer -> timer.count() > 0))
                .extracting(timer -> timer.getId().getTag("status"))
                .containsExactlyInAnyOrder(
                        "completed",
                        "timed_out",
                        "timeout_cleanup_failed",
                        "docker_unavailable",
                        "image_unavailable",
                        "container_setup_failed",
                        "workspace_unavailable",
                        "cache_unavailable",
                        "process_start_failed",
                        "cleanup_failed");
    }

    private static SandboxExecution execution(
            SandboxExecutionStatus status, boolean timedOut, Duration elapsed) {
        return new SandboxExecution(
                status,
                status == SandboxExecutionStatus.COMPLETED ? 0 : null,
                elapsed,
                timedOut,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }
}

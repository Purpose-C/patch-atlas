package io.github.patchatlas.observability;

import io.github.patchatlas.run.RunEvents;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenSandboxCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;

/** 用返回的 elapsed 记录沙箱 Timer，并写结构化日志；不重新测量墙钟。 */
public final class MicrometerSandboxExecutionObserver implements SandboxExecutionObserver {

    public static final String METER_NAME = "patchatlas.sandbox.execution.duration";
    private static final String[] COMMAND_TYPES = {"dependency_warmup", "test"};

    private final MeterRegistry registry;

    public MicrometerSandboxExecutionObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        preRegisterTimers();
    }

    private void preRegisterTimers() {
        for (String commandType : COMMAND_TYPES) {
            for (MavenNetworkMode network : MavenNetworkMode.values()) {
                for (SandboxExecutionStatus status : SandboxExecutionStatus.values()) {
                    for (boolean timedOut : legalTimedOutValues(status)) {
                        Timer.builder(METER_NAME)
                                .tag("command_type", commandType)
                                .tag("network_mode", tag(network))
                                .tag("status", tag(status))
                                .tag("timed_out", Boolean.toString(timedOut))
                                .register(registry);
                    }
                }
            }
        }
    }

    private static boolean[] legalTimedOutValues(SandboxExecutionStatus status) {
        if (status == SandboxExecutionStatus.COMPLETED) {
            return new boolean[] {false};
        }
        if (status == SandboxExecutionStatus.TIMED_OUT
                || status == SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED) {
            return new boolean[] {true};
        }
        return new boolean[] {false, true};
    }

    @Override
    public void record(MavenSandboxCommand command, SandboxExecution execution) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(execution, "execution");
        try {
            RunEvents.sandboxExecuted(command, execution);
        } catch (RuntimeException ignored) {
            // 日志失败不得改变沙箱事实
        }
        try {
            Timer.builder(METER_NAME)
                    .tag("command_type", commandType(command))
                    .tag("network_mode", tag(execution.networkMode()))
                    .tag("status", tag(execution.status()))
                    .tag("timed_out", Boolean.toString(execution.timedOut()))
                    .register(registry)
                    .record(execution.elapsed());
        } catch (RuntimeException ex) {
            RunEvents.observabilityRecordingFailed(ex);
        }
    }

    private static String commandType(MavenSandboxCommand command) {
        return command instanceof MavenDependencyWarmupCommand ? "dependency_warmup" : "test";
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}

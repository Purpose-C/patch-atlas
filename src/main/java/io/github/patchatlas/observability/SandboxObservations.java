package io.github.patchatlas.observability;

import io.github.patchatlas.run.RunEvents;
import io.github.patchatlas.sandbox.MavenSandboxCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;

/**
 * 观测适配层：在沙箱执行后记录结构化日志与 Micrometer Timer。
 *
 * <p>位于 observability 包，避免 sandbox → observability 的循环依赖。
 * 日志与 observer 失败均不得覆盖已取得的 {@link SandboxExecution}。
 */
public final class SandboxObservations {

    private SandboxObservations() {}

    public static void recordSafely(
            SandboxExecutionObserver observer, MavenSandboxCommand command, SandboxExecution execution) {
        try {
            RunEvents.sandboxExecuted(command, execution);
        } catch (RuntimeException ignored) {
            // 日志失败不得改变沙箱事实
        }
        try {
            observer.record(command, execution);
        } catch (RuntimeException ex) {
            RunEvents.observabilityRecordingFailed(ex);
        }
    }
}

package io.github.patchatlas.sandbox;

import io.github.patchatlas.observability.RunEvents;

/** 观测失败不得覆盖已取得的 {@link SandboxExecution}。 */
public final class SandboxObservations {

    private SandboxObservations() {}

    public static void recordSafely(
            SandboxExecutionObserver observer, MavenSandboxCommand command, SandboxExecution execution) {
        RunEvents.sandboxExecuted(command, execution);
        try {
            observer.record(command, execution);
        } catch (RuntimeException ex) {
            RunEvents.observabilityRecordingFailed(ex);
        }
    }
}

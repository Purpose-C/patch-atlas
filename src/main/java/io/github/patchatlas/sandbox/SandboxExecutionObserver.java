package io.github.patchatlas.sandbox;

/** 单方法 seam：记录一次已返回的 {@link SandboxExecution}，不得改变该事实。 */
@FunctionalInterface
public interface SandboxExecutionObserver {

    SandboxExecutionObserver NOOP = (command, execution) -> {};

    void record(MavenSandboxCommand command, SandboxExecution execution);
}

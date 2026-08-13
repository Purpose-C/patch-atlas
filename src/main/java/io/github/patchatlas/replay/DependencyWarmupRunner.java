package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** 在候选补丁写入前，为 OFFLINE Maven 执行预热可信 revision 的依赖。 */
public final class DependencyWarmupRunner {

    private final SandboxRunner sandboxRunner;
    private final Path allowedWorkspaceRoot;
    private final SandboxExecutionObserver observer;

    public DependencyWarmupRunner(SandboxRunner sandboxRunner, Path allowedWorkspaceRoot) {
        this(sandboxRunner, allowedWorkspaceRoot, SandboxExecutionObserver.NOOP);
    }

    public DependencyWarmupRunner(
            SandboxRunner sandboxRunner, Path allowedWorkspaceRoot, SandboxExecutionObserver observer) {
        this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner");
        this.allowedWorkspaceRoot = WorkspaceTrust.normalizeAllowedRoot(allowedWorkspaceRoot);
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * @return 空表示无需预热或预热成功；非空表示 Maven/沙箱未能完成预热
     */
    public Optional<String> warm(Path workspace, MavenTestCommand command) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(command, "command");
        if (command.networkMode() == MavenNetworkMode.ONLINE) {
            return Optional.empty();
        }

        WorkspaceTrust.requireUnderAllowedRoot(workspace, allowedWorkspaceRoot);
        MavenDependencyWarmupCommand warmup = new MavenDependencyWarmupCommand(
                command.modulePath(), command.testSelector(), command.javaVersion());
        SandboxExecution execution = sandboxRunner.execute(workspace, warmup);
        try {
            observer.record(warmup, execution);
        } catch (RuntimeException ignored) {
            // 观测失败不得改变沙箱事实
        }
        if (execution.status() != SandboxExecutionStatus.COMPLETED) {
            return Optional.of("dependency warmup failed: " + execution.status().name());
        }
        if (execution.exitCode() == null || execution.exitCode() != 0) {
            return Optional.of("dependency warmup Maven execution failed");
        }
        return Optional.empty();
    }
}

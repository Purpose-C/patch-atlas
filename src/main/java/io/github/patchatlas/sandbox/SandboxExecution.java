package io.github.patchatlas.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Docker/Maven 执行产生的结构化事实。
 *
 * <p>{@code command} 只暴露容器内实际请求的 Maven 白名单命令,不泄漏宿主路径或 Docker CLI;
 * {@code exitCode} 仅在进程真实退出时存在。
 */
public record SandboxExecution(
        SandboxExecutionStatus status,
        Integer exitCode,
        Duration elapsed,
        boolean timedOut,
        List<String> command,
        String logSummary,
        String image,
        SandboxLimits limits,
        MavenNetworkMode networkMode) {

    public SandboxExecution {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(elapsed, "elapsed");
        command = List.copyOf(command);
        Objects.requireNonNull(logSummary, "logSummary");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(networkMode, "networkMode");
        if ((status == SandboxExecutionStatus.TIMED_OUT
                        || status == SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED)
                && !timedOut) {
            throw new IllegalArgumentException("timeout status requires timedOut=true");
        }
        if (status == SandboxExecutionStatus.COMPLETED && timedOut) {
            throw new IllegalArgumentException("completed execution cannot be timed out");
        }
        if (status == SandboxExecutionStatus.COMPLETED && exitCode == null) {
            throw new IllegalArgumentException("completed execution requires exitCode");
        }
    }
}

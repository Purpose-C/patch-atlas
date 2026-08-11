package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 将 {@link SandboxExecution} 与 {@link TestReport} 合成为单侧 {@link RunOutcome}。
 *
 * <p>不做跨 revision 裁决；不从单次 XML 的 flakes 字段猜测 flaky。
 */
public final class ExecutionClassifier {

    public RunOutcome classify(SandboxExecution execution, TestReport report) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(report, "report");

        if (execution.timedOut()
                || execution.status() == SandboxExecutionStatus.TIMED_OUT
                || execution.status() == SandboxExecutionStatus.TIMEOUT_CLEANUP_FAILED) {
            return RunOutcome.TIMEOUT;
        }

        if (execution.status() != SandboxExecutionStatus.COMPLETED) {
            return RunOutcome.ENVIRONMENT_FAILURE;
        }

        if (report.count(TestCaseStatus.FAILED) > 0) {
            return RunOutcome.ASSERTION_FAILURE;
        }
        if (report.count(TestCaseStatus.ERROR) > 0) {
            return RunOutcome.TEST_ERROR;
        }

        Integer exitCode = execution.exitCode();
        boolean exitOk = exitCode != null && exitCode == 0;

        // 已排除 FAILED/ERROR 后，剩余用例只会是 PASSED 或 SKIPPED。
        if (!report.testCases().isEmpty()) {
            return exitOk ? RunOutcome.PASS : RunOutcome.ENVIRONMENT_FAILURE;
        }

        // 无 Surefire 用例：编译失败常见路径是 COMPLETED + 非零退出 + 空报告。
        if (exitOk) {
            return RunOutcome.PASS;
        }
        if (looksLikeInfrastructureFailure(execution.logSummary())) {
            return RunOutcome.ENVIRONMENT_FAILURE;
        }
        return RunOutcome.COMPILE_FAILURE;
    }

    public RunOutcome classifyAttempts(List<RunOutcome> attempts) {
        Objects.requireNonNull(attempts, "attempts");
        if (attempts.size() < 2) {
            throw new IllegalArgumentException("flaky classification requires at least 2 attempts");
        }
        RunOutcome first = Objects.requireNonNull(attempts.getFirst(), "attempt");
        for (RunOutcome attempt : attempts) {
            Objects.requireNonNull(attempt, "attempt");
            if (attempt != first) {
                return RunOutcome.FLAKY_FAILURE;
            }
        }
        return first;
    }

    private static boolean looksLikeInfrastructureFailure(String logSummary) {
        if (logSummary == null || logSummary.isBlank()) {
            return false;
        }
        String log = logSummary.toLowerCase(Locale.ROOT);
        return containsAny(
                log,
                "could not resolve dependencies",
                "non-resolvable parent pom",
                "connection refused",
                "unknown host",
                "pluginresolutionexception",
                "failed to read artifact descriptor",
                "could not transfer artifact",
                "repository system session",
                "error resolving version",
                "no compiler is provided",
                "invalid target release",
                "unsupported class file major version",
                "java.lang.outofmemoryerror",
                "cannot create resource");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

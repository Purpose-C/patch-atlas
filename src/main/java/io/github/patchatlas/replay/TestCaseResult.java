package io.github.patchatlas.replay;

import java.time.Duration;
import java.util.Objects;

/**
 * 单条测试用例的中立执行事实。
 *
 * <p>不携带 DOM/Surefire XML 类型；消息已在解析时截断。
 */
public record TestCaseResult(
        String className,
        String methodName,
        Duration elapsed,
        TestCaseStatus status,
        String exceptionType,
        String message) {

    public TestCaseResult {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(status, "status");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
        if (status == TestCaseStatus.PASSED) {
            if (exceptionType != null || message != null) {
                throw new IllegalArgumentException("passed result cannot carry exception details");
            }
        }
    }
}

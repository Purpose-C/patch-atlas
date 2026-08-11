package io.github.patchatlas.replay;

import java.util.Objects;

/**
 * 一次验证中被明确选定的目标测试：完整类名 + 方法名。
 *
 * <p>V1 只支持能稳定映射为单条 Surefire {@code testcase} 的普通方法。
 */
public record TargetTest(String className, String methodName) {

    public TargetTest {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
    }
}

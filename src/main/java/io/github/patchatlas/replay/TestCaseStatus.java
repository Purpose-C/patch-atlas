package io.github.patchatlas.replay;

/**
 * Surefire XML 中单条用例的事实状态。
 *
 * <p>这不是最终失败分类（如 {@code ASSERTION_FAILURE} / {@code ENVIRONMENT_FAILURE}）；
 * 那些由 结合 {@code SandboxExecution} 与日志再判定。
 */
public enum TestCaseStatus {
    PASSED,
    FAILED,
    ERROR,
    SKIPPED
}

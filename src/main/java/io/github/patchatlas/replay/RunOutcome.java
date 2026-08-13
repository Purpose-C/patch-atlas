package io.github.patchatlas.replay;

/**
 * 单侧（或多次尝试聚合后的）执行分类结果。
 *
 * <p>跨 revision 的 {@code VALID_REPRODUCTION} / {@code FAILED_ON_BOTH_COMMITS} 属于跨 revision 裁决，不在此枚举。
 * {@link #FLAKY_FAILURE} 只能由 {@link ExecutionClassifier#classifyAttempts} 产生。
 */
public enum RunOutcome {
    PASS,
    ASSERTION_FAILURE,
    TEST_ERROR,
    COMPILE_FAILURE,
    ENVIRONMENT_FAILURE,
    TIMEOUT,
    FLAKY_FAILURE
}

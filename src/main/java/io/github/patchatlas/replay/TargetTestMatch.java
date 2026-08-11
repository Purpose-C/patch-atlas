package io.github.patchatlas.replay;

/**
 * 一次 {@link TestReport} 相对 {@link TargetTest} 的精确匹配结果。
 *
 * <p>只有 {@link #MATCHED_PASSED} / {@link #MATCHED_FAILED} 可参与成功裁决的目标证据。
 */
public enum TargetTestMatch {
    MATCHED_PASSED,
    MATCHED_FAILED,
    MISSING,
    DUPLICATE,
    SKIPPED,
    ERROR,
    /** 目标本身存在，但报告中另有 FAILED/ERROR 用例。 */
    ACCOMPANYING_FAILURES
}

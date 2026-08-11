package io.github.patchatlas.replay;

/** 单次尝试走到的阶段；禁止用伪造执行事实掩盖未执行。 */
public enum AttemptPhase {
    /** 清理/路径校验失败，沙箱未执行。 */
    PRE_EXECUTION_FAILURE,
    /** 沙箱已执行且报告可读（含空报告）。 */
    EXECUTED,
    /** 沙箱已执行，但报告解析/路径不安全失败。 */
    REPORT_FAILURE
}

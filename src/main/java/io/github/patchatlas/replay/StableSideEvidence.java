package io.github.patchatlas.replay;

/**
 * 某一 revision 侧经归一化后、可供纯状态机使用的稳定证据。
 *
 * <p>由后续编排层从双次尝试 + Target Test 精确匹配结果归约而来；
 * 本枚举本身不解析 XML、不调用 Docker。
 */
public enum StableSideEvidence {
    /** 目标测试稳定断言失败（且满足成功证据门槛）。 */
    TARGET_ASSERTION_FAILURE,
    /** 目标测试稳定通过（且满足成功证据门槛）。 */
    TARGET_PASSED,
    /** flaky、编译/环境/超时、目标缺失、证据矛盾等——不能支持成功裁决。 */
    OTHER_OR_INVALID
}

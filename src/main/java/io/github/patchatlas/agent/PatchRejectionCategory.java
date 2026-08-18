package io.github.patchatlas.agent;

/** 有界拒绝类别；不回显完整 patch / 绝对路径 / 密钥。 */
public enum PatchRejectionCategory {
    MALFORMED_OR_OVERSIZED_PATCH,
    UNSAFE_OR_OUT_OF_SCOPE_PATH,
    UNSUPPORTED_CHANGE_TYPE,
    FILE_OR_LINE_LIMIT_EXCEEDED,
    TARGET_NOT_CHANGED_BY_PATCH,
    /** 补丁无法唯一推导 Target Test；与模型声称了错误目标不是同一件事。 */
    TARGET_TEST_NOT_DERIVABLE,
    WORKSPACE_UNSAFE,
    APPLICATION_FAILURE
}

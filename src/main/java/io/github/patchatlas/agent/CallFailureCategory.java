package io.github.patchatlas.agent;

/** 单次模型调用失败类别（不等于 Run 终态）。 */
public enum CallFailureCategory {
    /** 可修正：结构/schema 无效。 */
    STRUCTURED_OUTPUT_INVALID,
    MODEL_CONFIGURATION_ERROR,
    MODEL_AUTHENTICATION_ERROR,
    MODEL_UNAVAILABLE,
    MODEL_REFUSED
}

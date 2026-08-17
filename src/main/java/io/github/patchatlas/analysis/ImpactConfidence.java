package io.github.patchatlas.analysis;

/**
 * 影响置信度分级：描述一条边的证据强度，不是模型自信程度。
 */
public enum ImpactConfidence {
    CONFIRMED,
    INFERRED,
    POSSIBLE
}

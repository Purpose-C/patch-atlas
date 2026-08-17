package io.github.patchatlas.run;

/** FAILED 终态的阶段标识（有界，非自由文本）。 */
public enum FailureStage {
    LOCATING,
    GENERATION,
    PATCH_GATE,
    WORKSPACE,
    REPLAY,
    RECOVERY
}

package io.github.patchatlas.run;

/** FAILED 终态的稳定类别。 */
public enum FailureCategory {
    GENERATION_FAILURE,
    PATCH_REJECTED,
    WORKSPACE_UNSAFE,
    REPLAY_SYSTEM_ERROR,
    RECOVERY_EXHAUSTED
}

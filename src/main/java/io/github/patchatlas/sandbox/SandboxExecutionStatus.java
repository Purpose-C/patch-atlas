package io.github.patchatlas.sandbox;

/** 只记录执行层状态；Maven/JUnit 失败语义由上层结果解析器判断。 */
public enum SandboxExecutionStatus {
    COMPLETED,
    TIMED_OUT,
    TIMEOUT_CLEANUP_FAILED,
    DOCKER_UNAVAILABLE,
    IMAGE_UNAVAILABLE,
    WORKSPACE_UNAVAILABLE,
    CACHE_UNAVAILABLE,
    PROCESS_START_FAILED
}

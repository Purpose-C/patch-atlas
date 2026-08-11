package io.github.patchatlas.repository;

import java.nio.file.Path;
import java.time.Duration;

/** 匿名克隆公开仓库的结构化结果。 */
public sealed interface CloneResult {

    record Success(Path workDir, Duration elapsed) implements CloneResult {}

    record RejectedInput(String reason) implements CloneResult {}

    record Unreachable(String repositoryUrl, String reason) implements CloneResult {}
}

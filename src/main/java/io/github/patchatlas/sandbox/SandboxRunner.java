package io.github.patchatlas.sandbox;

import java.nio.file.Path;

/** 在隔离环境中执行一个白名单 Maven 命令并返回可追溯事实。 */
public interface SandboxRunner {

    SandboxExecution execute(Path workspace, MavenSandboxCommand command);
}

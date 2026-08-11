package io.github.patchatlas.sandbox;

import java.util.List;

/**
 * 沙箱可执行的 Maven 白名单命令。
 *
 * <p>调用方只能选择这些强类型模板，不能传入完整命令字符串。
 */
public sealed interface MavenSandboxCommand
        permits MavenTestCommand, MavenDependencyWarmupCommand {

    List<String> arguments();

    MavenNetworkMode networkMode();
}

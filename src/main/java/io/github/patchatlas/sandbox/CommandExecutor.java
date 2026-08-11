package io.github.patchatlas.sandbox;

import java.time.Duration;
import java.util.List;

/** 宿主进程边界；包内可替换以便离线测试 Docker 编排。 */
interface CommandExecutor {

    CommandExecution execute(List<String> command, Duration timeout, int maxOutputBytes);
}

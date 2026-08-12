package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenSandboxCommand;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** 测试用沙箱：按队列返回执行事实，并可写入 Surefire XML。 */
public final class FakeSandboxRunner implements SandboxRunner {

    private final Deque<ScriptedExecution> scripts = new ArrayDeque<>();
    private final List<Path> executedWorkspaces = new ArrayList<>();
    private final List<MavenSandboxCommand> executedCommands = new ArrayList<>();

    public void enqueue(SandboxExecution execution, String reportXml) {
        scripts.addLast(new ScriptedExecution(execution, reportXml));
    }

    public int remaining() {
        return scripts.size();
    }

    public List<Path> executedWorkspaces() {
        return List.copyOf(executedWorkspaces);
    }

    public List<MavenSandboxCommand> executedCommands() {
        return List.copyOf(executedCommands);
    }

    @Override
    public SandboxExecution execute(Path workspace, MavenSandboxCommand command) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(command, "command");
        if (scripts.isEmpty()) {
            throw new IllegalStateException("no scripted sandbox execution left");
        }
        ScriptedExecution script = scripts.removeFirst();
        executedWorkspaces.add(workspace);
        executedCommands.add(command);
        if (command instanceof MavenTestCommand testCommand) {
            try {
                Path reportsDir =
                        SurefireReportsLocation.resolve(workspace, testCommand.modulePath());
                Files.createDirectories(reportsDir);
                if (script.reportXml() != null) {
                    Files.writeString(
                            reportsDir.resolve("TEST-Scripted.xml"),
                            script.reportXml(),
                            StandardCharsets.UTF_8);
                }
            } catch (Exception ex) {
                throw new IllegalStateException("failed to materialize surefire report", ex);
            }
        }
        return script.execution();
    }

    private record ScriptedExecution(SandboxExecution execution, String reportXml) {}
}

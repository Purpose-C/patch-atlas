package io.github.patchatlas.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认测试用脚本化 SandboxRunner：返回预设执行事实，并写入最小 Surefire XML，
 * 使 {@code SideReplayRunner} 能归约出稳定目标证据。
 */
public final class ScriptedSandboxRunner implements SandboxRunner {

    public enum ReportMode {
        /** 目标测试失败（断言失败） */
        TARGET_ASSERTION_FAILURE,
        /** 目标测试通过 */
        TARGET_PASSED,
        /** 空报告 + 非零退出 → 编译失败 */
        COMPILE_FAILURE
    }

    public record ScriptedStep(SandboxExecution execution, ReportMode reportMode) {
        public ScriptedStep {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(reportMode, "reportMode");
        }
    }

    private final List<ScriptedStep> script;
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedSandboxRunner(List<ScriptedStep> script) {
        this.script = List.copyOf(Objects.requireNonNull(script, "script"));
        if (this.script.isEmpty()) {
            throw new IllegalArgumentException("script empty");
        }
    }

    public static ScriptedSandboxRunner always(SandboxExecution execution) {
        ReportMode mode = isFailureExit(execution) ? ReportMode.TARGET_ASSERTION_FAILURE : ReportMode.TARGET_PASSED;
        return new ScriptedSandboxRunner(List.of(new ScriptedStep(execution, mode)));
    }

    public static ScriptedSandboxRunner always(ScriptedStep step) {
        return new ScriptedSandboxRunner(List.of(step));
    }

    public static ScriptedSandboxRunner of(ScriptedStep... steps) {
        return new ScriptedSandboxRunner(List.of(steps));
    }

    public static ScriptedSandboxRunner of(SandboxExecution... executions) {
        return new ScriptedSandboxRunner(List.of(executions).stream()
                .map(e -> new ScriptedStep(
                        e, isFailureExit(e) ? ReportMode.TARGET_ASSERTION_FAILURE : ReportMode.TARGET_PASSED))
                .toList());
    }

    @Override
    public SandboxExecution execute(Path workspace, MavenSandboxCommand command) {
        int i = calls.getAndIncrement();
        ScriptedStep step = i >= script.size() ? script.getLast() : script.get(i);
        writeSurefireReport(workspace, command, step.reportMode());
        return step.execution();
    }

    public int callCount() {
        return calls.get();
    }

    public static SandboxExecution completed(int exitCode) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exitCode,
                Duration.ofMillis(10),
                false,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    public static ScriptedStep targetAssertionFailure() {
        return new ScriptedStep(completed(1), ReportMode.TARGET_ASSERTION_FAILURE);
    }

    public static ScriptedStep targetPassed() {
        return new ScriptedStep(completed(0), ReportMode.TARGET_PASSED);
    }

    public static ScriptedStep compileFailure() {
        return new ScriptedStep(
                new SandboxExecution(
                        SandboxExecutionStatus.COMPLETED,
                        1,
                        Duration.ofMillis(10),
                        false,
                        List.of("mvn", "test"),
                        "[ERROR] COMPILATION ERROR",
                        "maven:3.9-eclipse-temurin-21",
                        SandboxLimits.defaults(),
                        MavenNetworkMode.OFFLINE),
                ReportMode.COMPILE_FAILURE);
    }

    private static boolean isFailureExit(SandboxExecution execution) {
        return execution.exitCode() != null && execution.exitCode() != 0;
    }

    private static void writeSurefireReport(
            Path workspace, MavenSandboxCommand command, ReportMode mode) {
        try {
            String className = "fixtures.OldTest";
            String method = "added";
            if (command instanceof MavenTestCommand mtc) {
                String selector = mtc.testSelector();
                int hash = selector.indexOf('#');
                if (hash > 0) {
                    className = selector.substring(0, hash);
                    method = selector.substring(hash + 1);
                } else {
                    className = selector;
                }
            }
            Path reports = workspace.resolve("target/surefire-reports");
            Files.createDirectories(reports);
            String xml = switch (mode) {
                case TARGET_ASSERTION_FAILURE ->
                        """
                        <?xml version="1.0"?>
                        <testsuite tests="1" failures="1" errors="0" skipped="0">
                          <testcase classname="%s" name="%s" time="0.01">
                            <failure type="org.opentest4j.AssertionFailedError" message="x">x</failure>
                          </testcase>
                        </testsuite>
                        """
                                .formatted(className, method);
                case TARGET_PASSED ->
                        """
                        <?xml version="1.0"?>
                        <testsuite tests="1" failures="0" errors="0" skipped="0">
                          <testcase classname="%s" name="%s" time="0.01"/>
                        </testsuite>
                        """
                                .formatted(className, method);
                case COMPILE_FAILURE ->
                        """
                        <?xml version="1.0"?>
                        <testsuite tests="0" failures="0" errors="0" skipped="0"/>
                        """;
            };
            Files.writeString(
                    reports.resolve("TEST-" + className + ".xml"), xml, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write surefire fixture", ex);
        }
    }
}

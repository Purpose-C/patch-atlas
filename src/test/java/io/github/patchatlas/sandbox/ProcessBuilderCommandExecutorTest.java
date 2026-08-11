package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessBuilderCommandExecutorTest {

    @Test
    void capturesOnlyBoundedTailOfCombinedOutput() {
        ProcessBuilderCommandExecutor executor = new ProcessBuilderCommandExecutor();

        CommandExecution execution = executor.execute(
                javaCommand("output"), Duration.ofSeconds(5), 32);

        assertThat(execution.exitCode()).isZero();
        assertThat(execution.timedOut()).isFalse();
        assertThat(execution.output()).hasSizeLessThanOrEqualTo(32).endsWith("TAIL");
        assertThat(execution.output()).startsWith("[truncated]");
    }

    @Test
    void killsHostProcessWhenWallClockTimeoutExpires() {
        ProcessBuilderCommandExecutor executor = new ProcessBuilderCommandExecutor();

        CommandExecution execution = executor.execute(
                javaCommand("sleep"), Duration.ofMillis(100), 1024);

        assertThat(execution.timedOut()).isTrue();
        assertThat(execution.exitCode()).isNull();
        assertThat(execution.elapsed()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void reportsStartFailureWithoutThrowing() {
        ProcessBuilderCommandExecutor executor = new ProcessBuilderCommandExecutor();

        CommandExecution execution = executor.execute(
                List.of("/patch-atlas/command-that-does-not-exist"),
                Duration.ofSeconds(1),
                1024);

        assertThat(execution.exitCode()).isNull();
        assertThat(execution.startFailure()).isNotBlank();
    }

    private static List<String> javaCommand(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                OutputProcess.class.getName(),
                mode);
    }

    public static final class OutputProcess {

        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) {
                Thread.sleep(10_000);
                return;
            }
            System.out.print("x".repeat(200));
            System.err.print("TAIL");
        }
    }
}

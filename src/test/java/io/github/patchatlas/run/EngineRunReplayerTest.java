package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.FakeSandboxRunner;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineRunReplayerTest {

    @Test
    void formalReplayBuildsCommandFromPersistedExecutionPolicy(@TempDir Path root) {
        FakeSandboxRunner sandbox = new FakeSandboxRunner();
        sandbox.enqueue(completed(), passingReport());
        sandbox.enqueue(completed(), passingReport());
        TargetTest target = new TargetTest("fixtures.ATest", "reproduces");
        PersistedCandidatePatch candidate =
                PersistedCandidatePatch.fromAccepted("patch", target);
        ClaimedRun claimed = new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.REPLAYING,
                1,
                new RunLease(UUID.randomUUID(), "test", Instant.now().plusSeconds(60)),
                0,
                1,
                Optional.of(candidate));
        MavenExecutionPolicy policy = new MavenExecutionPolicy("11", MavenNetworkMode.ONLINE);

        var result = new EngineRunReplayer(new SideReplayRunner(sandbox, root))
                .replay(claimed, candidate, new PreparedReplayWorkspace.Live(root, "", policy));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.NOT_REPRODUCED);
        assertThat(sandbox.executedCommands()).hasSize(2).allSatisfy(command -> {
            MavenTestCommand testCommand = (MavenTestCommand) command;
            assertThat(testCommand.javaVersion()).isEqualTo("11");
            assertThat(testCommand.networkMode()).isEqualTo(MavenNetworkMode.ONLINE);
        });
    }

    private static SandboxExecution completed() {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                0,
                Duration.ofMillis(1),
                false,
                List.of("mvn", "test"),
                "ok",
                "maven:3.9-eclipse-temurin-11",
                SandboxLimits.defaults(),
                MavenNetworkMode.ONLINE);
    }

    private static String passingReport() {
        return """
                <testsuite tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="fixtures.ATest" name="reproduces"/>
                </testsuite>
                """;
    }
}

package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 装配入口：无效根失败；create/of 都交出可构造的 Worker 与 Replay 协调器。 */
class Issue2TestRuntimeTest {

    @TempDir
    Path temp;

    @Test
    void createRejectsMissingWorkspaceRoot() {
        Path missing = temp.resolve("no-such-root");
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GenerationCallFailure(
                CallFailureCategory.MODEL_UNAVAILABLE, "unused"));
        assertThatThrownBy(() -> Issue2TestRuntime.create(
                        generator,
                        missing,
                        (workspace, command) -> ScriptedSandboxRunner.completed(0),
                        LocalGitFixture.fetcher(temp)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace root must be an existing directory");
    }

    @Test
    void createAndOfBothProduceWorker() throws Exception {
        Path root = Files.createDirectories(temp.resolve("ws"));
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GenerationCallFailure(
                CallFailureCategory.MODEL_UNAVAILABLE, "unused"));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(0));
        PostgresRunStore store = new PostgresRunStore(unusedDataSource());

        Issue2TestRuntime created =
                Issue2TestRuntime.create(generator, root, sandbox, LocalGitFixture.fetcher(root));
        assertThat(created.replayCoordinator()).isInstanceOf(FormalReplayCoordinator.class);
        assertThat(created.worker(
                        store, Issue2TestWorker.DEFAULT_LEASE, Issue2TestWorker.DEFAULT_HEARTBEAT))
                .isInstanceOf(Issue2TestWorker.class);

        SideReplayRunner side = new SideReplayRunner(sandbox, root);
        Issue2TestRuntime fromParts = Issue2TestRuntime.of(
                generator,
                new PatchGate(root),
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(root)),
                new DependencyWarmupRunner(sandbox, root),
                side,
                new EngineRunReplayer(side));
        assertThat(fromParts.replayCoordinator()).isInstanceOf(FormalReplayCoordinator.class);
        assertThat(fromParts.worker(
                        store, Issue2TestWorker.DEFAULT_LEASE, Issue2TestWorker.DEFAULT_HEARTBEAT))
                .isInstanceOf(Issue2TestWorker.class);
    }

    @Test
    void createUsesProvidedReplayerAndSeesAppliedCandidate() throws Exception {
        Path root = Files.createDirectories(temp.resolve("ws-override"));
        LocalGitFixture.Fixture live = LocalGitFixture.initWithExistingTest(temp.resolve("git-live"));
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GenerationCallFailure(
                CallFailureCategory.MODEL_UNAVAILABLE, "unused"));
        AtomicBoolean overrideUsed = new AtomicBoolean();
        RunReplayer override = (claimed, candidate, workspace) -> {
            Path dir = ((PreparedReplayWorkspace.Live) workspace).workspace();
            Path testFile = dir.resolve("src/test/java/fixtures/OldTest.java");
            try {
                if (!Files.exists(testFile)
                        || !Files.readString(testFile, StandardCharsets.UTF_8).contains("void added()")) {
                    throw new IllegalStateException(
                            "replay workspace missing applied candidate under " + dir);
                }
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            overrideUsed.set(true);
            return liveResult(candidate);
        };
        Issue2TestRuntime runtime = Issue2TestRuntime.create(
                generator,
                root,
                ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(0)),
                LocalGitFixture.fetcher(live.originDir()),
                SandboxExecutionObserver.NOOP,
                override);

        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH,
                new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD));
        ClaimedRun claimed = new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.REPLAYING,
                1L,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.of(candidate));
        InMemoryReplayRunSession session = new InMemoryReplayRunSession(
                claimed,
                new ReplayWorkspaceProjection.Live(
                        "https://github.com/ex/repo.git", live.buggySha(), ""));

        RunDetails details = runtime.replayCoordinator().run(claimed, session);

        assertThat(overrideUsed).isTrue();
        assertThat(details.state()).isEqualTo(RunState.COMPLETED);
        assertThat(details.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
    }

    private static ReplayResult liveResult(PersistedCandidatePatch candidate) {
        AttemptRecord a = AttemptRecord.executed(
                new SandboxExecution(
                        SandboxExecutionStatus.COMPLETED,
                        1,
                        Duration.ofMillis(1),
                        false,
                        List.of("mvn", "test"),
                        "log",
                        "maven:3.9-eclipse-temurin-21",
                        SandboxLimits.defaults(),
                        MavenNetworkMode.OFFLINE),
                new TestReport(List.of(new TestCaseResult(
                        candidate.targetTest().className(),
                        candidate.targetTest().methodName(),
                        Duration.ofMillis(1),
                        TestCaseStatus.FAILED,
                        "org.opentest4j.AssertionFailedError",
                        "x"))),
                candidate.targetTest());
        return ReplayResult.live(
                ReplayVerdict.REPRODUCTION_CANDIDATE,
                candidate.targetTest(),
                new SideExecutionResult(List.of(a, a)));
    }

    private static javax.sql.DataSource unusedDataSource() {
        return new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override
            public java.sql.Connection getConnection() {
                throw new UnsupportedOperationException("assembly test does not open a database");
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException("assembly test does not open a database");
            }
        };
    }
}

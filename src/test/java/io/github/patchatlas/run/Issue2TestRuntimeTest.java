package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
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

package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Formal Replay 编排：内存 session + 真实 Gate + 本地 fixture。默认离线，无 PostgreSQL。
 */
class FormalReplayCoordinatorTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);

    private static final String MAIN_SOURCE_PATCH =
            """
            diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
            new file mode 100644
            --- /dev/null
            +++ b/src/main/java/Foo.java
            @@ -0,0 +1,3 @@
            +package fixtures;
            +class Foo {}
            """;

    @TempDir
    Path temp;

    private Path workspaceRoot;
    private LocalGitFixture.Fixture liveFixture;

    @BeforeEach
    void setUp() throws Exception {
        workspaceRoot = Files.createDirectories(temp.resolve("ws"));
        liveFixture = LocalGitFixture.initWithExistingTest(temp.resolve("git-live"));
    }

    @Test
    void liveCompleteAppliesCandidateAndRecordsVerdict() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        InMemoryReplayRunSession session = liveSession(claimed);
        FormalReplayCoordinator coordinator = coordinator(
                factory(liveFixture), successfulWarmup(), FormalReplayCoordinatorTest::liveReplay);

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.state()).isEqualTo(RunState.COMPLETED);
        assertThat(details.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(session.openRoundCount()).isEqualTo(1);
        assertThat(session.terminal()).contains(details);
    }

    @Test
    void patchGateRejectionFailsWithoutCallingReplayer() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(MAIN_SOURCE_PATCH, TARGET));
        InMemoryReplayRunSession session = liveSession(claimed);
        AtomicReplayer replayer = new AtomicReplayer();
        FormalReplayCoordinator coordinator =
                coordinator(factory(liveFixture), successfulWarmup(), replayer);

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure()).isPresent();
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.PATCH_GATE);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.PATCH_REJECTED);
        assertThat(replayer.calls).isZero();
        assertThat(session.openRoundCount()).isEqualTo(1);
    }

    @Test
    void warmupFailureIsReplaySystemError() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        InMemoryReplayRunSession session = liveSession(claimed);
        AtomicReplayer replayer = new AtomicReplayer();
        FormalReplayCoordinator coordinator =
                coordinator(factory(liveFixture), failingWarmup(), replayer);

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.REPLAY);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.REPLAY_SYSTEM_ERROR);
        assertThat(details.failure().orElseThrow().summary()).contains("dependency warmup");
        assertThat(replayer.calls).isZero();
    }

    @Test
    void workspacePrepareExceptionIsWorkspaceErrorWithoutMessage() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        InMemoryReplayRunSession session = liveSession(claimed);
        FormalReplayCoordinator coordinator = coordinator(
                throwingFactory("disk-full-secret"), successfulWarmup(), FormalReplayCoordinatorTest::liveReplay);

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.WORKSPACE);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.WORKSPACE_ERROR);
        assertThat(details.failure().orElseThrow().summary())
                .isEqualTo("replay workspace prepare failed: IOException");
        assertThat(details.failure().orElseThrow().summary()).doesNotContain("disk-full-secret");
    }

    @Test
    void diagnosticWorkspaceFailureEchoesExceptionMessage() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        InMemoryReplayRunSession session = new InMemoryReplayRunSession(
                claimed, liveProjection(), RunPurpose.DIAGNOSTIC);
        FormalReplayCoordinator coordinator = coordinator(
                throwingFactory("disk-full-secret"), successfulWarmup(), FormalReplayCoordinatorTest::liveReplay);

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.failure().orElseThrow().summary())
                .isEqualTo("replay workspace prepare failed: IOException: disk-full-secret");
    }

    @Test
    void replayExceptionIsReplaySystemError() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        InMemoryReplayRunSession session = liveSession(claimed);
        FormalReplayCoordinator coordinator = coordinator(
                factory(liveFixture),
                successfulWarmup(),
                (c, candidate, workspace) -> {
                    throw new RuntimeException("docker boom");
                });

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.REPLAY);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.REPLAY_SYSTEM_ERROR);
        assertThat(details.failure().orElseThrow().summary()).isEqualTo("replay failed: RuntimeException");
        assertThat(details.failure().orElseThrow().summary()).doesNotContain("docker boom");
    }

    @Test
    void staleCompletePropagates() throws Exception {
        ClaimedRun claimed = liveClaim(PersistedCandidatePatch.fromAccepted(
                LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        InMemoryReplayRunSession delegate = liveSession(claimed);
        ReplayRunSession staleOnComplete = new ReplayRunSession() {
            @Override
            public Opened openRound() {
                return delegate.openRound();
            }

            @Override
            public RunDetails complete(ReplayResult result) {
                throw new StaleClaimException(claimed.runId(), "fenced");
            }

            @Override
            public RunDetails fail(RunFailure failure) {
                return delegate.fail(failure);
            }
        };
        FormalReplayCoordinator coordinator = coordinator(
                factory(liveFixture), successfulWarmup(), FormalReplayCoordinatorTest::liveReplay);

        assertThatThrownBy(() -> coordinator.run(claimed, staleOnComplete))
                .isInstanceOf(StaleClaimException.class)
                .hasMessageContaining("fenced");
    }

    @Test
    void historicalKnownTriggerAppliesOnlyToBuggy() throws Exception {
        LocalGitFixture.Fixture known =
                LocalGitFixture.initHistoricalWithKnownTrigger(temp.resolve("git-trigger"));
        PersistedCandidatePatch trigger =
                PersistedCandidatePatch.fromKnownTrigger(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET);
        ClaimedRun claimed = historicalClaim(trigger);
        InMemoryReplayRunSession session = new InMemoryReplayRunSession(
                claimed, historicalProjection(known), RunPurpose.CALIBRATION);
        FormalReplayCoordinator coordinator = coordinator(
                factory(known), successfulWarmup(), FormalReplayCoordinatorTest::historicalReplay);

        RunDetails details = coordinator.run(claimed, session);

        assertThat(details.state()).isEqualTo(RunState.COMPLETED);
        assertThat(details.verdict()).contains(ReplayVerdict.VALID_REPRODUCTION);
    }

    private FormalReplayCoordinator coordinator(
            CandidateWorkspaceFactory workspaces,
            DependencyWarmupRunner warmup,
            RunReplayer replayer) {
        return new FormalReplayCoordinator(
                new PatchGate(workspaceRoot), workspaces, warmup, replayer);
    }

    private CandidateWorkspaceFactory factory(LocalGitFixture.Fixture fixture) {
        return new TempCandidateWorkspaceFactory(workspaceRoot, LocalGitFixture.fetcher(fixture.originDir()));
    }

    private static CandidateWorkspaceFactory throwingFactory(String message) {
        return new CandidateWorkspaceFactory() {
            @Override
            public WorkspaceSession openForRevision(
                    ClaimedRun run,
                    String repositoryUrl,
                    String revision,
                    String modulePath,
                    io.github.patchatlas.sandbox.MavenExecutionPolicy executionPolicy)
                    throws Exception {
                throw new IOException(message);
            }
        };
    }

    private DependencyWarmupRunner successfulWarmup() {
        return new DependencyWarmupRunner(
                (workspace, command) -> ScriptedSandboxRunner.completed(0), workspaceRoot);
    }

    private DependencyWarmupRunner failingWarmup() {
        return new DependencyWarmupRunner(
                (workspace, command) -> ScriptedSandboxRunner.completed(1), workspaceRoot);
    }

    private InMemoryReplayRunSession liveSession(ClaimedRun claimed) {
        return new InMemoryReplayRunSession(claimed, liveProjection());
    }

    private ReplayWorkspaceProjection liveProjection() {
        return new ReplayWorkspaceProjection.Live(
                "https://github.com/ex/repo.git", liveFixture.buggySha(), "");
    }

    private ReplayWorkspaceProjection historicalProjection(LocalGitFixture.Fixture fixture) {
        return new ReplayWorkspaceProjection.Historical(
                "https://github.com/ex/repo.git",
                fixture.buggySha(),
                fixture.fixedSha(),
                "");
    }

    private static ClaimedRun liveClaim(PersistedCandidatePatch candidate) {
        return new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.REPLAYING,
                1L,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.of(candidate));
    }

    private static ClaimedRun historicalClaim(PersistedCandidatePatch candidate) {
        return new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.HISTORICAL,
                RunState.REPLAYING,
                1L,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.of(candidate));
    }

    private static ReplayResult liveReplay(
            ClaimedRun claimed, PersistedCandidatePatch candidate, PreparedReplayWorkspace workspace) {
        Path dir = ((PreparedReplayWorkspace.Live) workspace).workspace();
        assertPatched(dir);
        return liveResult(candidate);
    }

    private static ReplayResult historicalReplay(
            ClaimedRun claimed, PersistedCandidatePatch candidate, PreparedReplayWorkspace workspace) {
        PreparedReplayWorkspace.Historical historical = (PreparedReplayWorkspace.Historical) workspace;
        assertPatched(historical.buggyWorkspace());
        assertPatched(historical.fixedWorkspace());
        return historicalValidResult(candidate);
    }

    private static void assertPatched(Path workspace) {
        Path testFile = workspace.resolve("src/test/java/fixtures/OldTest.java");
        try {
            assertThat(Files.readString(testFile, StandardCharsets.UTF_8)).contains("void added()");
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static ReplayResult liveResult(PersistedCandidatePatch candidate) {
        AttemptRecord a = AttemptRecord.executed(
                completed(1),
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

    private static ReplayResult historicalValidResult(PersistedCandidatePatch candidate) {
        AttemptRecord fail = AttemptRecord.executed(
                completed(1),
                new TestReport(List.of(new TestCaseResult(
                        candidate.targetTest().className(),
                        candidate.targetTest().methodName(),
                        Duration.ofMillis(1),
                        TestCaseStatus.FAILED,
                        "org.opentest4j.AssertionFailedError",
                        "x"))),
                candidate.targetTest());
        AttemptRecord pass = AttemptRecord.executed(
                completed(0),
                new TestReport(List.of(new TestCaseResult(
                        candidate.targetTest().className(),
                        candidate.targetTest().methodName(),
                        Duration.ofMillis(1),
                        TestCaseStatus.PASSED,
                        null,
                        null))),
                candidate.targetTest());
        return ReplayResult.historicalWithFixed(
                ReplayVerdict.VALID_REPRODUCTION,
                candidate.targetTest(),
                new SideExecutionResult(List.of(fail, fail)),
                new SideExecutionResult(List.of(pass, pass)));
    }

    private static SandboxExecution completed(int exit) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exit,
                Duration.ofMillis(1),
                false,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static final class AtomicReplayer implements RunReplayer {
        private int calls;

        @Override
        public ReplayResult replay(
                ClaimedRun claimed,
                PersistedCandidatePatch candidate,
                PreparedReplayWorkspace workspace) {
            calls++;
            return liveResult(candidate);
        }
    }
}

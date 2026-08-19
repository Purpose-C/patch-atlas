package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.SourceSnapshot;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Formal Replay 的 finish_reason 来自 {@code verification_run.model_finish_reason}，
 * 不由 {@code AGENT_GENERATED} 推导。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class FormalReplayCoordinatorPersistenceTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);

    private static final String WRONG_HEADER_COUNTS_PATCH =
            """
            diff --git a/src/test/java/fixtures/OldTest.java b/src/test/java/fixtures/OldTest.java
            --- a/src/test/java/fixtures/OldTest.java
            +++ b/src/test/java/fixtures/OldTest.java
            @@ -6,99 +6,99 @@
               @Test
               void already() {}
            +
            +  @Test
            +  void added() {}
            """;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @TempDir
    Path tempRoot;

    private PostgresRunStore store;
    private LocalGitFixture.Fixture fixture;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        store = new PostgresRunStore(dataSource());
        fixture = LocalGitFixture.initWithExistingTest(tempRoot.resolve("git"));
        workspaceRoot = Files.createDirectories(tempRoot.resolve("workspaces"));
    }

    @Test
    void storedLengthRejectsAsTruncatedNotRecount() throws Exception {
        ClaimedRun replaying = replayingAfter(CompletionDiagnostics.of("length", "0", "10"));
        AtomicInteger replayCalls = new AtomicInteger();

        RunDetails details = replay(replaying, countingReplayer(replayCalls));

        assertThat(replaying.candidate().orElseThrow().provenance())
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.PATCH_GATE);
        assertThat(details.failure().orElseThrow().summary()).contains("响应被截断");
        assertThat(details.failure().orElseThrow().summary()).doesNotContain("hunk");
        assertThat(replayCalls).hasValue(0);
    }

    @Test
    void storedUnknownDoesNotRelaxAgentGeneratedHeaderMismatch() throws Exception {
        ClaimedRun replaying = replayingAfter(CompletionDiagnostics.unknown());
        AtomicInteger replayCalls = new AtomicInteger();

        RunDetails details = replay(replaying, countingReplayer(replayCalls));

        assertThat(replaying.candidate().orElseThrow().provenance())
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().summary()).contains("hunk");
        assertThat(details.failure().orElseThrow().summary()).doesNotContain("响应被截断");
        assertThat(replayCalls).hasValue(0);
    }

    @Test
    void storedStopRecountsAgentGeneratedWrongHeader() throws Exception {
        ClaimedRun replaying = replayingAfter(CompletionDiagnostics.of("stop", "0", "10"));

        RunDetails details = replay(replaying, FormalReplayCoordinatorPersistenceTest::liveReplay);

        assertThat(replaying.completionDiagnostics().finishReason()).isEqualTo("stop");
        assertThat(details.state()).isEqualTo(RunState.COMPLETED);
        assertThat(details.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
    }

    @Test
    void sameAgentGeneratedLengthAndStopBehaveDifferently() throws Exception {
        ClaimedRun lengthClaim = replayingAfter(CompletionDiagnostics.of("length", "0", "10"));
        ClaimedRun stopClaim = replayingAfter(CompletionDiagnostics.of("stop", "0", "10"));
        assertThat(lengthClaim.candidate().orElseThrow().provenance())
                .isEqualTo(stopClaim.candidate().orElseThrow().provenance())
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);

        RunDetails length = replay(lengthClaim, FormalReplayCoordinatorPersistenceTest::liveReplay);
        RunDetails stop = replay(stopClaim, FormalReplayCoordinatorPersistenceTest::liveReplay);

        assertThat(length.state()).isEqualTo(RunState.FAILED);
        assertThat(length.failure().orElseThrow().summary()).contains("响应被截断");
        assertThat(stop.state()).isEqualTo(RunState.COMPLETED);
        assertThat(stop.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
    }

    private ClaimedRun replayingAfter(CompletionDiagnostics diagnostics) {
        store.submit(liveSubmission("finish-" + diagnostics.finishReason() + "-" + System.nanoTime()));
        ClaimedRun generating = LocatingTestSupport.commitPinned(
                store, store.claimNext("worker", Duration.ofMinutes(5)).orElseThrow());
        ClaimedRun replaying;
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(generating),
                "worker",
                Issue2TestWorker.DEFAULT_LEASE,
                Issue2TestWorker.DEFAULT_HEARTBEAT)) {
            GenerationRunSession session =
                    new LeaseHeartbeatGenerationRunSession(beat, RunPurpose.STANDARD);
            session.reserveGenerationAttempt("fake", "fixture-v1");
            session.recordModelUsage(new ModelUsage(1, 2, 3), diagnostics);
            replaying = session.commitCandidate(GatedCandidateTestHelper.gated(
                    PersistedCandidatePatch.fromAccepted(WRONG_HEADER_COUNTS_PATCH, TARGET)));
        }
        assertThat(replaying.completionDiagnostics().finishReason())
                .isEqualTo(diagnostics.finishReason());
        return replaying;
    }

    private RunDetails replay(ClaimedRun replaying, RunReplayer replayer) {
        FormalReplayCoordinator coordinator = new FormalReplayCoordinator(
                new PatchGate(workspaceRoot),
                new TempCandidateWorkspaceFactory(
                        workspaceRoot, LocalGitFixture.fetcher(fixture.originDir())),
                new DependencyWarmupRunner(
                        (workspace, command) -> ScriptedSandboxRunner.completed(0), workspaceRoot),
                replayer);
        try (ReplayRunSession session = LeaseHeartbeatReplayRunSession.open(
                store,
                replaying,
                "worker",
                Issue2TestWorker.DEFAULT_LEASE,
                Issue2TestWorker.DEFAULT_HEARTBEAT)) {
            return coordinator.run(replaying, session);
        }
    }

    private static RunReplayer countingReplayer(AtomicInteger calls) {
        return (claimed, candidate, workspace) -> {
            calls.incrementAndGet();
            return liveResult(candidate);
        };
    }

    private RunSubmission liveSubmission(String caseId) {
        return new RunSubmission(
                VerificationMode.LIVE,
                caseId,
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                fixture.buggySha(),
                null,
                "",
                "21",
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }

    private static ReplayResult liveReplay(
            ClaimedRun claimed, PersistedCandidatePatch candidate, PreparedReplayWorkspace workspace) {
        Path dir = ((PreparedReplayWorkspace.Live) workspace).workspace();
        Path testFile = dir.resolve("src/test/java/fixtures/OldTest.java");
        try {
            assertThat(Files.readString(testFile, StandardCharsets.UTF_8)).contains("void added()");
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        return liveResult(candidate);
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

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

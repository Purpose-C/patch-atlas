package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.DependencyWarmupRunner;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 两实例恢复：真实 Gate + 本地 fixture clone/checkout；修改既有文件 patch。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class Issue2TestWorkerRecoveryTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @TempDir
    Path tempRoot;

    private LocalGitFixture.Fixture fixture;
    private Path workspaceRoot;
    private final List<Path> materializedWorkspaces = new ArrayList<>();

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
        fixture = LocalGitFixture.initWithExistingTest(tempRoot.resolve("git"));
        workspaceRoot = Files.createDirectories(tempRoot.resolve("workspaces"));
        materializedWorkspaces.clear();
    }

    @Test
    void recoveringAfterGeneratingCrashInvokesGeneratorAgain() throws Exception {
        AtomicInteger generateCalls = new AtomicInteger();
        PostgresRunStore storeA = new PostgresRunStore(dataSource());
        PostgresRunStore storeB = new PostgresRunStore(dataSource());

        UUID runId = storeA.submit(liveSubmission("gen-crash"));

        ClaimedRun abandoned = storeA.claimNext("instance-a", Duration.ofMinutes(5)).orElseThrow();
        assertThat(abandoned.state()).isEqualTo(RunState.LOCATING);
        expireLease(runId);

        Issue2TestWorker workerB = worker(storeB, generateCalls);
        RunDetails completed = workerB.processNext("instance-b").orElseThrow();

        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(completed.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(generateCalls.get()).isEqualTo(1);
        assertThat(materializedWorkspaces).isNotEmpty();
    }

    @Test
    void recoveringAfterReplayingCrashDoesNotCallGeneratorAgainAndUsesFreshWorkspace()
            throws Exception {
        AtomicInteger generateCalls = new AtomicInteger();
        PostgresRunStore storeA = new PostgresRunStore(dataSource());
        PostgresRunStore storeB = new PostgresRunStore(dataSource());

        UUID runId = storeA.submit(liveSubmission("replay-crash"));

        // 实例 A：真实 materialize + Gate 后提交 candidate，再崩溃
        TestGenerator generatorA = trackingGenerator(generateCalls);
        ClaimedRun claimed = LocatingTestSupport.commitPinned(
                storeA, storeA.claimNext("instance-a", Duration.ofMinutes(5)).orElseThrow());
        GenerationInput input = storeA.loadGenerationInput(claimed.runId());
        CandidateDraft draft = new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET);
        generateCalls.incrementAndGet();
        PatchGate gate = new PatchGate(workspaceRoot);
        Path firstWorkspace;
        try (var session = cloningFactory().open(claimed, input)) {
            firstWorkspace = session.workspace();
            var prepared = gate.prepare(session.workspace(), "", draft, MavenNetworkMode.OFFLINE);
            assertThat(prepared)
                    .isInstanceOf(io.github.patchatlas.agent.PatchPreparationResult.PreparedCandidate.class);
            // 污染工作区：模拟崩溃前留下已打 patch 的文件
            assertThat(Files.readString(firstWorkspace.resolve("src/test/java/fixtures/OldTest.java")))
                    .contains("void added()");
            GatedCandidate gated = GatedCandidate.afterSuccessfulGate(
                    draft,
                    (io.github.patchatlas.agent.PatchPreparationResult.PreparedCandidate) prepared);
            ClaimedRun renewed =
                    storeA.renewLease(ClaimHandle.from(claimed), "instance-a", Duration.ofMinutes(5));
            ClaimedRun replaying = storeA.commitCandidate(ClaimHandle.from(renewed), gated);
            assertThat(replaying.state()).isEqualTo(RunState.REPLAYING);
        }
        // session close 删除 firstWorkspace
        assertThat(firstWorkspace).doesNotExist();
        assertThat(generateCalls.get()).isEqualTo(1);
        expireLease(runId);

        AtomicInteger generateCallsB = new AtomicInteger();
        int workspacesBeforeB = materializedWorkspaces.size();
        Issue2TestWorker workerB = worker(storeB, generateCallsB);
        // B 接管 REPLAYING：不再 generate，但必须重新 materialize + 再应用 candidate
        RunDetails completed = workerB.processNext("instance-b").orElseThrow();

        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(generateCallsB.get()).isZero();
        assertThat(generateCalls.get()).isEqualTo(1);
        // 恢复路径再 materialize 一次，且与 A 的目录不同
        assertThat(materializedWorkspaces).hasSize(workspacesBeforeB + 1);
        Path recoveryWorkspace = materializedWorkspaces.get(materializedWorkspaces.size() - 1);
        assertThat(recoveryWorkspace).isNotEqualTo(firstWorkspace);
        // Fake replayer 已断言 patch 在新 workspace 上被再次应用
        assertThat(completed.candidate()).isPresent();
    }

    @Test
    void twoGeneratingAttemptsMaterializeDistinctCleanWorkspaces() throws Exception {
        AtomicInteger generateCalls = new AtomicInteger();
        PostgresRunStore store = new PostgresRunStore(dataSource());
        // 两次独立 Live run
        store.submit(liveSubmission("ws-1"));
        store.submit(liveSubmission("ws-2"));

        Issue2TestWorker worker = worker(store, generateCalls);
        worker.processNext("solo").orElseThrow();
        worker.processNext("solo").orElseThrow();

        // 每个 Run：generate 一次 materialize + replay 再一次 = 2；两 Run 共 4
        assertThat(materializedWorkspaces).hasSize(4);
        assertThat(materializedWorkspaces.stream().distinct().count()).isEqualTo(4);
        // close 后均已清理
        for (Path ws : materializedWorkspaces) {
            assertThat(ws).doesNotExist();
        }
        assertThat(generateCalls.get()).isEqualTo(2);
    }

    @Test
    void singleInstanceHappyPathUsesRealPatchGateOnExistingFile() throws Exception {
        AtomicInteger generateCalls = new AtomicInteger();
        PostgresRunStore store = new PostgresRunStore(dataSource());
        store.submit(liveSubmission("happy"));

        Issue2TestWorker worker = worker(store, generateCalls);
        RunDetails completed = worker.processNext("solo").orElseThrow();

        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(generateCalls.get()).isEqualTo(1);
        assertThat(store.claimNext("solo", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void rejectsMainSourcePatchAtGateAndExhaustsAfterThreeAttempts() {
        AtomicInteger generateCalls = new AtomicInteger();
        String mainPatch =
                """
                diff --git a/src/main/java/Evil.java b/src/main/java/Evil.java
                new file mode 100644
                --- /dev/null
                +++ b/src/main/java/Evil.java
                @@ -0,0 +1,2 @@
                +package x;
                +class Evil {}
                """;
        TestGenerator generator = new FakeTestGenerator(new GenerationResult.GeneratedDraft(
                new CandidateDraft(mainPatch, new TargetTest("x.Evil", "nope")))) {
            @Override
            public GenerationResult generate(GenerationRequest request) {
                generateCalls.incrementAndGet();
                return super.generate(request);
            }
        };
        PostgresRunStore store = new PostgresRunStore(dataSource());
        store.submit(liveSubmission("evil"));

        SideReplayRunner side = new SideReplayRunner(
                ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1)), workspaceRoot);
        Issue2TestWorker worker = configuredWorker(
                store,
                generator,
                new PatchGate(workspaceRoot),
                cloningFactory(),
                successfulWarmup(workspaceRoot),
                side,
                Issue2TestWorkerRecoveryTest::fakeLiveReplay);

        RunDetails details = worker.processNext("w").orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure()).isPresent();
        // 越界路径现在视为可修正：三轮 Gate 拒绝后终态为 GENERATION_EXHAUSTED
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.GENERATION);
        assertThat(details.failure().orElseThrow().category()).isEqualTo(FailureCategory.GENERATION_EXHAUSTED);
        assertThat(details.candidate()).isEmpty();
        assertThat(generateCalls.get()).isEqualTo(3);
    }

    private Issue2TestWorker worker(PostgresRunStore store, AtomicInteger generateCalls) {
        SideReplayRunner side = new SideReplayRunner(
                ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1)), workspaceRoot);
        return configuredWorker(
                store,
                trackingGenerator(generateCalls),
                new PatchGate(workspaceRoot),
                cloningFactory(),
                successfulWarmup(workspaceRoot),
                side,
                Issue2TestWorkerRecoveryTest::fakeLiveReplay);
    }

    private TempCandidateWorkspaceFactory cloningFactory() {
        RepositoryWorkspaceFetcher base = LocalGitFixture.fetcher(fixture.originDir());
        RepositoryWorkspaceFetcher recording = (url, sha, parent, name) -> {
            Path workspace = base.materialize(url, sha, parent, name);
            String content = Files.readString(
                    workspace.resolve("src/test/java/fixtures/OldTest.java"), StandardCharsets.UTF_8);
            if (content.contains("void added()")) {
                throw new IllegalStateException("workspace not clean: leftover patch content");
            }
            LocalGitFixture.assertHead(workspace, sha);
            materializedWorkspaces.add(workspace);
            return workspace;
        };
        return new TempCandidateWorkspaceFactory(workspaceRoot, recording);
    }

    private TestGenerator trackingGenerator(AtomicInteger generateCalls) {
        GenerationResult draft = new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
        return new FakeTestGenerator(draft) {
            @Override
            public GenerationResult generate(GenerationRequest request) {
                generateCalls.incrementAndGet();
                return super.generate(request);
            }
        };
    }

    private static ReplayResult fakeLiveReplay(
            ClaimedRun claimed, PersistedCandidatePatch candidate, PreparedReplayWorkspace workspace) {
        return switch (workspace) {
            case PreparedReplayWorkspace.Live live -> {
                assertPatched(live.workspace());
                assertThat(live.workspace().getFileName().toString())
                        .isNotEqualTo(claimed.runId().toString());
                yield liveResult(candidate);
            }
            case PreparedReplayWorkspace.Historical historical -> {
                assertPatched(historical.buggyWorkspace());
                assertPatched(historical.fixedWorkspace());
                assertThat(historical.buggyWorkspace()).isNotEqualTo(historical.fixedWorkspace());
                yield historicalValidResult(candidate);
            }
        };
    }

    private static void assertPatched(Path workspace) {
        Path testFile = workspace.resolve("src/test/java/fixtures/OldTest.java");
        assertThat(testFile).exists();
        try {
            assertThat(Files.readString(testFile, StandardCharsets.UTF_8)).contains("void added()");
        } catch (java.io.IOException ex) {
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
        SideExecutionResult primary = new SideExecutionResult(List.of(a, a));
        return ReplayResult.live(ReplayVerdict.REPRODUCTION_CANDIDATE, candidate.targetTest(), primary);
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
        SideExecutionResult buggy = new SideExecutionResult(List.of(fail, fail));
        SideExecutionResult fixed = new SideExecutionResult(List.of(pass, pass));
        return ReplayResult.historicalWithFixed(
                ReplayVerdict.VALID_REPRODUCTION, candidate.targetTest(), buggy, fixed);
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

    @Test
    void historicalReplayMaterializesBuggyAndFixedWorkspaces() throws Exception {
        LocalGitFixture.Fixture hist =
                LocalGitFixture.initHistoricalWithExistingTest(tempRoot.resolve("hist-git"));
        Path histRoot = Files.createDirectories(tempRoot.resolve("hist-ws"));
        List<Path> histWorkspaces = new ArrayList<>();
        RepositoryWorkspaceFetcher histFetcher = (url, sha, parent, name) -> {
            Path workspace = LocalGitFixture.fetcher(hist.originDir()).materialize(url, sha, parent, name);
            LocalGitFixture.assertHead(workspace, sha);
            histWorkspaces.add(workspace);
            return workspace;
        };

        PostgresRunStore store = new PostgresRunStore(dataSource());
        store.submit(new RunSubmission(
                VerificationMode.HISTORICAL,
                "hist-1",
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                hist.buggySha(),
                hist.fixedSha(),
                "",
                "21",
                List.of(new SourceSnapshot("src/A.java", "class A {}"))));

        AtomicInteger generateCalls = new AtomicInteger();
        SideReplayRunner side = new SideReplayRunner(
                ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1)), histRoot);
        Issue2TestWorker worker = configuredWorker(
                store,
                trackingGenerator(generateCalls),
                new PatchGate(histRoot),
                new TempCandidateWorkspaceFactory(histRoot, histFetcher),
                successfulWarmup(histRoot),
                side,
                Issue2TestWorkerRecoveryTest::fakeLiveReplay);

        RunDetails completed = worker.processNext("hist-worker").orElseThrow();
        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(completed.verdict()).contains(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(generateCalls.get()).isEqualTo(1);
        // generate 1 + replay buggy + replay fixed = 3
        assertThat(histWorkspaces).hasSize(3);
        assertThat(histWorkspaces.stream().distinct().count()).isEqualTo(3);

        ReplayWorkspaceProjection projection =
                store.loadReplayWorkspaceProjection(completed.runId());
        assertThat(projection).isInstanceOf(ReplayWorkspaceProjection.Historical.class);
        var histProj = (ReplayWorkspaceProjection.Historical) projection;
        assertThat(histProj.fixedRevision()).isEqualTo(hist.fixedSha());
        assertThat(histProj.buggyRevision()).isEqualTo(hist.buggySha());
    }

    @Test
    void calibrationAppliesKnownTriggerOnlyToBuggyAndVerifiesFixed() throws Exception {
        LocalGitFixture.Fixture hist =
                LocalGitFixture.initHistoricalWithKnownTrigger(tempRoot.resolve("calibration-git"));
        Path histRoot = Files.createDirectories(tempRoot.resolve("calibration-ws"));
        PostgresRunStore store = new PostgresRunStore(dataSource());
        RunSubmission submission = new RunSubmission(
                VerificationMode.HISTORICAL,
                "calibration-known-trigger",
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                hist.buggySha(),
                hist.fixedSha(),
                "",
                "21",
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
        PersistedCandidatePatch trigger =
                PersistedCandidatePatch.fromAccepted(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET);
        ClaimedRun replaying = store.startCalibration(
                submission,
                GatedCandidateTestHelper.gated(trigger),
                "calibrator",
                Duration.ofMinutes(5));

        FormalReplayCoordinator coordinator = new FormalReplayCoordinator(
                new PatchGate(histRoot),
                new TempCandidateWorkspaceFactory(histRoot, LocalGitFixture.fetcher(hist.originDir())),
                successfulWarmup(histRoot),
                Issue2TestWorkerRecoveryTest::fakeLiveReplay);
        RunDetails completed;
        try (ReplayRunSession session = LeaseHeartbeatReplayRunSession.open(
                store,
                replaying,
                "calibrator",
                Issue2TestWorker.DEFAULT_LEASE,
                Issue2TestWorker.DEFAULT_HEARTBEAT)) {
            completed = coordinator.run(replaying, session);
        }

        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(completed.verdict()).contains(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(completed.candidate()).get().extracting(PersistedCandidatePatch::provenance)
                .isEqualTo(TestPatchProvenance.KNOWN_TRIGGER);
    }

    @Test
    void replayEngineExceptionIsClassifiedAsReplaySystemError() {
        PostgresRunStore store = new PostgresRunStore(dataSource());
        store.submit(liveSubmission("replay-err"));
        AtomicInteger generateCalls = new AtomicInteger();
        SideReplayRunner side = new SideReplayRunner(
                ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1)), workspaceRoot);
        Issue2TestWorker worker = configuredWorker(
                store,
                trackingGenerator(generateCalls),
                new PatchGate(workspaceRoot),
                cloningFactory(),
                successfulWarmup(workspaceRoot),
                side,
                (claimed, candidate, ws) -> {
                    throw new RuntimeException("docker boom");
                });

        RunDetails details = worker.processNext("err").orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure()).isPresent();
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.REPLAY);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.REPLAY_SYSTEM_ERROR);
        assertThat(generateCalls.get()).isEqualTo(1);
    }

    private static Issue2TestWorker configuredWorker(
            PostgresRunStore store,
            TestGenerator generator,
            PatchGate gate,
            CandidateWorkspaceFactory workspaceFactory,
            DependencyWarmupRunner dependencyWarmupRunner,
            SideReplayRunner sideReplayRunner,
            RunReplayer replayer) {
        return Issue2TestRuntime.of(
                        generator,
                        gate,
                        workspaceFactory,
                        dependencyWarmupRunner,
                        sideReplayRunner,
                        replayer)
                .worker(
                        store,
                        Issue2TestWorker.DEFAULT_LEASE,
                        Issue2TestWorker.DEFAULT_HEARTBEAT);
    }

    private static DependencyWarmupRunner successfulWarmup(Path workspaceRoot) {
        return new DependencyWarmupRunner(
                (workspace, command) -> ScriptedSandboxRunner.completed(0), workspaceRoot);
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

    private void expireLease(UUID runId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    UPDATE verification_run
                       SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE id = '%s'
                    """
                            .formatted(runId));
        }
    }

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

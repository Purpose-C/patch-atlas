package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * LOCATING 领取/接管与 input_schema_version 多版本共存（真实 PostgreSQL）。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class PostgresRunStoreLocatingTest {

    private static final String BUG = "c".repeat(40);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @TempDir
    Path temp;

    private PostgresRunStore store;

    @BeforeEach
    void setUp() {
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
    }

    @Test
    void expiredLocatingLeaseIsReclaimedViaClaimNextWithoutStateRegression() throws Exception {
        UUID id = store.submit(live("locating-reclaim"));
        ClaimedRun ownerA = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        assertThat(ownerA.state()).isEqualTo(RunState.LOCATING);

        expireLease(id);

        ClaimedRun ownerB = store.claimNext("owner-b", Duration.ofMinutes(5)).orElseThrow();
        assertThat(ownerB.runId()).isEqualTo(id);
        assertThat(ownerB.state()).isEqualTo(RunState.LOCATING);
        assertThat(ownerB.lease().owner()).isEqualTo("owner-b");
        assertThat(ownerB.recoveryCount()).isEqualTo(1);
        assertThat(store.findRun(id).orElseThrow().state()).isEqualTo(RunState.LOCATING);
    }

    @Test
    void newSubmitWritesInputSchemaVersion3() throws Exception {
        UUID id = store.submit(live("schema-v3"));
        assertThat(readSchemaVersion(id)).isEqualTo(3);
        GenerationInput input = store.loadGenerationInput(id);
        assertThat(input.sourceSnapshots()).hasSize(1);
    }

    @Test
    void migratedV8RowKeepsInputSchemaVersion1AndRemainsReadable() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("8")
                .load()
                .migrate();

        UUID legacyId = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version, source_snapshots
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      '%s', '', 'QUEUED', 0,
                      '[{"relativePath":"src/A.java","content":"class A {}"}]'::jsonb
                    )
                    """
                            .formatted(legacyId, BUG));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        store = new PostgresRunStore(dataSource());

        assertThat(readSchemaVersion(legacyId)).isEqualTo(1);
        GenerationInput input = store.loadGenerationInput(legacyId);
        assertThat(input.sourceSnapshots()).containsExactly(new SourceSnapshot("src/A.java", "class A {}"));
        assertThat(store.findRun(legacyId).orElseThrow().state()).isEqualTo(RunState.QUEUED);
    }

    @Test
    void locatingCrashThenReclaimRewritesTraceFromZeroWithoutConsumingAttempts() throws Exception {
        UUID id = store.submit(live("locating-rerun"));
        ClaimedRun first = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        store.replaceLocatingTrace(
                ClaimHandle.from(first),
                List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.SELECTION, "src/A.java", "PINNED", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.SELECTION, "src/B.java", "PINNED", "{}")));
        expireLease(id);

        ClaimedRun recovered = store.claimNext("owner-b", Duration.ofMinutes(5)).orElseThrow();
        assertThat(recovered.state()).isEqualTo(RunState.LOCATING);
        assertThat(recovered.recoveryCount()).isEqualTo(1);

        locate(recovered);
        List<LocatingTraceStep> traces = store.loadLocatingTrace(id);
        assertThat(traces).extracting(LocatingTraceStep::seq).containsExactly(0);
        assertThat(traces).extracting(LocatingTraceStep::reason).containsExactly("PINNED");
        assertThat(generationAttemptCount(id)).isZero();
    }

    @Test
    void pinnedPathKeepsSnapshotsByteForByte() throws Exception {
        SourceSnapshot snapshot = new SourceSnapshot("src/A.java", "class A {}");
        UUID id = store.submit(live("pinned", List.of(snapshot)));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        locate(locating);

        assertThat(readOrigin(id)).isEqualTo("PINNED");
        assertThat(store.loadGenerationInput(id).sourceSnapshots()).containsExactly(snapshot);
        assertThat(store.findRun(id).orElseThrow().state()).isEqualTo(RunState.GENERATING);
    }

    @Test
    void heuristicPathWritesSelectionAndExclusionTraces() throws Exception {
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("git"));
        UUID id = store.submit(new RunSubmission(
                VerificationMode.LIVE,
                "heuristic-1",
                "https://github.com/ex/repo.git",
                null,
                null,
                "NPE in fixtures/OldTest.java",
                "class OldTest fails",
                fixture.buggySha(),
                null,
                "",
                "21",
                List.of()));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        Path root = Files.createDirectories(temp.resolve("ws"));
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new io.github.patchatlas.analysis.BuggyRepositoryReader(),
                new io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder());
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(locating),
                locating.lease().owner(),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            coordinator.run(
                    locating,
                    store.loadGenerationInput(id),
                    new LeaseHeartbeatLocatingRunSession(beat),
                    RunPurpose.STANDARD);
        }

        List<LocatingTraceStep> traces = store.loadLocatingTrace(id);
        assertThat(readOrigin(id)).isEqualTo("HEURISTIC");
        assertThat(traces).anyMatch(step -> step.kind() == LocatingStepKind.SELECTION);
        int expected = store.loadGenerationInput(id).sourceSnapshots().size()
                + (int) traces.stream().filter(step -> step.kind() == LocatingStepKind.EXCLUSION).count();
        assertThat(traces).hasSize(expected);
    }

    private void locate(ClaimedRun locating) {
        LocatingCoordinator coordinator = new LocatingCoordinator(
                unusedWorkspaces(),
                new io.github.patchatlas.analysis.BuggyRepositoryReader(),
                new io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder());
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(locating),
                locating.lease().owner(),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            coordinator.run(
                    locating,
                    store.loadGenerationInput(locating.runId()),
                    new LeaseHeartbeatLocatingRunSession(beat),
                    store.findRunDetail(locating.runId()).orElseThrow().purpose());
        }
    }

    @Test
    void locatingUsageDoesNotEnterGenerationRecordedUsageStatus() throws Exception {
        UUID id = store.submit(live("locating-usage"));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        ClaimHandle handle = ClaimHandle.from(locating);
        store.beginLocatingTrace(handle);
        store.recordLocatingUsage(handle, Optional.of(new ModelUsage(5, 7, 12)));
        store.recordLocatingUsage(handle, Optional.of(new ModelUsage(1, 2, 3)));
        store.recordLocatingUsage(handle, Optional.of(new ModelUsage(4, 1, 5)));

        ClaimedRun generating = LocatingTestSupport.commitPinned(store, locating);
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(generating),
                generating.lease().owner(),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            var reserved = beat.reserveGenerationAttempt("fake", "fixture-v1");
            beat.recordModelUsage(new ModelUsage(11, 22, 33));
            assertThat(reserved.ordinal()).isEqualTo(1);
        }

        RunDetailView detail = store.findRunDetail(id).orElseThrow();
        assertThat(detail.generation().usageRecordCount()).isEqualTo(1);
        assertThat(detail.generation().usageStatus())
                .isEqualTo(RecordedUsageStatus.RECORDED_FOR_ALL_ATTEMPTS);
        assertThat(detail.generation().inputTokens()).isEqualTo(11);
        assertThat(detail.locatingUsage().callCount()).isEqualTo(3);
        assertThat(detail.locatingUsage().unknown()).isFalse();
        assertThat(detail.locatingUsage().reportedTokens()).contains(new ModelUsage(10, 10, 20));
        assertThat(generationAttemptCount(id)).isEqualTo(1);
    }

    @Test
    void locatingUsageMissingAnyCallIsUnknownNotZero() {
        UUID id = store.submit(live("locating-usage-unknown"));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        ClaimHandle handle = ClaimHandle.from(locating);
        store.beginLocatingTrace(handle);
        store.recordLocatingUsage(handle, Optional.of(new ModelUsage(5, 5, 10)));
        store.recordLocatingUsage(handle, Optional.empty());
        store.recordLocatingUsage(handle, Optional.of(new ModelUsage(1, 1, 2)));

        LocatingUsage usage = store.loadLocatingUsage(id);
        assertThat(usage.callCount()).isEqualTo(3);
        assertThat(usage.unknown()).isTrue();
        assertThat(usage.reportedTokens()).isEmpty();
        assertThat(usage.reportLabel()).isEqualTo("unknown");
        assertThat(usage.reportLabel()).isNotEqualTo("0");
        assertThat(store.findRunDetail(id).orElseThrow().generation().usageRecordCount()).isZero();
    }

    @Test
    void takeoverDoesNotLetStaleOwnerOverwriteCommittedTrace() throws Exception {
        UUID id = store.submit(live("scene-15"));
        ClaimedRun ownerA = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        store.replaceLocatingTrace(
                ClaimHandle.from(ownerA),
                List.of(LocatingTraceStep.of(0, LocatingStepKind.SELECTION, "src/A.java", "PINNED", "{}")));

        expireLease(id);
        ClaimedRun ownerB = store.claimNext("owner-b", Duration.ofMinutes(5)).orElseThrow();
        locate(ownerB);

        assertThatThrownBy(() -> store.replaceLocatingTrace(
                        ClaimHandle.from(ownerA),
                        List.of(LocatingTraceStep.of(0, LocatingStepKind.SELECTION, "src/Evil.java", "PINNED", "{}"))))
                .isInstanceOf(StaleClaimException.class);

        List<LocatingTraceStep> traces = store.loadLocatingTrace(id);
        assertThat(traces).extracting(LocatingTraceStep::subject).containsExactly("src/A.java");
        assertThat(store.loadGenerationInput(id).sourceSnapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactly("src/A.java");
        assertThat(readOrigin(id)).isEqualTo("PINNED");
    }

    @Test
    void launchDiagnosticTextToolsRunsToolLoop() throws Exception {
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("diag-git"));
        RunSubmission diagnostic = new RunSubmission(
                VerificationMode.LIVE,
                "diag-text",
                "https://github.com/ex/repo.git",
                null,
                null,
                "NPE in fixtures/OldTest.java",
                "class OldTest fails",
                fixture.buggySha(),
                null,
                "",
                "21",
                io.github.patchatlas.sandbox.MavenNetworkMode.OFFLINE,
                List.of(),
                ContextOrigin.TEXT_TOOLS);
        UUID id = store.submitDiagnostic(diagnostic);
        assertThat(store.loadContextOrigin(id)).contains(ContextOrigin.TEXT_TOOLS);

        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        Path root = Files.createDirectories(temp.resolve("ws-diag"));
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new io.github.patchatlas.analysis.BuggyRepositoryReader(),
                new io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder(),
                (claimed, input, session, workspace) -> {
                    session.replaceTrace(List.of(LocatingTraceStep.of(
                            0, LocatingStepKind.SUBMIT, "src/test/java/fixtures/OldTest.java", "submit", "{}")));
                    return new LocatingCoordinator.Result.ContextCommitted(session.commitContext(
                            ContextOrigin.TEXT_TOOLS,
                            List.of(new SourceSnapshot(
                                    "src/test/java/fixtures/OldTest.java", LocalGitFixture.EXISTING_TEST))));
                });
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(locating),
                locating.lease().owner(),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            coordinator.run(
                    locating,
                    store.loadGenerationInput(id),
                    new LeaseHeartbeatLocatingRunSession(beat),
                    store.findRunDetail(id).orElseThrow().purpose(),
                    store.loadContextOrigin(id).orElseThrow());
        }

        assertThat(readOrigin(id)).isEqualTo("TEXT_TOOLS");
        assertThat(store.findRun(id).orElseThrow().state()).isEqualTo(RunState.GENERATING);
        assertThat(store.loadLocatingTrace(id)).extracting(LocatingTraceStep::kind)
                .contains(LocatingStepKind.SUBMIT);
    }

    @Test
    void differentContextOriginIsNotMergedIntoOneRun() {
        RunSubmission heuristic = live("origin-h");
        RunSubmission tools = new RunSubmission(
                VerificationMode.LIVE,
                "origin-t",
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                BUG,
                null,
                "",
                null,
                io.github.patchatlas.sandbox.MavenNetworkMode.OFFLINE,
                List.of(new SourceSnapshot("src/A.java", "class A {}")),
                ContextOrigin.TEXT_TOOLS);
        assertThat(SubmissionFingerprint.sha256Hex(heuristic))
                .isNotEqualTo(SubmissionFingerprint.sha256Hex(tools));
        UUID first = store.submit(heuristic);
        UUID second = store.submit(tools);
        assertThat(first).isNotEqualTo(second);
        assertThat(store.findRun(first)).isPresent();
        assertThat(store.findRun(second)).isPresent();
    }

    @Test
    void appendedTraceSurvivesCrashAndRerunRewritesFromZero() throws Exception {
        UUID id = store.submit(live("append-crash"));
        ClaimedRun first = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        store.beginLocatingTrace(ClaimHandle.from(first));
        store.appendLocatingTrace(
                ClaimHandle.from(first),
                LocatingTraceStep.of(0, LocatingStepKind.SEARCH, "src/A.java", "search", "{}"));
        assertThat(store.loadLocatingTrace(id)).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.SEARCH);

        expireLease(id);
        ClaimedRun recovered = store.claimNext("owner-b", Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.loadLocatingTrace(id)).extracting(LocatingTraceStep::seq).containsExactly(0);

        store.beginLocatingTrace(ClaimHandle.from(recovered));
        store.appendLocatingTrace(
                ClaimHandle.from(recovered),
                LocatingTraceStep.of(0, LocatingStepKind.LIST, ".", "list", "{}"));
        List<LocatingTraceStep> traces = store.loadLocatingTrace(id);
        assertThat(traces).extracting(LocatingTraceStep::seq).containsExactly(0);
        assertThat(traces).extracting(LocatingTraceStep::kind).containsExactly(LocatingStepKind.LIST);
        assertThat(generationAttemptCount(id)).isZero();
    }

    @Test
    void staleOwnerAppendAfterTakeoverIsRejectedAndDoesNotPolluteNewTrace() throws Exception {
        UUID id = store.submit(live("append-fence"));
        ClaimedRun ownerA = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        store.beginLocatingTrace(ClaimHandle.from(ownerA));
        store.appendLocatingTrace(
                ClaimHandle.from(ownerA),
                LocatingTraceStep.of(0, LocatingStepKind.SEARCH, "src/A.java", "search", "{}"));

        expireLease(id);
        ClaimedRun ownerB = store.claimNext("owner-b", Duration.ofMinutes(5)).orElseThrow();
        store.beginLocatingTrace(ClaimHandle.from(ownerB));
        store.appendLocatingTrace(
                ClaimHandle.from(ownerB),
                LocatingTraceStep.of(0, LocatingStepKind.READ, "src/B.java", "read", "{}"));

        assertThatThrownBy(() -> store.appendLocatingTrace(
                        ClaimHandle.from(ownerA),
                        LocatingTraceStep.of(1, LocatingStepKind.LIST, ".", "list", "{}")))
                .isInstanceOf(StaleClaimException.class);

        List<LocatingTraceStep> traces = store.loadLocatingTrace(id);
        assertThat(traces).extracting(LocatingTraceStep::kind).containsExactly(LocatingStepKind.READ);
        assertThat(traces).extracting(LocatingTraceStep::seq).containsExactly(0);
        assertThat(traces).noneMatch(step -> step.kind() == LocatingStepKind.LIST);
    }

    @Test
    void commitContextRejectsEmptySnapshots() {
        store.submit(live("empty-commit"));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        assertThatThrownBy(() -> store.commitContext(ClaimHandle.from(locating), ContextOrigin.TEXT_TOOLS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
        assertThat(store.findRun(locating.runId()).orElseThrow().state()).isEqualTo(RunState.LOCATING);
    }

    @Test
    void replaceLocatingTraceRejectsStaleHandle() {
        store.submit(live("trace-fence"));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        ClaimHandle stale = new ClaimHandle(
                locating.runId(), UUID.randomUUID(), locating.version(), RunState.LOCATING);
        assertThatThrownBy(() -> store.replaceLocatingTrace(
                        stale,
                        List.of(LocatingTraceStep.of(
                                0, LocatingStepKind.SELECTION, "src/A.java", "PINNED", "{}"))))
                .isInstanceOf(StaleClaimException.class);
    }

    @Test
    void emptyHeuristicSelectionFailsWithoutConsumingAttempts() throws Exception {
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("empty-git"));
        UUID id = store.submit(new RunSubmission(
                VerificationMode.LIVE,
                "empty-locate",
                "https://github.com/ex/repo.git",
                null,
                null,
                "复现时偶发空指针",
                "没有路径也没有类名",
                fixture.buggySha(),
                null,
                "",
                "21",
                List.of()));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        Path root = Files.createDirectories(temp.resolve("ws-empty"));
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new io.github.patchatlas.analysis.BuggyRepositoryReader(),
                new io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder());
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(locating),
                locating.lease().owner(),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            coordinator.run(
                    locating,
                    store.loadGenerationInput(id),
                    new LeaseHeartbeatLocatingRunSession(beat),
                    store.findRunDetail(id).orElseThrow().purpose());
        }

        RunDetails details = store.findRun(id).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.LOCATING);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.LOCATING_NO_CONTEXT);
        assertThat(generationAttemptCount(id)).isZero();
    }

    private static CandidateWorkspaceFactory unusedWorkspaces() {
        return (run, url, revision, module, policy) -> {
            throw new AssertionError("PINNED must not open a workspace");
        };
    }

    private int generationAttemptCount(UUID runId) throws Exception {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT generation_attempt_count FROM verification_run WHERE id = '%s'"
                                .formatted(runId))) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private String readOrigin(UUID runId) throws Exception {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT context_origin FROM verification_run WHERE id = '%s'"
                                .formatted(runId))) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private void expireLease(UUID runId) throws Exception {
        try (Connection connection = open();
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

    private int readSchemaVersion(UUID runId) throws Exception {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT input_schema_version FROM verification_run WHERE id = '%s'"
                                .formatted(runId))) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static RunSubmission live(String caseId) {
        return live(caseId, List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }

    private static RunSubmission live(String caseId, List<SourceSnapshot> snapshots) {
        return new RunSubmission(
                VerificationMode.LIVE,
                caseId,
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                BUG,
                null,
                "",
                null,
                snapshots);
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.GenerationInput;
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
 * LOCATING 领取/接管与 input_schema_version 双版本共存（真实 PostgreSQL）。
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
    void newSubmitWritesInputSchemaVersion2() throws Exception {
        UUID id = store.submit(live("schema-v2"));
        assertThat(readSchemaVersion(id)).isEqualTo(2);
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
                id,
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
                    new LeaseHeartbeatLocatingRunSession(beat));
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
                    new LeaseHeartbeatLocatingRunSession(beat));
        }
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

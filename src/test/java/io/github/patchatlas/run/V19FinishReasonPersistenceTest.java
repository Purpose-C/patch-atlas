package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** V19：finish_reason 入库；取不到时存 unknown 字面量，不存 NULL。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class V19FinishReasonPersistenceTest {

    private static final String BUG = "a".repeat(40);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @Test
    void newRunDefaultsToUnknownLiteralAndRejectsNull() throws Exception {
        migrateLatest();
        UUID id = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', 'QUEUED', 0
                    )
                    """
                            .formatted(id));
            assertThat(finishReason(statement, id)).isEqualTo("unknown");
            assertThatThrownBy(() -> statement.execute(
                            "UPDATE verification_run SET model_finish_reason = NULL WHERE id = '"
                                    + id
                                    + "'"))
                    .hasMessageContaining("model_finish_reason");
        }
    }

    @Test
    void preV19RowsBackfillUnknownNotNull() throws Exception {
        Flyway toV18 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("18")
                .cleanDisabled(false)
                .load();
        toV18.clean();
        toV18.migrate();

        UUID id = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', 'QUEUED', 0
                    )
                    """
                            .formatted(id));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            assertThat(finishReason(statement, id)).isEqualTo("unknown");
        }
    }

    @Test
    void recordModelUsagePersistsFinishReasonFromDiagnostics() throws Exception {
        migrateLatest();
        PostgresRunStore store = new PostgresRunStore(dataSource());
        store.submit(live("finish-reason"));
        ClaimedRun claimed = LocatingTestSupport.commitPinned(
                store, store.claimNext("w1", Duration.ofMinutes(5)).orElseThrow());
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "w1",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(beat, RunPurpose.STANDARD);
            session.reserveGenerationAttempt("fake", "fixture-v1");
            ClaimedRun afterLength = session.recordModelUsage(
                    new ModelUsage(1, 2, 3), CompletionDiagnostics.of("length", "0", "2"));
            assertThat(finishReason(claimed.runId())).isEqualTo("length");
            assertThat(afterLength.completionDiagnostics().finishReason()).isEqualTo("length");
            assertThat(afterLength.completionDiagnostics().reasoningTokens())
                    .isEqualTo(CompletionDiagnostics.UNKNOWN);

            session.reserveGenerationAttempt("fake", "fixture-v1");
            session.recordModelUsage(new ModelUsage(1, 2, 3));
            assertThat(finishReason(claimed.runId())).isEqualTo("unknown");
        }
    }

    @Test
    void commitCandidateReloadReadsFinishReasonColumnNotProvenance() throws Exception {
        migrateLatest();
        PostgresRunStore store = new PostgresRunStore(dataSource());
        store.submit(live("reload-finish"));
        ClaimedRun claimed = LocatingTestSupport.commitPinned(
                store, store.claimNext("w1", Duration.ofMinutes(5)).orElseThrow());
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "w1",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(beat, RunPurpose.STANDARD);
            session.reserveGenerationAttempt("fake", "fixture-v1");
            session.recordModelUsage(new ModelUsage(1, 2, 3), CompletionDiagnostics.of("stop", "0", "4"));
            ClaimedRun replaying = session.commitCandidate(GatedCandidateTestHelper.gated(
                    PersistedCandidatePatch.fromAccepted(
                            """
                            diff --git a/src/test/java/c/T.java b/src/test/java/c/T.java
                            new file mode 100644
                            --- /dev/null
                            +++ b/src/test/java/c/T.java
                            @@ -0,0 +1,3 @@
                            +package c;
                            +class T { void m() {} }
                            """,
                            new io.github.patchatlas.replay.TargetTest("c.T", "m"))));
            assertThat(replaying.candidate().orElseThrow().provenance())
                    .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
            assertThat(replaying.completionDiagnostics().finishReason()).isEqualTo("stop");
            assertThat(finishReason(claimed.runId())).isEqualTo("stop");
        }
    }

    private static void migrateLatest() {
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
    }

    private static String finishReason(Statement statement, UUID id) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT model_finish_reason FROM verification_run WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            String value = rs.getString(1);
            assertThat(rs.wasNull()).isFalse();
            return value;
        }
    }

    private static String finishReason(UUID runId) throws Exception {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            return finishReason(statement, runId);
        }
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

    private static RunSubmission live(String caseId) {
        return new RunSubmission(
                VerificationMode.LIVE,
                caseId,
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                BUG,
                null,
                "",
                "21",
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }
}

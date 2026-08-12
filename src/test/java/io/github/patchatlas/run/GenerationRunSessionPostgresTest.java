package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * ��PostgreSQL 下 GenerationRunSession 预占、usage、fencing、第四次拒绝。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class GenerationRunSessionPostgresTest {

    private static final String BUG = "a".repeat(40);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

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
    void reserveUsageAndFourthReject() {
        store.submit(live("res-1"));
        ClaimedRun claimed = store.claimNext("w1", Duration.ofMinutes(5)).orElseThrow();

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "w1",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(store, beat);

            var r1 = session.reserveGenerationAttempt("fake", "fixture-v1");
            assertThat(r1).isInstanceOf(GenerationRunSession.ReserveResult.Reserved.class);
            assertThat(((GenerationRunSession.ReserveResult.Reserved) r1).attemptOrdinal())
                    .isEqualTo(1);

            ClaimedRun afterUsage = session.recordModelUsage(new ModelUsage(11, 22, 33));
            assertThat(afterUsage.version()).isGreaterThan(claimed.version());

            var r2 = session.reserveGenerationAttempt("fake", "fixture-v1");
            assertThat(((GenerationRunSession.ReserveResult.Reserved) r2).attemptOrdinal())
                    .isEqualTo(2);
            var r3 = session.reserveGenerationAttempt("fake", "fixture-v1");
            assertThat(((GenerationRunSession.ReserveResult.Reserved) r3).attemptOrdinal())
                    .isEqualTo(3);

            var r4 = session.reserveGenerationAttempt("fake", "fixture-v1");
            assertThat(r4).isInstanceOf(GenerationRunSession.ReserveResult.Exhausted.class);

            RunDetails details = store.findRun(claimed.runId()).orElseThrow();
            assertThat(details.state()).isEqualTo(RunState.FAILED);
            assertThat(details.failure().orElseThrow().category())
                    .isEqualTo(FailureCategory.GENERATION_EXHAUSTED);
            assertThat(store.loadGenerationAttemptCount(claimed.runId())).isEqualTo(3);
        }
    }

    @Test
    void staleOwnerCannotRecordUsageAfterFencing() throws Exception {
        store.submit(live("fence-1"));
        ClaimedRun claimed = store.claimNext("old", Duration.ofMinutes(5)).orElseThrow();
        ClaimHandle staleHandle = ClaimHandle.from(claimed);

        expireLease(claimed.runId());
        ClaimedRun recovered = store.claimNext("new", Duration.ofMinutes(5)).orElseThrow();
        assertThat(recovered.runId()).isEqualTo(claimed.runId());
        assertThat(recovered.version()).isGreaterThan(claimed.version());

        assertThatThrownBy(() -> store.recordModelUsage(staleHandle, new ModelUsage(1, 1, 2)))
                .isInstanceOf(StaleClaimException.class);

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(recovered),
                "new",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(store, beat);
            var reserved = session.reserveGenerationAttempt("fake", "m");
            assertThat(reserved).isInstanceOf(GenerationRunSession.ReserveResult.Reserved.class);
            assertThat(((GenerationRunSession.ReserveResult.Reserved) reserved).attemptOrdinal())
                    .isEqualTo(1);
            ClaimedRun current = session.currentClaim();
            assertThat(current.version())
                    .isEqualTo(
                            ((GenerationRunSession.ReserveResult.Reserved) reserved)
                                    .claim()
                                    .version());
        }
    }

    @Test
    void recoveryDoesNotResetAttemptCount() throws Exception {
        store.submit(live("recover-count"));
        ClaimedRun claimed = store.claimNext("a", Duration.ofMinutes(5)).orElseThrow();

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "a",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30))) {
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(store, beat);
            session.reserveGenerationAttempt("fake", "m");
            session.reserveGenerationAttempt("fake", "m");
        }

        expireLease(claimed.runId());
        ClaimedRun b = store.claimNext("b", Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.loadGenerationAttemptCount(b.runId())).isEqualTo(2);

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(b), "b", Duration.ofMinutes(5), Duration.ofSeconds(30))) {
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(store, beat);
            var r3 = session.reserveGenerationAttempt("fake", "m");
            assertThat(((GenerationRunSession.ReserveResult.Reserved) r3).attemptOrdinal())
                    .isEqualTo(3);
            var r4 = session.reserveGenerationAttempt("fake", "m");
            assertThat(r4).isInstanceOf(GenerationRunSession.ReserveResult.Exhausted.class);
        }
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

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

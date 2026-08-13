package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.replay.VerificationMode;
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
 * 续租、token/version fence、过期接管与恢复上限。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class PostgresRunStoreLeaseTest {

    private static final String BUG = "b".repeat(40);

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
    void renewLeaseExtendsExpiryAndBumpsVersion() {
        UUID id = store.submit(live("renew-1"));
        ClaimedRun claimed = store.claimNext("owner-a", Duration.ofMinutes(2)).orElseThrow();
        ClaimHandle handle = ClaimHandle.from(claimed);

        ClaimedRun renewed = store.renewLease(handle, "owner-a", Duration.ofMinutes(10));

        assertThat(renewed.runId()).isEqualTo(id);
        assertThat(renewed.version()).isEqualTo(claimed.version() + 1);
        assertThat(renewed.lease().token()).isEqualTo(claimed.lease().token());
        assertThat(renewed.lease().expiresAt()).isAfter(claimed.lease().expiresAt());
    }

    @Test
    void renewWithWrongTokenIsFenced() {
        store.submit(live("fence-token"));
        ClaimedRun claimed = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        ClaimHandle wrong = new ClaimHandle(
                claimed.runId(), UUID.randomUUID(), claimed.version(), claimed.state());

        assertThatThrownBy(() -> store.renewLease(wrong, "owner-a", Duration.ofMinutes(5)))
                .isInstanceOf(StaleClaimException.class);
    }

    @Test
    void renewWithStaleVersionIsFenced() {
        store.submit(live("fence-ver"));
        ClaimedRun claimed = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        ClaimHandle handle = ClaimHandle.from(claimed);
        store.renewLease(handle, "owner-a", Duration.ofMinutes(5));

        assertThatThrownBy(() -> store.renewLease(handle, "owner-a", Duration.ofMinutes(5)))
                .isInstanceOf(StaleClaimException.class);
    }

    @Test
    void expiredLeaseCanBeReclaimedByOtherOwnerAndOldOwnerIsFenced() throws Exception {
        store.submit(live("reclaim"));
        ClaimedRun ownerA = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        expireLease(ownerA.runId());

        ClaimedRun ownerB = store.claimNext("owner-b", Duration.ofMinutes(5)).orElseThrow();
        assertThat(ownerB.runId()).isEqualTo(ownerA.runId());
        assertThat(ownerB.lease().owner()).isEqualTo("owner-b");
        assertThat(ownerB.recoveryCount()).isEqualTo(1);
        assertThat(ownerB.lease().token()).isNotEqualTo(ownerA.lease().token());

        assertThatThrownBy(() ->
                        store.renewLease(ClaimHandle.from(ownerA), "owner-a", Duration.ofMinutes(5)))
                .isInstanceOf(StaleClaimException.class);

        assertThatThrownBy(() -> store.fail(
                        ClaimHandle.from(ownerA),
                        new RunFailure(
                                FailureStage.GENERATION,
                                FailureCategory.GENERATION_FAILURE,
                                "late fail")))
                .isInstanceOf(StaleClaimException.class);

        // 新 owner 仍可操作
        ClaimedRun renewed = store.renewLease(ClaimHandle.from(ownerB), "owner-b", Duration.ofMinutes(5));
        assertThat(renewed.version()).isGreaterThan(ownerB.version());
    }

    @Test
    void fourthReclaimMarksRecoveryExhaustedAndIsNotClaimable() throws Exception {
        store.submit(live("exhaust"));
        ClaimedRun claimed = store.claimNext("w0", Duration.ofMinutes(1)).orElseThrow();
        UUID id = claimed.runId();

        // 成功接管 3 次 → recovery_count = 3
        for (int i = 1; i <= RunLeaseRules.MAX_RECOVERY_COUNT; i++) {
            expireLease(id);
            claimed = store.claimNext("w" + i, Duration.ofMinutes(1)).orElseThrow();
            assertThat(claimed.runId()).isEqualTo(id);
            assertThat(claimed.recoveryCount()).isEqualTo(i);
        }

        // 第四次过期接管 → FAILED / RECOVERY_EXHAUSTED，不再返回 claim
        expireLease(id);
        assertThat(store.claimNext("w-final", Duration.ofMinutes(1))).isEmpty();

        RunDetails details = store.findRun(id).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure()).isPresent();
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.RECOVERY_EXHAUSTED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.RECOVERY);
        assertThat(details.verdict()).isEmpty();
    }

    @Test
    void terminalRunIsNeverClaimed() {
        store.submit(live("terminal"));
        ClaimedRun claimed = store.claimNext("owner-a", Duration.ofMinutes(5)).orElseThrow();
        store.fail(
                ClaimHandle.from(claimed),
                new RunFailure(
                        FailureStage.GENERATION,
                        FailureCategory.GENERATION_FAILURE,
                        "gen failed"));

        assertThat(store.claimNext("owner-b", Duration.ofMinutes(5))).isEmpty();
        RunDetails details = store.findRun(claimed.runId()).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.FAILED);
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
                "t",
                "b",
                BUG,
                null,
                "",
                null,
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

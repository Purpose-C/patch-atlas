package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RecordedUsageStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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

/** ��只读聚合不伪造 NULL usage，也不改 Run 状态。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class PostgresRunAggregateReaderTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    private PostgresRunAggregateReader reader;

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
        reader = new PostgresRunAggregateReader(dataSource());
    }

    @Test
    void aggregatesCompletedFailedAttemptsUsageAndTokens() throws Exception {
        insertCompleted(VerificationMode.LIVE, ReplayVerdict.VALID_REPRODUCTION);
        insertCompleted(VerificationMode.LIVE, ReplayVerdict.VALID_REPRODUCTION);
        insertCompleted(VerificationMode.HISTORICAL, ReplayVerdict.INCONCLUSIVE);
        insertFailed(
                VerificationMode.LIVE, FailureStage.GENERATION, FailureCategory.GENERATION_EXHAUSTED);
        insertUsage("openai", "gpt-4.1-mini", 2, 1, 10, 20, 30);
        insertUsage("openai", "other-model", 1, 0, 100, 0, 100);
        insertUsage("fake", "fixture-v1", 1, 1, 1, 1, 2);
        UUID tracked = insertUsage("openai", "gpt-4.1-mini", 3, null, 5, 5, 10);

        assertThat(reader.completedRuns(VerificationMode.LIVE, ReplayVerdict.VALID_REPRODUCTION))
                .isEqualTo(2);
        assertThat(reader.completedRuns(VerificationMode.HISTORICAL, ReplayVerdict.INCONCLUSIVE))
                .isEqualTo(1);
        assertThat(reader.completedRuns(VerificationMode.LIVE, ReplayVerdict.NOT_REPRODUCED))
                .isZero();
        assertThat(reader.failedRuns(
                        VerificationMode.LIVE,
                        FailureStage.GENERATION,
                        FailureCategory.GENERATION_EXHAUSTED))
                .isEqualTo(1);
        assertThat(reader.generationAttempts("openai")).isEqualTo(6);
        assertThat(reader.usageRecords("openai")).isEqualTo(1);
        assertThat(reader.usageRuns("openai", RecordedUsageStatus.TRACKING_UNAVAILABLE)).isEqualTo(1);
        assertThat(reader.usageRuns("openai", RecordedUsageStatus.NONE_RECORDED)).isEqualTo(1);
        assertThat(reader.usageRuns("openai", RecordedUsageStatus.PARTIALLY_RECORDED)).isEqualTo(1);
        assertThat(reader.usageRuns("openai", RecordedUsageStatus.RECORDED_FOR_ALL_ATTEMPTS)).isZero();
        assertThat(reader.tokens("openai", "input")).isEqualTo(115);
        assertThat(reader.tokensForModel("openai", "gpt-4.1-mini", "output")).isEqualTo(25);

        long versionBefore = version(tracked);
        reader.usageRecords("openai");
        assertThat(version(tracked)).isEqualTo(versionBefore);
        assertThat(state(tracked)).isEqualTo("QUEUED");
    }

    @Test
    void estimatedTokensIgnoreUnmatchedModel() throws Exception {
        insertUsage("openai", "gpt-4.1-mini", 1, 1, 1_000_000, 0, 1_000_000);
        insertUsage("openai", "other", 1, 1, 9_000_000, 0, 9_000_000);
        assertThat(reader.tokensForModel("openai", "gpt-4.1-mini", "input")).isEqualTo(1_000_000);
        assertThat(reader.tokens("openai", "input")).isEqualTo(10_000_000);
    }

    private UUID insertCompleted(VerificationMode mode, ReplayVerdict verdict) throws Exception {
        UUID id = UUID.randomUUID();
        String fixed = mode == VerificationMode.HISTORICAL ? "'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'" : "NULL";
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, fixed_revision, module_path, state, version,
                      verdict, primary_stable_evidence, final_replay_round, completed_at
                    ) VALUES (
                      '%s', '%s', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', %s, '', 'COMPLETED', 1,
                      '%s', 'TARGET_ASSERTION_FAILURE', 1, CURRENT_TIMESTAMP
                    )
                    """
                            .formatted(id, mode.name(), fixed, verdict.name()));
        }
        return id;
    }

    private void insertFailed(VerificationMode mode, FailureStage stage, FailureCategory category)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version,
                      failure_stage, failure_category, failure_summary, completed_at
                    ) VALUES (
                      '%s', '%s', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', 'FAILED', 1,
                      '%s', '%s', 'failed', CURRENT_TIMESTAMP
                    )
                    """
                            .formatted(id, mode.name(), stage.name(), category.name()));
        }
    }

    private UUID insertUsage(
            String provider,
            String model,
            int attempts,
            Integer records,
            long input,
            long output,
            long total)
            throws Exception {
        UUID id = UUID.randomUUID();
        String countSql = records == null ? "NULL" : records.toString();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version,
                      generation_attempt_count, model_provider, model_name,
                      model_input_tokens, model_output_tokens, model_total_tokens,
                      model_usage_record_count
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', 'QUEUED', 0,
                      %d, '%s', '%s', %d, %d, %d, %s
                    )
                    """
                            .formatted(id, attempts, provider, model, input, output, total, countSql));
        }
        return id;
    }

    private long version(UUID id) throws Exception {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(
                        "SELECT version FROM verification_run WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private String state(UUID id) throws Exception {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(
                        "SELECT state FROM verification_run WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
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
}

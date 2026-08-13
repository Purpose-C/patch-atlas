package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** ��V5 回填未知 usage，且不改 V1–V4。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class V5ModelUsageObservationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @Test
    void backfillsLegacyZeroAttemptAsZeroAndAttemptedAsNull() throws Exception {
        Flyway toV4 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("4")
                .cleanDisabled(false)
                .load();
        toV4.clean();
        toV4.migrate();

        UUID zeroAttempt = UUID.randomUUID();
        UUID attempted = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(insertQueued(zeroAttempt, 0, null, null));
            statement.execute(insertQueued(attempted, 1, "openai", "gpt-4.1-mini"));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            assertThat(usageCount(statement, zeroAttempt)).isEqualTo(0);
            assertThat(usageCountWasNull(statement, attempted)).isTrue();
        }
    }

    @Test
    void newRunDefaultsToZeroAndRejectsCountAboveAttempts() throws Exception {
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
            assertThat(usageCount(statement, id)).isEqualTo(0);

            assertThatThrownBy(() -> statement.execute(
                            "UPDATE verification_run SET model_usage_record_count = 1 WHERE id = '"
                                    + id + "'"))
                    .hasMessageContaining("verification_run_model_usage_record_count_chk");
        }
    }

    private static void migrateLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    private static String insertQueued(UUID id, int attempts, String provider, String model) {
        String providerSql = provider == null ? "NULL" : "'" + provider + "'";
        String modelSql = model == null ? "NULL" : "'" + model + "'";
        return """
                INSERT INTO verification_run (
                  id, mode, repository_url, issue_title, issue_body,
                  buggy_revision, module_path, state, version,
                  generation_attempt_count, model_provider, model_name
                ) VALUES (
                  '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', 'QUEUED', 0,
                  %d, %s, %s
                )
                """
                .formatted(id, attempts, providerSql, modelSql);
    }

    private static Integer usageCount(Statement statement, UUID id) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT model_usage_record_count FROM verification_run WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            int value = rs.getInt(1);
            return rs.wasNull() ? null : value;
        }
    }

    private static boolean usageCountWasNull(Statement statement, UUID id) throws Exception {
        return usageCount(statement, id) == null;
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

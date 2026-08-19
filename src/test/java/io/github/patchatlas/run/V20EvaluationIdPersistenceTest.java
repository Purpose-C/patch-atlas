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

/** V20：evaluation_id 可空，不进证据报告；非法字面量被约束拒绝。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class V20EvaluationIdPersistenceTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @Test
    void newRunDefaultsToNullAndRejectsInvalidLiteral() throws Exception {
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
            assertThat(evaluationId(statement, id)).isNull();
            assertThatThrownBy(() -> statement.execute(
                            "UPDATE verification_run SET evaluation_id = 'Batch_5' WHERE id = '"
                                    + id
                                    + "'"))
                    .hasMessageContaining("evaluation_id");
            statement.execute(
                    "UPDATE verification_run SET evaluation_id = 'batch5-three-arm' WHERE id = '"
                            + id
                            + "'");
            assertThat(evaluationId(statement, id)).isEqualTo("batch5-three-arm");
        }
    }

    @Test
    void preV20RowsStayNullAfterMigrate() throws Exception {
        Flyway toV19 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("19")
                .cleanDisabled(false)
                .load();
        toV19.clean();
        toV19.migrate();

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
            assertThat(evaluationId(statement, id)).isNull();
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

    private static String evaluationId(Statement statement, UUID id) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT evaluation_id FROM verification_run WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            String value = rs.getString(1);
            return rs.wasNull() ? null : value;
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

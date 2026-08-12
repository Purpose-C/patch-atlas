package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.SourceSnapshot;
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
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * session timezone 非 UTC 时，TIMESTAMPTZ 默认值与 claim 更新仍满足 updated_at >= created_at。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class TimestamptzDefaultTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas")
                    .withCommand("postgres", "-c", "timezone=America/Los_Angeles");

    @BeforeEach
    void migrate() {
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

    @Test
    void claimUnderNonUtcSessionKeepsTimestampInvariant() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'America/Los_Angeles'");
            try (ResultSet tz = statement.executeQuery("SHOW timezone")) {
                tz.next();
                assertThat(tz.getString(1)).isEqualTo("America/Los_Angeles");
            }
        }

        PostgresRunStore store = new PostgresRunStore(dataSource());
        UUID id = store.submit(new RunSubmission(
                VerificationMode.LIVE,
                "tz",
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                "b".repeat(40),
                null,
                "",
                null,
                List.of(new SourceSnapshot("src/A.java", "class A {}"))));

        ClaimedRun claimed = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        assertThat(claimed.runId()).isEqualTo(id);
        assertThat(claimed.state()).isEqualTo(RunState.GENERATING);

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        """
                        SELECT created_at <= updated_at AS ok,
                               created_at,
                               updated_at
                          FROM verification_run
                         WHERE id = '%s'
                        """
                                .formatted(id))) {
            rs.next();
            assertThat(rs.getBoolean("ok")).isTrue();
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

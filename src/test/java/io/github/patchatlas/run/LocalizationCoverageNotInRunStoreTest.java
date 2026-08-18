package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 覆盖率是 Oracle 衍生量，不得写入 locating_trace、source_snapshots 或生成侧列。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class LocalizationCoverageNotInRunStoreTest {

    private static final String BUG = "c".repeat(40);
    private static final String FIXED = "d".repeat(40);

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
    void locatingTraceSnapshotsAndGenerationColumnsDoNotCarryCoverage() throws Exception {
        UUID id = store.submit(historical("coverage-isolation"));
        ClaimedRun locating = store.claimNext("owner", Duration.ofMinutes(5)).orElseThrow();
        store.replaceLocatingTrace(
                ClaimHandle.from(locating),
                List.of(LocatingTraceStep.of(
                        0, LocatingStepKind.SELECTION, "src/main/java/A.java", "PINNED", "{}")));
        ClaimedRun generating = store.commitContext(
                ClaimHandle.from(locating),
                ContextOrigin.HEURISTIC,
                List.of(new SourceSnapshot("src/main/java/A.java", "class A {}")));
        store.fail(
                ClaimHandle.from(generating),
                new RunFailure(FailureStage.GENERATION, FailureCategory.GENERATION_FAILURE, "gen failed"));

        String stored = storedGenerationSideText(id);
        assertThat(stored)
                .doesNotContain("anyHit")
                .doesNotContain("selectedCount")
                .doesNotContain("localizationCoverage")
                .doesNotContain("LocalizationCoverageEvaluator")
                .doesNotContain("RepairGroundTruthExtractor");
    }

    private String storedGenerationSideText(UUID runId) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            try (ResultSet traces = statement.executeQuery(
                    "SELECT coalesce(detail::text,'') || ' ' || coalesce(subject,'') || ' ' || coalesce(reason,'') FROM locating_trace WHERE run_id = '%s'"
                            .formatted(runId))) {
                while (traces.next()) {
                    out.append(traces.getString(1)).append(' ');
                }
            }
            try (ResultSet run = statement.executeQuery(
                    """
                    SELECT coalesce(source_snapshots::text,''),
                           coalesce(issue_title,''),
                           coalesce(issue_body,''),
                           coalesce(failure_summary,''),
                           coalesce(model_provider,''),
                           coalesce(model_name,'')
                      FROM verification_run WHERE id = '%s'
                    """
                            .formatted(runId))) {
                assertThat(run.next()).isTrue();
                for (int i = 1; i <= 6; i++) {
                    out.append(run.getString(i)).append(' ');
                }
            }
        }
        return out.toString();
    }

    private static RunSubmission historical(String caseId) {
        return new RunSubmission(
                VerificationMode.HISTORICAL,
                caseId,
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                BUG,
                FIXED,
                "",
                "21",
                List.of(new SourceSnapshot("src/main/java/A.java", "class A {}")));
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

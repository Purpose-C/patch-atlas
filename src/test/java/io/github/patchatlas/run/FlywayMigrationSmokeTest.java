package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实 PostgreSQL 上 Flyway 迁移与关键约束 smoke。
 * 默认被 excludedGroups 排除；{@code -Dgroups=database} 启用。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class FlywayMigrationSmokeTest {

    private static final DockerImageName POSTGRES =
            DockerImageName.parse("postgres:16.6");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES_CONTAINER =
            new PostgreSQLContainer<>(POSTGRES).withDatabaseName("patchatlas");

    @Test
    void migratesExactlyThreeBusinessTablesAndHistory() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        POSTGRES_CONTAINER.getJdbcUrl(),
                        POSTGRES_CONTAINER.getUsername(),
                        POSTGRES_CONTAINER.getPassword())
                .locations("classpath:db/migration")
                .load();

        var first = flyway.migrate();
        assertThat(first.migrationsExecuted).isEqualTo(7);
        var second = flyway.migrate();
        assertThat(second.migrationsExecuted).isZero();
        flyway.validate();

        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        """
                        SELECT table_name
                          FROM information_schema.tables
                         WHERE table_schema = 'public'
                           AND table_type = 'BASE TABLE'
                        """)) {
            Set<String> tables = new HashSet<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables)
                    .containsExactlyInAnyOrder(
                            "verification_run",
                            "candidate_test_patch",
                            "replay_attempt",
                            "flyway_schema_history");
        }
    }

    @Test
    void enforcesLiveModeRequiresNullFixedRevision() throws Exception {
        migrate();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(
                            """
                            INSERT INTO verification_run (
                              id, mode, repository_url, issue_title, issue_body,
                              buggy_revision, fixed_revision, module_path, state, version
                            ) VALUES (
                              '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                              'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                              'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                              '', 'QUEUED', 0
                            )
                            """
                                    .formatted(UUID.randomUUID())))
                    .hasMessageContaining("verification_run_mode_fixed_chk");
        }
    }

    @Test
    void rejectsOversizedSourceSnapshotsJson() throws Exception {
        migrate();
        // 超过 384 KiB 的 jsonb 文本
        String hugeContent = "x".repeat(400_000);
        String json = "[{\"relativePath\":\"a/B.java\",\"content\":\"" + hugeContent + "\"}]";
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(
                            """
                            INSERT INTO verification_run (
                              id, mode, repository_url, issue_title, issue_body,
                              buggy_revision, module_path, state, version, source_snapshots
                            ) VALUES (
                              '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                              'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                              '', 'QUEUED', 0, '%s'::jsonb
                            )
                            """
                                    .formatted(UUID.randomUUID(), json.replace("'", "''"))))
                    .hasMessageContaining("verification_run_snapshots_size_chk");
        }
    }

    @Test
    void rejectsOversizedTestCasesJson() throws Exception {
        migrate();
        UUID runId = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version,
                      lease_token, lease_owner, lease_expires_at
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      '', 'REPLAYING', 1,
                      '%s', 'w', CURRENT_TIMESTAMP + INTERVAL '1 hour'
                    )
                    """
                            .formatted(runId, UUID.randomUUID()));
            String hugeCases = "[\"" + "y".repeat(9_000_000) + "\"]";
            assertThatThrownBy(() -> statement.execute(
                            """
                            INSERT INTO replay_attempt (
                              id, run_id, replay_round, side, attempt_ordinal,
                              phase, outcome, target_evidence, diagnostic,
                              sandbox_status, test_cases
                            ) VALUES (
                              '%s', '%s', 1, 'PRIMARY', 1,
                              'REPORT_FAILURE', 'ENVIRONMENT_FAILURE', 'INVALID', 'x',
                              'COMPLETED', '%s'::jsonb
                            )
                            """
                                    .formatted(UUID.randomUUID(), runId, hugeCases)))
                    .hasMessageContaining("replay_attempt_cases_size_chk");
        }
    }

    @Test
    void enforcesCandidateOneToOneWithRun() throws Exception {
        migrate();
        UUID runId = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      '', 'QUEUED', 0
                    )
                    """
                            .formatted(runId));
            statement.execute(
                    """
                    INSERT INTO candidate_test_patch (
                      run_id, patch_text, patch_sha256, target_class, target_method
                    ) VALUES (
                      '%s', 'diff --git a/x b/x',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'fixtures.T', 'm'
                    )
                    """
                            .formatted(runId));
            assertThatThrownBy(() -> statement.execute(
                            """
                            INSERT INTO candidate_test_patch (
                              run_id, patch_text, patch_sha256, target_class, target_method
                            ) VALUES (
                              '%s', 'diff --git a/y b/y',
                              'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                              'fixtures.T', 'm'
                            )
                            """
                                    .formatted(runId)))
                    .hasMessageContaining("candidate_test_patch");
        }
    }

    @Test
    void v6DefaultsAndConstrainsRunPurposeAndPatchProvenance() throws Exception {
        migrate();
        UUID runId = UUID.randomUUID();
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
                            .formatted(runId));
            statement.execute(
                    """
                    INSERT INTO candidate_test_patch (
                      run_id, patch_text, patch_sha256, target_class, target_method
                    ) VALUES (
                      '%s', 'diff --git a/x b/x',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'fixtures.T', 'm'
                    )
                    """
                            .formatted(runId));

            try (ResultSet rs = statement.executeQuery(
                    """
                    SELECT r.run_purpose, c.patch_provenance
                      FROM verification_run r
                      JOIN candidate_test_patch c ON c.run_id = r.id
                     WHERE r.id = '%s'
                    """
                            .formatted(runId))) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("run_purpose")).isEqualTo("STANDARD");
                assertThat(rs.getString("patch_provenance")).isEqualTo("AGENT_GENERATED");
            }

            assertThatThrownBy(() -> statement.execute(
                            "UPDATE verification_run SET run_purpose = 'OTHER' WHERE id = '"
                                    + runId
                                    + "'"))
                    .hasMessageContaining("verification_run_purpose_chk");
            assertThatThrownBy(() -> statement.execute(
                            "UPDATE candidate_test_patch SET patch_provenance = 'OTHER' WHERE run_id = '"
                                    + runId
                                    + "'"))
                    .hasMessageContaining("candidate_test_patch_provenance_chk");
        }
    }

    @Test
    void v3NormalizesSupportedLegacyJavaPatchVersion() throws Exception {
        Flyway v2 = Flyway.configure()
                .dataSource(
                        POSTGRES_CONTAINER.getJdbcUrl(),
                        POSTGRES_CONTAINER.getUsername(),
                        POSTGRES_CONTAINER.getPassword())
                .locations("classpath:db/migration")
                .target("2")
                .cleanDisabled(false)
                .load();
        v2.clean();
        v2.migrate();
        UUID runId = UUID.randomUUID();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, java_version, state, version
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', '21.0.2', 'QUEUED', 0
                    )
                    """
                            .formatted(runId));
        }

        migrate();

        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT java_version FROM verification_run WHERE id = '" + runId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("21");
        }
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(
                        POSTGRES_CONTAINER.getJdbcUrl(),
                        POSTGRES_CONTAINER.getUsername(),
                        POSTGRES_CONTAINER.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                POSTGRES_CONTAINER.getJdbcUrl(),
                POSTGRES_CONTAINER.getUsername(),
                POSTGRES_CONTAINER.getPassword());
    }
}

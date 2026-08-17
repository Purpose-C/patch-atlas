package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.ClaimHandle;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.GatedCandidate;
import io.github.patchatlas.run.LocatingTestSupport;
import io.github.patchatlas.run.PersistedCandidatePatch;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunSubmission;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

/** 恢复、stale owner 与终态重试不得让 Run Aggregate Metrics 重复计数。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class RunAggregateMetricsIdempotenceTest {

    private static final String BUG = "a".repeat(40);
    private static final TargetTest TARGET = new TargetTest("c.T", "m");
    private static final String PATCH =
            """
            diff --git a/src/test/java/c/T.java b/src/test/java/c/T.java
            new file mode 100644
            --- /dev/null
            +++ b/src/test/java/c/T.java
            @@ -0,0 +1,3 @@
            +package c;
            +class T { void m() {} }
            """;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    private PostgresRunStore store;
    private PostgresRunAggregateReader reader;
    private SimpleMeterRegistry registry;

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
        javax.sql.DataSource dataSource = dataSource();
        store = new PostgresRunStore(dataSource);
        reader = new PostgresRunAggregateReader(dataSource);
        registry = new SimpleMeterRegistry();
        RunAggregateMeters.bind(registry, reader, Optional.empty());
    }

    @Test
    void completedRunIsCountedOnceAfterRecoveryStaleOwnerAndTerminalRetry() throws Exception {
        store.submit(live("complete-once"));
        ClaimedRun generating = LocatingTestSupport.commitPinned(
                store, store.claimNext("old", Duration.ofMinutes(5)).orElseThrow());
        ClaimedRun reserved =
                store.reserveGenerationAttempt(ClaimHandle.from(generating), "openai", "gpt-4.1-mini")
                        .claim();
        ClaimedRun afterUsage =
                store.recordModelUsage(ClaimHandle.from(reserved), new ModelUsage(10, 20, 30));
        ClaimedRun replaying =
                store.commitCandidate(ClaimHandle.from(afterUsage), gated());
        ClaimedRun opened = store.openReplayRound(ClaimHandle.from(replaying));

        expireLease(opened.runId());
        ClaimedRun recovered = store.claimNext("new", Duration.ofMinutes(5)).orElseThrow();
        assertThat(recovered.recoveryCount()).isEqualTo(1);

        ReplayResult result = ReplayResult.live(
                ReplayVerdict.REPRODUCTION_CANDIDATE, TARGET, assertionFailureSide());
        assertThatThrownBy(() -> store.complete(ClaimHandle.from(opened), result))
                .isInstanceOf(io.github.patchatlas.run.StaleClaimException.class);
        assertThat(completedLiveCandidate()).isZero();

        store.complete(ClaimHandle.from(recovered), result);
        assertThat(completedLiveCandidate()).isEqualTo(1.0);
        assertThat(reader.usageRecords("openai")).isEqualTo(1);

        assertThatThrownBy(() -> store.complete(ClaimHandle.from(recovered), result))
                .isInstanceOfAny(
                        io.github.patchatlas.run.StaleClaimException.class,
                        org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> store.complete(ClaimHandle.from(opened), result))
                .isInstanceOfAny(
                        io.github.patchatlas.run.StaleClaimException.class,
                        org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(store.claimNext("third", Duration.ofMinutes(5))).isEmpty();

        assertThat(completedLiveCandidate()).isEqualTo(1.0);
        assertThat(reader.completedRuns(VerificationMode.LIVE, ReplayVerdict.REPRODUCTION_CANDIDATE))
                .isEqualTo(1);
        assertThat(reader.usageRecords("openai")).isEqualTo(1);
    }

    @Test
    void failedRunAndUsageAreNotRecountedAfterStaleOwnerRetry() throws Exception {
        store.submit(live("fail-once"));
        ClaimedRun oldOwner = LocatingTestSupport.commitPinned(
                store, store.claimNext("old", Duration.ofMinutes(5)).orElseThrow());
        ClaimedRun reserved =
                store.reserveGenerationAttempt(ClaimHandle.from(oldOwner), "openai", "gpt-4.1-mini")
                        .claim();
        store.recordModelUsage(ClaimHandle.from(reserved), new ModelUsage(3, 4, 7));

        expireLease(oldOwner.runId());
        ClaimedRun recovered = store.claimNext("new", Duration.ofMinutes(5)).orElseThrow();

        assertThatThrownBy(() -> store.recordModelUsage(
                        ClaimHandle.from(reserved), new ModelUsage(1, 1, 2)))
                .isInstanceOf(io.github.patchatlas.run.StaleClaimException.class);
        assertThatThrownBy(() -> store.fail(
                        ClaimHandle.from(oldOwner),
                        new RunFailure(
                                FailureStage.GENERATION,
                                FailureCategory.GENERATION_FAILURE,
                                "late")))
                .isInstanceOf(io.github.patchatlas.run.StaleClaimException.class);
        assertThat(reader.failedRuns(
                        VerificationMode.LIVE,
                        FailureStage.GENERATION,
                        FailureCategory.GENERATION_FAILURE))
                .isZero();
        assertThat(reader.usageRecords("openai")).isEqualTo(1);

        store.fail(
                ClaimHandle.from(recovered),
                new RunFailure(
                        FailureStage.GENERATION, FailureCategory.GENERATION_FAILURE, "gen failed"));
        assertThat(failedGeneration()).isEqualTo(1.0);

        assertThatThrownBy(() -> store.fail(
                        ClaimHandle.from(recovered),
                        new RunFailure(
                                FailureStage.GENERATION,
                                FailureCategory.GENERATION_FAILURE,
                                "again")))
                .isInstanceOf(io.github.patchatlas.run.StaleClaimException.class);
        assertThat(store.claimNext("third", Duration.ofMinutes(5))).isEmpty();
        assertThat(failedGeneration()).isEqualTo(1.0);
        assertThat(reader.usageRecords("openai")).isEqualTo(1);
    }

    private double completedLiveCandidate() {
        return registry.find("patchatlas.run.completed")
                .tags("mode", "live", "verdict", "reproduction_candidate")
                .functionCounter()
                .count();
    }

    private double failedGeneration() {
        return registry.find("patchatlas.run.failed")
                .tags("mode", "live", "stage", "generation", "category", "generation_failure")
                .functionCounter()
                .count();
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

    private static GatedCandidate gated() {
        PersistedCandidatePatch patch = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        CandidateDraft draft = new CandidateDraft(patch.patchText(), patch.targetTest());
        PatchPreparationResult.PreparedCandidate prepared =
                new PatchPreparationResult.PreparedCandidate(
                        Path.of("."),
                        "",
                        patch.targetTest(),
                        new MavenTestCommand("", "c.T#m", MavenNetworkMode.OFFLINE));
        return GatedCandidate.afterSuccessfulGate(draft, prepared);
    }

    private static SideExecutionResult assertionFailureSide() {
        AttemptRecord attempt = AttemptRecord.executed(
                new SandboxExecution(
                        SandboxExecutionStatus.COMPLETED,
                        1,
                        Duration.ofMillis(1),
                        false,
                        List.of("mvn", "test"),
                        "log",
                        "maven:3.9-eclipse-temurin-21",
                        SandboxLimits.defaults(),
                        MavenNetworkMode.OFFLINE),
                new TestReport(List.of(new TestCaseResult(
                        "c.T",
                        "m",
                        Duration.ofMillis(1),
                        TestCaseStatus.FAILED,
                        "org.opentest4j.AssertionFailedError",
                        "x"))),
                TARGET);
        return new SideExecutionResult(List.of(attempt, attempt));
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

package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.StableSideEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
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

/** ��commitCandidate / complete 原子写与 ReplayResult round-trip。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class PostgresRunStoreCompleteTest {

    private static final String BUG = "c".repeat(40);
    private static final String FIXED = "d".repeat(40);
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
    void commitCandidateIsAtomicWithReplayingState() {
        ClaimedRun generating = claimLive("cand-1");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);

        ClaimedRun replaying = store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));

        assertThat(replaying.state()).isEqualTo(RunState.REPLAYING);
        assertThat(replaying.candidate()).contains(candidate);
        RunDetails details = store.findRun(replaying.runId()).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.REPLAYING);
        assertThat(details.candidate()).isPresent();
        assertThat(details.candidate().orElseThrow().patchSha256()).isEqualTo(candidate.patchSha256());
    }

    @Test
    void duplicateCandidateIsRejected() {
        ClaimedRun generating = claimLive("cand-dup");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));

        // 手工回退状态无法再 insert 同一 PK；用新 claim 句柄但同一 run 已有 candidate
        assertThatThrownBy(() -> store.commitCandidate(
                        new ClaimHandle(
                                generating.runId(),
                                generating.lease().token(),
                                generating.version(),
                                RunState.GENERATING),
                        GatedCandidateTestHelper.gated(
                                PersistedCandidatePatch.fromAccepted(PATCH + "\n", TARGET))))
                .isInstanceOf(Exception.class);
    }

    @Test
    void completeLiveResultRoundTripsReplayResult() {
        ClaimedRun generating = claimLive("live-complete");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        ClaimedRun replaying = store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));
        ClaimedRun opened = store.openReplayRound(ClaimHandle.from(replaying));

        SideExecutionResult primary = targetAssertionFailureSide();
        ReplayResult result = ReplayResult.live(
                ReplayVerdict.REPRODUCTION_CANDIDATE, TARGET, primary);

        RunDetails completed = store.complete(ClaimHandle.from(opened), result);
        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(completed.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(completed.failure()).isEmpty();

        ReplayResult loaded = store.loadReplayResult(opened.runId());
        assertThat(loaded.verdict()).isEqualTo(result.verdict());
        assertThat(loaded.mode()).isEqualTo(VerificationMode.LIVE);
        assertThat(loaded.primarySide().stableEvidence())
                .isEqualTo(StableSideEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(loaded.fixedSide()).isEmpty();
        assertThat(loaded.targetTest()).isEqualTo(TARGET);
    }

    @Test
    void completeHistoricalWithFixedSide() {
        ClaimedRun generating = claimHistorical("hist-complete");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        ClaimedRun replaying = store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));
        ClaimedRun opened = store.openReplayRound(ClaimHandle.from(replaying));

        ReplayResult result = ReplayResult.historicalWithFixed(
                ReplayVerdict.VALID_REPRODUCTION,
                TARGET,
                targetAssertionFailureSide(),
                targetPassedSide());

        store.complete(ClaimHandle.from(opened), result);
        ReplayResult loaded = store.loadReplayResult(opened.runId());
        assertThat(loaded.verdict()).isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(loaded.fixedSide()).isPresent();
        assertThat(loaded.fixedSide().orElseThrow().stableEvidence())
                .isEqualTo(StableSideEvidence.TARGET_PASSED);
    }

    @Test
    void completeHistoricalShortCircuit() {
        ClaimedRun generating = claimHistorical("hist-short");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        ClaimedRun replaying = store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));
        ClaimedRun opened = store.openReplayRound(ClaimHandle.from(replaying));

        ReplayResult result = ReplayResult.historicalShortCircuited(
                ReplayVerdict.NOT_REPRODUCED,
                TARGET,
                targetPassedSide(),
                "primary not assertion failure");

        store.complete(ClaimHandle.from(opened), result);
        ReplayResult loaded = store.loadReplayResult(opened.runId());
        assertThat(loaded.fixedSide()).isEmpty();
        assertThat(loaded.fixedNotExecutedReason()).contains("primary not assertion failure");
        assertThat(loaded.verdict()).isEqualTo(ReplayVerdict.NOT_REPRODUCED);
    }

    @Test
    void completeRollbackWhenFenceFailsLeavesNoAttempts() throws Exception {
        ClaimedRun generating = claimLive("rollback");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        ClaimedRun replaying = store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));
        ClaimedRun opened = store.openReplayRound(ClaimHandle.from(replaying));

        // 外部抬高 version，使 complete 的终态 UPDATE 失败并回滚 attempts
        bumpVersion(opened.runId());

        ReplayResult result = ReplayResult.live(
                ReplayVerdict.REPRODUCTION_CANDIDATE, TARGET, targetAssertionFailureSide());

        assertThatThrownBy(() -> store.complete(ClaimHandle.from(opened), result))
                .isInstanceOf(StaleClaimException.class);

        assertThat(countAttempts(opened.runId())).isZero();
        RunDetails details = store.findRun(opened.runId()).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.REPLAYING);
        assertThat(details.verdict()).isEmpty();
    }

    private ClaimedRun claimLive(String caseId) {
        store.submit(new RunSubmission(
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
                List.of(new SourceSnapshot("src/A.java", "class A {}"))));
        return store.claimNext("worker", Duration.ofMinutes(5)).orElseThrow();
    }

    private ClaimedRun claimHistorical(String caseId) {
        store.submit(new RunSubmission(
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
                null,
                List.of(new SourceSnapshot("src/A.java", "class A {}"))));
        return store.claimNext("worker", Duration.ofMinutes(5)).orElseThrow();
    }

    private void bumpVersion(UUID runId) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("UPDATE verification_run SET version = version + 1 WHERE id = '%s'".formatted(runId));
        }
    }

    private int countAttempts(UUID runId) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT COUNT(*) FROM replay_attempt WHERE run_id = '%s'".formatted(runId))) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static SideExecutionResult targetPassedSide() {
        AttemptRecord a = AttemptRecord.executed(
                completed(0), new TestReport(List.of(passed())), TARGET);
        return new SideExecutionResult(List.of(a, a));
    }

    private static SideExecutionResult targetAssertionFailureSide() {
        AttemptRecord a = AttemptRecord.executed(
                completed(1), new TestReport(List.of(failed())), TARGET);
        return new SideExecutionResult(List.of(a, a));
    }

    private static SandboxExecution completed(int exit) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exit,
                Duration.ofMillis(1),
                false,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static TestCaseResult passed() {
        return new TestCaseResult(
                "c.T", "m", Duration.ofMillis(1), TestCaseStatus.PASSED, null, null);
    }

    private static TestCaseResult failed() {
        return new TestCaseResult(
                "c.T",
                "m",
                Duration.ofMillis(1),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "x");
    }

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

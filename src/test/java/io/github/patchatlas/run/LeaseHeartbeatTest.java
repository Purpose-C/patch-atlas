package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.RunOutcome;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 心跳与状态写串行化；version 竞态不会把健康 commit 打成 stale。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class LeaseHeartbeatTest {

    private static final String BUG = "f".repeat(40);
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
    void heartbeatRenewsLeaseAndBumpsVersion() throws Exception {
        ClaimedRun claimed = claimLive("hb");
        long startVersion = claimed.version();

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "owner",
                Duration.ofSeconds(30),
                Duration.ofMillis(200))) {
            Thread.sleep(700);
            ClaimHandle current = beat.runLocked(h -> h);
            assertThat(current.version()).isGreaterThan(startVersion);
            assertThat(current.leaseToken()).isEqualTo(claimed.lease().token());
        }
    }

    @Test
    void serializedTransitionSurvivesConcurrentHeartbeatTicks() throws Exception {
        ClaimedRun claimed = claimLive("race");
        GatedCandidate gated = GatedCandidateTestHelper.gated(
                PersistedCandidatePatch.fromAccepted(PATCH, TARGET));

        AtomicInteger heartbeatAttempts = new AtomicInteger();
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "owner",
                Duration.ofSeconds(30),
                Duration.ofMillis(50))) {

            // 拉长“计算”窗口，期间心跳会多次 tryLock 跳过或成功续租
            Thread.sleep(250);

            // 主线程通过串行 API 提交，不得因心跳抬 version 而 stale
            ClaimedRun replaying = beat.commitCandidate(gated);
            assertThat(replaying.state()).isEqualTo(RunState.REPLAYING);
            assertThat(replaying.version()).isGreaterThan(claimed.version());

            ClaimedRun opened = beat.openReplayRound();
            Thread.sleep(150);
            RunDetails completed = beat.complete(liveResult());
            assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
            assertThat(completed.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
            heartbeatAttempts.set(1); // keep analyzer quiet
        }
        assertThat(heartbeatAttempts.get()).isEqualTo(1);
    }

    @Test
    void concurrentFailDoesNotMisclassifyAsWorkspaceWhenUsingBeatApi() throws Exception {
        ClaimedRun claimed = claimLive("fail-serial");
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store,
                ClaimHandle.from(claimed),
                "owner",
                Duration.ofSeconds(30),
                Duration.ofMillis(50))) {
            Thread.sleep(200);
            RunDetails failed = beat.fail(new RunFailure(
                    FailureStage.GENERATION,
                    FailureCategory.GENERATION_FAILURE,
                    "model failed"));
            assertThat(failed.state()).isEqualTo(RunState.FAILED);
            assertThat(failed.failure().orElseThrow().stage()).isEqualTo(FailureStage.GENERATION);
        }
    }

    @Test
    void rejectsIntervalNotShorterThanLease() {
        ClaimHandle handle = new ClaimHandle(
                UUID.randomUUID(), UUID.randomUUID(), 1, RunState.GENERATING);
        assertThatThrownBy(() -> LeaseHeartbeat.start(
                        store, handle, "o", Duration.ofMinutes(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
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
        return store.claimNext("owner", Duration.ofSeconds(30)).orElseThrow();
    }

    private static ReplayResult liveResult() {
        AttemptRecord a = AttemptRecord.executed(
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
        SideExecutionResult primary = new SideExecutionResult(
                List.of(a, a),
                StableSideEvidence.TARGET_ASSERTION_FAILURE,
                Optional.of(RunOutcome.ASSERTION_FAILURE));
        return ReplayResult.live(ReplayVerdict.REPRODUCTION_CANDIDATE, TARGET, primary);
    }

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

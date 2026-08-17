package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SingleAttemptEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 核心 store seam：幂等提交、keyset 列表、详情正式证据投影。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class PostgresRunStoreApiSeamTest {

    private static final String BUG = "a".repeat(40);
    private static final String FIXED = "b".repeat(40);
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
    void submitIdempotentReplaysSameKeyAndFingerprint() {
        IdempotencyKey key = IdempotencyKey.parse("same-key-1");
        RunSubmission submission = liveSubmission("idem-1");
        String fp = SubmissionFingerprint.sha256Hex(submission);

        IdempotentSubmitResult first = store.submitIdempotent(key, fp, submission);
        IdempotentSubmitResult second = store.submitIdempotent(key, fp, submission);

        assertThat(first).isInstanceOf(IdempotentSubmitResult.Accepted.class);
        assertThat(second).isInstanceOf(IdempotentSubmitResult.Accepted.class);
        var a1 = (IdempotentSubmitResult.Accepted) first;
        var a2 = (IdempotentSubmitResult.Accepted) second;
        assertThat(a1.created()).isTrue();
        assertThat(a2.created()).isFalse();
        assertThat(a2.runId()).isEqualTo(a1.runId());
        assertThat(a2.state()).isEqualTo(RunState.QUEUED);
    }

    @Test
    void submitIdempotentConflictsOnDifferentFingerprint() {
        IdempotencyKey key = IdempotencyKey.parse("conflict-key");
        RunSubmission a = liveSubmission("body-a");
        RunSubmission b = liveSubmission("body-b");
        String fpA = SubmissionFingerprint.sha256Hex(a);
        String fpB = SubmissionFingerprint.sha256Hex(b);
        assertThat(fpA).isNotEqualTo(fpB);

        IdempotentSubmitResult first = store.submitIdempotent(key, fpA, a);
        assertThat(first).isInstanceOf(IdempotentSubmitResult.Accepted.class);

        IdempotentSubmitResult conflict = store.submitIdempotent(key, fpB, b);
        assertThat(conflict).isInstanceOf(IdempotentSubmitResult.Conflict.class);
        assertThat(((IdempotentSubmitResult.Conflict) conflict).existingRunId())
                .isEqualTo(((IdempotentSubmitResult.Accepted) first).runId());
    }

    @Test
    void concurrentSubmitIdempotentYieldsSingleRun() throws Exception {
        IdempotencyKey key = IdempotencyKey.parse("race-key");
        RunSubmission submission = liveSubmission("race");
        String fp = SubmissionFingerprint.sha256Hex(submission);

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotentSubmitResult>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            PostgresRunStore workerStore = new PostgresRunStore(dataSource());
            futures.add(pool.submit(() -> {
                start.await();
                return workerStore.submitIdempotent(key, fp, submission);
            }));
        }
        start.countDown();

        Set<UUID> ids = new HashSet<>();
        int created = 0;
        for (Future<IdempotentSubmitResult> future : futures) {
            IdempotentSubmitResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(IdempotentSubmitResult.Accepted.class);
            var accepted = (IdempotentSubmitResult.Accepted) result;
            ids.add(accepted.runId());
            if (accepted.created()) {
                created++;
            }
        }
        pool.shutdownNow();

        assertThat(ids).hasSize(1);
        assertThat(created).isEqualTo(1);
    }

    @Test
    void listRunsKeysetHasNoDuplicatesOrGaps() {
        List<UUID> submitted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RunSubmission s = liveSubmission("page-" + i);
            String fp = SubmissionFingerprint.sha256Hex(s);
            IdempotentSubmitResult r =
                    store.submitIdempotent(IdempotencyKey.parse("page-key-" + i), fp, s);
            submitted.add(((IdempotentSubmitResult.Accepted) r).runId());
        }

        List<UUID> seen = new ArrayList<>();
        Optional<RunListCursor> cursor = Optional.empty();
        int pages = 0;
        while (true) {
            RunListPage page = store.listRuns(2, cursor);
            pages++;
            for (RunSummary row : page.items()) {
                seen.add(row.runId());
            }
            if (page.nextCursor().isEmpty()) {
                break;
            }
            cursor = page.nextCursor().map(RunListCursor::decode);
            assertThat(pages).isLessThan(10);
        }

        assertThat(seen).hasSize(5);
        assertThat(new HashSet<>(seen)).hasSize(5);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(submitted);
        // keyset 默认 created_at DESC：后提交的在前
        assertThat(seen.getFirst()).isEqualTo(submitted.get(4));
        assertThat(seen.getLast()).isEqualTo(submitted.get(0));
    }

    @Test
    void findRunDetailProjectsFormalAttemptTargetCaseFacts() {
        ClaimedRun generating = claimAndGenerate("detail-1");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        ClaimedRun replaying =
                store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));
        ClaimedRun opened = store.openReplayRound(ClaimHandle.from(replaying));

        ReplayResult result = ReplayResult.live(
                ReplayVerdict.REPRODUCTION_CANDIDATE, TARGET, targetAssertionFailureSide());
        store.complete(ClaimHandle.from(opened), result);

        RunDetailView detail = store.findRunDetail(opened.runId()).orElseThrow();
        assertThat(detail.state()).isEqualTo(RunState.COMPLETED);
        assertThat(detail.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(detail.candidate()).isPresent();
        assertThat(detail.attempts()).isNotEmpty();

        RunAttemptView attempt = detail.attempts().getFirst();
        assertThat(attempt.targetEvidence()).isEqualTo(SingleAttemptEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(attempt.sandboxStatus()).contains(SandboxExecutionStatus.COMPLETED);
        assertThat(attempt.exitCode()).contains(1);
        assertThat(attempt.networkMode()).isPresent();
        assertThat(attempt.networkMode().orElseThrow()).contains("OFFLINE");

        assertThat(detail.generation().usageRecordCount()).isZero();
        assertThat(detail.generation().usageStatus())
                .isEqualTo(RecordedUsageStatus.NONE_RECORDED);

        RunAttemptView.TargetTestCaseView tc = attempt.targetTestCase().orElseThrow();
        assertThat(tc.className()).isEqualTo("c.T");
        assertThat(tc.methodName()).isEqualTo("m");
        assertThat(tc.status()).isIn(TestCaseStatus.FAILED.name(), "FAILED");
        assertThat(tc.message()).contains("assertion failed");
        assertThat(tc.elapsedMs()).contains(42L);
        assertThat(tc.exceptionType()).contains("org.opentest4j.AssertionFailedError");
    }

    @Test
    void findRunDetailExposesDefaultPurposeAndAgentPatchProvenance() {
        ClaimedRun generating = claimAndGenerate("provenance-1");
        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));

        RunDetailView detail = store.findRunDetail(generating.runId()).orElseThrow();

        assertThat(detail.purpose()).isEqualTo(RunPurpose.STANDARD);
        assertThat(detail.candidate()).get().extracting(RunDetailView.CandidateView::provenance)
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
    }

    @Test
    void submitAgentBenchmarkPersistsPurposeWithoutChangingGenerationFlow() {
        UUID runId = store.submitAgentBenchmark(liveSubmission("agent-benchmark-1"));
        ClaimedRun generating = LocatingTestSupport.commitPinned(
                store, store.claimNext("worker", Duration.ofMinutes(5)).orElseThrow());
        assertThat(generating.runId()).isEqualTo(runId);

        PersistedCandidatePatch candidate = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);
        store.commitCandidate(ClaimHandle.from(generating), GatedCandidateTestHelper.gated(candidate));

        RunDetailView detail = store.findRunDetail(runId).orElseThrow();
        assertThat(detail.purpose()).isEqualTo(RunPurpose.AGENT_BENCHMARK);
        assertThat(detail.candidate()).get().extracting(RunDetailView.CandidateView::provenance)
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
    }

    @Test
    void submitDiagnosticPersistsDiagnosticPurpose() {
        UUID runId = store.submitDiagnostic(liveSubmission("diagnostic-1"));

        RunDetailView detail = store.findRunDetail(runId).orElseThrow();
        assertThat(detail.purpose()).isEqualTo(RunPurpose.DIAGNOSTIC);
    }

    @Test
    void startCalibrationSkipsGenerationAndPersistsKnownTriggerProvenance() {
        PersistedCandidatePatch knownTrigger = PersistedCandidatePatch.fromAccepted(PATCH, TARGET);

        ClaimedRun replaying = store.startCalibration(
                historicalSubmission("calibration-1"),
                GatedCandidateTestHelper.gated(knownTrigger),
                "calibrator",
                Duration.ofMinutes(5));

        assertThat(replaying.state()).isEqualTo(RunState.REPLAYING);
        assertThat(replaying.replayRound()).isZero();
        assertThat(replaying.candidate()).get().extracting(PersistedCandidatePatch::provenance)
                .isEqualTo(TestPatchProvenance.KNOWN_TRIGGER);

        RunDetails restored = store.findRun(replaying.runId()).orElseThrow();
        assertThat(restored.candidate()).get().extracting(PersistedCandidatePatch::provenance)
                .isEqualTo(TestPatchProvenance.KNOWN_TRIGGER);

        RunDetailView detail = store.findRunDetail(replaying.runId()).orElseThrow();
        assertThat(detail.purpose()).isEqualTo(RunPurpose.CALIBRATION);
        assertThat(detail.generation().attemptCount()).isZero();
        assertThat(detail.generation().modelProvider()).isNull();
        assertThat(detail.generation().modelName()).isNull();
        assertThat(detail.candidate()).get().extracting(RunDetailView.CandidateView::provenance)
                .isEqualTo(TestPatchProvenance.KNOWN_TRIGGER);
    }

    @Test
    void findRunByCaseReturnsExistingPurposeScopedRun() {
        UUID agentId = store.submitAgentBenchmark(liveSubmission("shared-case"));
        ClaimedRun calibration = store.startCalibration(
                historicalSubmission("shared-case"),
                GatedCandidateTestHelper.gated(PersistedCandidatePatch.fromAccepted(PATCH, TARGET)),
                "calibrator",
                Duration.ofMinutes(5));

        RunDetailView agent = store.findRunByCase("shared-case", RunPurpose.AGENT_BENCHMARK).orElseThrow();
        RunDetailView cal = store.findRunByCase("shared-case", RunPurpose.CALIBRATION).orElseThrow();

        assertThat(agent.runId()).isEqualTo(agentId);
        assertThat(cal.runId()).isEqualTo(calibration.runId());
        assertThat(store.findRunByCase("shared-case", RunPurpose.STANDARD)).isEmpty();
        assertThat(store.findRunByCase("missing-case", RunPurpose.AGENT_BENCHMARK)).isEmpty();
    }

    private ClaimedRun claimAndGenerate(String caseId) {
        RunSubmission s = liveSubmission(caseId);
        store.submitIdempotent(
                IdempotencyKey.parse("claim-" + caseId),
                SubmissionFingerprint.sha256Hex(s),
                s);
        return LocatingTestSupport.commitPinned(
                store, store.claimNext("worker", Duration.ofMinutes(5)).orElseThrow());
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
                Duration.ofMillis(10),
                false,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }

    private static TestCaseResult failed() {
        return new TestCaseResult(
                "c.T",
                "m",
                Duration.ofMillis(42),
                TestCaseStatus.FAILED,
                "org.opentest4j.AssertionFailedError",
                "assertion failed");
    }

    private static RunSubmission liveSubmission(String caseId) {
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
                MavenNetworkMode.OFFLINE,
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }

    private static RunSubmission historicalSubmission(String caseId) {
        return new RunSubmission(
                VerificationMode.HISTORICAL,
                caseId,
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                BUG,
                FIXED,
                "",
                "21",
                MavenNetworkMode.OFFLINE,
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

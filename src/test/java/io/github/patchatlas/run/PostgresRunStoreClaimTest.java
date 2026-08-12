package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * ��submit / find / claim 与并发领取（真实 PostgreSQL）。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class PostgresRunStoreClaimTest {

    private static final String BUG = "a".repeat(40);
    private static final String FIXED_SENTINEL = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

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

        javax.sql.DataSource ds = dataSource();
        store = new PostgresRunStore(ds);
    }

    @Test
    void submitAndFindRoundTripsImmutableInput() {
        RunSubmission submission = historicalSubmission();
        UUID id = store.submit(submission);

        RunDetails details = store.findRun(id).orElseThrow();
        assertThat(details.runId()).isEqualTo(id);
        assertThat(details.state()).isEqualTo(RunState.QUEUED);
        assertThat(details.mode()).isEqualTo(VerificationMode.HISTORICAL);
        assertThat(details.buggyRevision()).isEqualTo(BUG);
        assertThat(details.fixedRevision()).isEqualTo(FIXED_SENTINEL);
        assertThat(details.issueTitle()).isEqualTo("title");
        assertThat(details.candidate()).isEmpty();
        assertThat(details.verdict()).isEmpty();
    }

    @Test
    void loadGenerationInputOmitsFixedRevisionSentinel() {
        UUID id = store.submit(historicalSubmission());

        GenerationInput input = store.loadGenerationInput(id);
        assertThat(input.generatorContext().buggyRevision()).isEqualTo(BUG);
        assertThat(input.sourceSnapshots()).hasSize(1);
        assertThat(input.toString()).doesNotContain(FIXED_SENTINEL);
        assertThat(input.toString()).doesNotContain("fixedRevision");
    }

    @Test
    void claimNextMovesQueuedToGeneratingWithLease() {
        UUID id = store.submit(liveSubmission("c-claim"));
        ClaimedRun claimed = store.claimNext("worker-a", Duration.ofMinutes(5)).orElseThrow();

        assertThat(claimed.runId()).isEqualTo(id);
        assertThat(claimed.state()).isEqualTo(RunState.GENERATING);
        assertThat(claimed.version()).isEqualTo(1);
        assertThat(claimed.lease().owner()).isEqualTo("worker-a");
        assertThat(claimed.candidate()).isEmpty();

        RunDetails details = store.findRun(id).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.GENERATING);
        assertThat(details.version()).isEqualTo(1);
    }

    @Test
    void claimNextIsEmptyWhenQueueEmpty() {
        assertThat(store.claimNext("worker-a", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void concurrentClaimYieldsExactlyOneOwner() throws Exception {
        UUID id = store.submit(liveSubmission("c-race"));

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<ClaimedRun>>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            String owner = "worker-" + i;
            PostgresRunStore workerStore = new PostgresRunStore(dataSource());
            futures.add(pool.submit(() -> {
                start.await();
                return workerStore.claimNext(owner, Duration.ofMinutes(2));
            }));
        }
        start.countDown();

        List<ClaimedRun> wins = new ArrayList<>();
        for (Future<Optional<ClaimedRun>> future : futures) {
            future.get(30, TimeUnit.SECONDS).ifPresent(wins::add);
        }
        pool.shutdownNow();

        assertThat(wins).hasSize(1);
        assertThat(wins.getFirst().runId()).isEqualTo(id);

        // 第二个 store 在租约有效期内不能再领到同一 Run
        assertThat(store.claimNext("late-worker", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void claimOrdersByCreatedAtThenId() {
        UUID first = store.submit(liveSubmission("order-1"));
        UUID second = store.submit(liveSubmission("order-2"));

        ClaimedRun a = store.claimNext("w", Duration.ofMinutes(1)).orElseThrow();
        ClaimedRun b = store.claimNext("w", Duration.ofMinutes(1)).orElseThrow();

        assertThat(a.runId()).isEqualTo(first);
        assertThat(b.runId()).isEqualTo(second);
    }

    @Test
    void activeLeaseBlocksOtherOwner() {
        UUID id = store.submit(liveSubmission("lease-hold"));
        ClaimedRun ownerA = store.claimNext("owner-a", Duration.ofMinutes(10)).orElseThrow();
        assertThat(ownerA.runId()).isEqualTo(id);
        assertThat(store.claimNext("owner-b", Duration.ofMinutes(10))).isEmpty();
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
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }

    private static RunSubmission historicalSubmission() {
        return new RunSubmission(
                VerificationMode.HISTORICAL,
                "hist-1",
                "https://github.com/ex/repo.git",
                "MIT",
                "https://github.com/ex/repo/issues/1",
                "title",
                "body",
                BUG,
                FIXED_SENTINEL,
                "",
                "17",
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}

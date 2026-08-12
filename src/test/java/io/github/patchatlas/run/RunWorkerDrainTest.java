package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.patchatlas.replay.VerificationMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 常驻 drain 防重入、关闭停领、连续消费。 */
class RunWorkerDrainTest {

    @Test
    void tickDelegatesToWorkerWhenAccepting() {
        Issue2TestWorker worker = mock(Issue2TestWorker.class);
        RunDetails details = queued(UUID.randomUUID());
        when(worker.processNext("owner-a")).thenReturn(Optional.of(details));

        RunWorkerDrain drain = new RunWorkerDrain(worker, "owner-a");
        assertThat(drain.tick()).contains(details);
        verify(worker).processNext("owner-a");
    }

    @Test
    void stopAcceptingBlocksFurtherClaims() {
        Issue2TestWorker worker = mock(Issue2TestWorker.class);
        when(worker.processNext(anyString())).thenReturn(Optional.of(queued(UUID.randomUUID())));

        RunWorkerDrain drain = new RunWorkerDrain(worker, "owner-a");
        drain.stopAccepting();
        assertThat(drain.isAccepting()).isFalse();
        assertThat(drain.tick()).isEmpty();
        verify(worker, never()).processNext(anyString());
    }

    @Test
    void concurrentTicksAreSerialized() throws Exception {
        Issue2TestWorker worker = mock(Issue2TestWorker.class);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(worker.processNext("owner-a")).thenAnswer(inv -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            concurrent.decrementAndGet();
            return Optional.of(queued(UUID.randomUUID()));
        });

        RunWorkerDrain drain = new RunWorkerDrain(worker, "owner-a");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(drain::tick);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(drain::tick);
            // 第二个在第一个持锁时应直接 empty（tryLock 失败）
            assertThat(second.get(2, TimeUnit.SECONDS)).isEmpty();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(maxConcurrent.get()).isEqualTo(1);
            verify(worker, times(1)).processNext("owner-a");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shutdownDuringTickWaitsThenBlocksNewClaims() throws Exception {
        Issue2TestWorker worker = mock(Issue2TestWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();

        when(worker.processNext("owner-a")).thenAnswer(inv -> {
            claims.incrementAndGet();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(queued(UUID.randomUUID()));
        });

        RunWorkerDrain drain = new RunWorkerDrain(worker, "owner-a");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var inFlight = pool.submit(drain::tick);
            // latch：确定 processNext 已持锁，不依赖 sleep
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // tryLock(0)：锁被 tick 占用时立即 false，证明关闭会观察到进行中的 tick
            assertThat(drain.shutdown(java.time.Duration.ZERO)).isFalse();
            assertThat(drain.isAccepting()).isFalse();
            // 关闭后不得新 claim（accepting=false；亦不会与 in-flight 并发 processNext）
            assertThat(drain.tick()).isEmpty();
            verify(worker, times(1)).processNext("owner-a");

            release.countDown();
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isPresent();
            // tick 结束后再 shutdown 应立即成功
            assertThat(drain.shutdown(java.time.Duration.ofSeconds(5))).isTrue();
            assertThat(claims.get()).isEqualTo(1);
            assertThat(drain.tick()).isEmpty();
            verify(worker, times(1)).processNext("owner-a");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void drainStopsOnEmptyAndRespectsMax() {
        Issue2TestWorker worker = mock(Issue2TestWorker.class);
        when(worker.processNext("owner-a"))
                .thenReturn(Optional.of(queued(UUID.randomUUID())))
                .thenReturn(Optional.of(queued(UUID.randomUUID())))
                .thenReturn(Optional.empty());

        RunWorkerDrain drain = new RunWorkerDrain(worker, "owner-a");
        assertThat(drain.drain(10)).isEqualTo(2);
        verify(worker, times(3)).processNext("owner-a");

        when(worker.processNext("owner-a")).thenReturn(Optional.of(queued(UUID.randomUUID())));
        assertThat(drain.drain(1)).isEqualTo(1);
        verify(worker, times(4)).processNext("owner-a");
    }

    @Test
    void workerRuntimeExceptionIsSwallowedAndUnlocks() {
        Issue2TestWorker worker = mock(Issue2TestWorker.class);
        when(worker.processNext("owner-a"))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(Optional.of(queued(UUID.randomUUID())));

        RunWorkerDrain drain = new RunWorkerDrain(worker, "owner-a");
        assertThat(drain.tick()).isEmpty();
        assertThat(drain.tick()).isPresent();
        verify(worker, times(2)).processNext("owner-a");
    }

    private static RunDetails queued(UUID id) {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        return new RunDetails(
                id,
                VerificationMode.LIVE,
                RunState.QUEUED,
                0L,
                null,
                "https://github.com/ex/repo.git",
                "t",
                "a".repeat(40),
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now,
                now,
                null);
    }
}

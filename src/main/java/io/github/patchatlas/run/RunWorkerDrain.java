package io.github.patchatlas.run;

import io.github.patchatlas.observability.RunEvents;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单进程串行 drain：启动恢复与常驻消费共用。
 *
 * <p>领取入口由 {@link #accepting} 控制；tick 全程持有 tryLock，关闭时先关入口再
 * 在时限内等待锁，避免“已关闭仍 claim”与“销毁 Bean 时 tick 仍在跑”。
 */
public final class RunWorkerDrain {

    private static final Logger log = LoggerFactory.getLogger(RunWorkerDrain.class);

    private final Issue2TestWorker worker;
    private final String owner;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final ReentrantLock tickLock = new ReentrantLock();

    public RunWorkerDrain(Issue2TestWorker worker, String owner) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void stopAccepting() {
        accepting.set(false);
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    /**
     * 关闭领取并等待当前 tick 结束。
     *
     * @return {@code true} 若在时限内进入空闲；{@code false} 若超时仍有 tick 持锁
     */
    public boolean shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        accepting.set(false);
        long nanos = Math.max(0L, timeout.toNanos());
        try {
            if (tickLock.tryLock(nanos, TimeUnit.NANOSECONDS)) {
                tickLock.unlock();
                return true;
            }
            log.warn("worker drain shutdown timed out after {}", timeout);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("worker drain shutdown interrupted");
            return false;
        }
    }

    public Optional<RunDetails> tick() {
        if (!accepting.get()) {
            return Optional.empty();
        }
        if (!tickLock.tryLock()) {
            return Optional.empty();
        }
        try {
            if (!accepting.get()) {
                return Optional.empty();
            }
            return worker.processNext(owner);
        } catch (StaleClaimException stale) {
            RunEvents.claimStale(stale.runId());
            return Optional.empty();
        } catch (RuntimeException ex) {
            RunEvents.workerTickFailed(ex);
            return Optional.empty();
        } finally {
            tickLock.unlock();
        }
    }

    public int drain(int maxRuns) {
        int max = Math.max(1, maxRuns);
        int processed = 0;
        for (int i = 0; i < max; i++) {
            if (tick().isEmpty()) {
                break;
            }
            processed++;
        }
        return processed;
    }
}

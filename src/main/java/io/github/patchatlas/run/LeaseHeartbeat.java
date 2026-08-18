package io.github.patchatlas.run;

import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.ReplayResult;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 长操作期间按固定节奏续租，并把所有会 bump version 的 Store 写操作与心跳串行化。
 *
 * <p>主线程不得先 {@code current()} 再异步写库；应使用 {@link #commitCandidate} /
 * {@link #fail} / {@link #openReplayRound} / {@link #complete}，在同一把锁内完成
 * 「读 handle → 写 Store → 更新 handle」。
 */
public final class LeaseHeartbeat implements AutoCloseable {

    private final PostgresRunStore store;
    private final String owner;
    private final Duration leaseDuration;
    private final ReentrantLock lock = new ReentrantLock();
    private ClaimHandle handle;
    private volatile RuntimeException failure;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> future;

    private LeaseHeartbeat(
            PostgresRunStore store,
            ClaimHandle initial,
            String owner,
            Duration leaseDuration,
            Duration interval) {
        this.store = store;
        this.owner = owner;
        this.leaseDuration = leaseDuration;
        this.handle = initial;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "lease-heartbeat-" + initial.runId());
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        long periodMs = Math.max(50L, interval.toMillis());
        this.future = scheduler.scheduleAtFixedRate(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public static LeaseHeartbeat start(
            PostgresRunStore store,
            ClaimHandle initial,
            String owner,
            Duration leaseDuration,
            Duration interval) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(interval, "interval");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (interval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeat interval must be shorter than leaseDuration");
        }
        return new LeaseHeartbeat(store, initial, owner, leaseDuration, interval);
    }

    public ClaimedRun commitCandidate(GatedCandidate gated) {
        return runTransition(h -> store.commitCandidate(h, gated));
    }

    public ClaimedRun commitContext(ContextOrigin origin, List<SourceSnapshot> snapshots) {
        return runTransition(h -> store.commitContext(h, origin, snapshots));
    }

    public void replaceLocatingTrace(List<LocatingTraceStep> steps) {
        runLocked(h -> {
            store.replaceLocatingTrace(h, steps);
            return null;
        });
    }

    public void beginLocatingTrace() {
        runLocked(h -> {
            store.beginLocatingTrace(h);
            return null;
        });
    }

    public void appendLocatingTrace(LocatingTraceStep step) {
        runLocked(h -> {
            store.appendLocatingTrace(h, step);
            return null;
        });
    }

    public void recordLocatingUsage(Optional<ModelUsage> usage) {
        runLocked(h -> {
            store.recordLocatingUsage(h, usage);
            return null;
        });
    }

    public PostgresRunStore.ReservedGenerationAttempt reserveGenerationAttempt(
            String provider, String modelName) {
        lock.lock();
        try {
            throwIfFailed();
            PostgresRunStore.ReservedGenerationAttempt reserved =
                    store.reserveGenerationAttempt(handle, provider, modelName);
            handle = ClaimHandle.from(reserved.claim());
            return reserved;
        } catch (GenerationAttemptsExhaustedException exhausted) {
            throw exhausted;
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            }
            throw ex;
        } finally {
            lock.unlock();
        }
    }

    public ClaimedRun recordModelUsage(ModelUsage usage) {
        return recordModelUsage(usage, CompletionDiagnostics.unknown());
    }

    public ClaimedRun recordModelUsage(ModelUsage usage, CompletionDiagnostics diagnostics) {
        return runTransition(h -> store.recordModelUsage(h, usage, diagnostics));
    }

    public RunDetails fail(RunFailure failure) {
        return runTerminal(h -> store.fail(h, failure));
    }

    public ClaimedRun openReplayRound() {
        return runTransition(h -> store.openReplayRound(h));
    }

    public RunDetails complete(ReplayResult result) {
        return runTerminal(h -> store.complete(h, result));
    }

    /**
     * 与心跳串行执行任意 Store 写（测试/扩展用）。action 必须使用传入的 handle。
     */
    public <T> T runLocked(Function<ClaimHandle, T> action) {
        Objects.requireNonNull(action, "action");
        lock.lock();
        try {
            throwIfFailed();
            return action.apply(handle);
        } finally {
            lock.unlock();
        }
    }

    private ClaimedRun runTransition(Function<ClaimHandle, ClaimedRun> action) {
        lock.lock();
        try {
            throwIfFailed();
            ClaimedRun next = action.apply(handle);
            handle = ClaimHandle.from(next);
            return next;
        } catch (GenerationAttemptsExhaustedException exhausted) {
            // 领域结果：额度用尽，不毒化心跳；由 GenerationRunSession 转 fail
            throw exhausted;
        } catch (RuntimeException ex) {
            // stale 等：记录，避免心跳继续用旧 handle 刷库
            if (failure == null) {
                failure = ex;
            }
            throw ex;
        } finally {
            lock.unlock();
        }
    }

    private RunDetails runTerminal(Function<ClaimHandle, RunDetails> action) {
        lock.lock();
        try {
            throwIfFailed();
            return action.apply(handle);
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            }
            throw ex;
        } finally {
            lock.unlock();
        }
    }

    private void tick() {
        if (failure != null) {
            return;
        }
        if (!lock.tryLock()) {
            // 主线程正在状态迁移，跳过本拍
            return;
        }
        try {
            if (failure != null) {
                return;
            }
            ClaimedRun renewed = store.renewLease(handle, owner, leaseDuration);
            handle = ClaimHandle.from(renewed);
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            }
        } finally {
            lock.unlock();
        }
    }

    private void throwIfFailed() {
        RuntimeException error = failure;
        if (error != null) {
            throw error;
        }
    }

    @Override
    public void close() {
        future.cancel(false);
        scheduler.shutdownNow();
    }
}

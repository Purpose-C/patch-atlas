package io.github.patchatlas.benchmark;

import io.github.patchatlas.run.Issue2TestWorker;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunPurpose;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class PostgresBenchmarkStore implements FormalBenchmarkRunner.Store {

    private final PostgresRunStore store;

    PostgresBenchmarkStore(PostgresRunStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<RunDetailView> findRunByCase(String caseId, RunPurpose purpose) {
        return store.findRunByCase(caseId, purpose);
    }

    @Override
    public Optional<RunDetailView> findRunDetail(UUID runId) {
        return store.findRunDetail(runId);
    }
}

final class WorkerBackedWaiter implements FormalBenchmarkRunner.RunWaiter {

    private final FormalBenchmarkRunner.Store store;
    private final Issue2TestWorker worker;
    private final String owner;
    private final Duration pollInterval;

    WorkerBackedWaiter(
            FormalBenchmarkRunner.Store store,
            Issue2TestWorker worker,
            String owner,
            Duration pollInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    }

    @Override
    public Optional<RunDetailView> awaitTerminal(UUID runId, Duration budget) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(budget, "budget");
        long deadline = System.nanoTime() + budget.toNanos();
        while (true) {
            Optional<RunDetailView> view = store.findRunDetail(runId);
            if (view.isPresent() && view.orElseThrow().state().isTerminal()) {
                return view;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            worker.processNext(owner);
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }
}

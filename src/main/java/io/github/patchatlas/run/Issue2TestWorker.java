package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.run.RunCorrelation;
import io.github.patchatlas.run.RunEvents;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Issue2Test phase dispatcher：LOCATING / GENERATING / REPLAYING 分别委托独立编排器。
 */
public final class Issue2TestWorker {

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(15);
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(2);

    private final PostgresRunStore store;
    private final LocatingCoordinator locatingCoordinator;
    private final CandidateGenerationCoordinator generationCoordinator;
    private final FormalReplayCoordinator replayCoordinator;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    public Issue2TestWorker(
            PostgresRunStore store,
            LocatingCoordinator locatingCoordinator,
            CandidateGenerationCoordinator generationCoordinator,
            FormalReplayCoordinator replayCoordinator,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.locatingCoordinator = Objects.requireNonNull(locatingCoordinator, "locatingCoordinator");
        this.generationCoordinator =
                Objects.requireNonNull(generationCoordinator, "generationCoordinator");
        this.replayCoordinator = Objects.requireNonNull(replayCoordinator, "replayCoordinator");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseDuration");
        }
    }

    public Optional<RunDetails> processNext(String owner) {
        Objects.requireNonNull(owner, "owner");
        Optional<ClaimedRun> claimed = store.claimNext(owner, leaseDuration, this::onRecoveryExhausted);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedRun run = claimed.get();
        try (var ignored = RunCorrelation.open(run.runId())) {
            if (run.recoveryCount() > 0) {
                RunEvents.runRecovered(run.runId(), run.mode(), run.recoveryCount());
            } else {
                RunEvents.runClaimed(run.runId(), run.mode(), run.recoveryCount());
            }
            try {
                return Optional.of(process(run, owner));
            } catch (StaleClaimException stale) {
                throw stale;
            } catch (RuntimeException ex) {
                RunEvents.workerTickFailed(ex);
                return Optional.empty();
            }
        }
    }

    private void onRecoveryExhausted(RunDetails details) {
        RunEvents.runFailed(
                details.runId(),
                details.mode(),
                details.failure().orElseThrow());
    }

    private RunDetails process(ClaimedRun claimed, String owner) {
        ClaimedRun current = claimed;
        if (current.state() == RunState.LOCATING) {
            Optional<ClaimedRun> afterLocate = locatePhase(current, owner);
            if (afterLocate.isEmpty()) {
                return store.findRun(claimed.runId()).orElseThrow();
            }
            current = afterLocate.get();
        }
        if (current.state() == RunState.GENERATING) {
            Optional<ClaimedRun> afterGenerate = generatePhase(current, owner);
            if (afterGenerate.isEmpty()) {
                return store.findRun(claimed.runId()).orElseThrow();
            }
            current = afterGenerate.get();
        }
        if (current.state() == RunState.REPLAYING) {
            return replayPhase(current, owner);
        }
        throw new IllegalStateException("unexpected claimed state " + current.state());
    }

    private Optional<ClaimedRun> locatePhase(ClaimedRun claimed, String owner) {
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval)) {
            GenerationInput input = store.loadGenerationInput(claimed.runId());
            LocatingRunSession session = new LeaseHeartbeatLocatingRunSession(beat);
            return switch (locatingCoordinator.run(claimed, input, session)) {
                case LocatingCoordinator.Result.ContextCommitted committed ->
                        Optional.of(committed.claim());
                case LocatingCoordinator.Result.RunFailed ignored -> Optional.empty();
            };
        }
    }

    private Optional<ClaimedRun> generatePhase(ClaimedRun claimed, String owner) {
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval)) {
            GenerationInput input = store.loadGenerationInput(claimed.runId());
            var executionPolicy = store.loadExecutionPolicy(claimed.runId());
            RunPurpose purpose = store.findRunDetail(claimed.runId()).orElseThrow().purpose();
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(beat, purpose);
            CandidateGenerationCoordinator.Result result =
                    generationCoordinator.run(input, executionPolicy, session);
            return switch (result) {
                case CandidateGenerationCoordinator.Result.CandidateCommitted committed ->
                        Optional.of(committed.claim());
                case CandidateGenerationCoordinator.Result.RunFailed ignored -> Optional.empty();
            };
        }
    }

    private RunDetails replayPhase(ClaimedRun claimed, String owner) {
        try (ReplayRunSession session = LeaseHeartbeatReplayRunSession.open(
                store, claimed, owner, leaseDuration, heartbeatInterval)) {
            return replayCoordinator.run(claimed, session);
        }
    }

}

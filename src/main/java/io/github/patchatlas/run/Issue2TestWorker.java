package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Issue2Test phase dispatcher：GENERATING 与 REPLAYING 分别委托独立编排器。
 */
public final class Issue2TestWorker {

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(15);
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(2);

    private final PostgresRunStore store;
    private final CandidateGenerationCoordinator generationCoordinator;
    private final FormalReplayCoordinator replayCoordinator;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    public Issue2TestWorker(
            PostgresRunStore store,
            CandidateGenerationCoordinator generationCoordinator,
            FormalReplayCoordinator replayCoordinator,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        this.store = Objects.requireNonNull(store, "store");
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
        Optional<ClaimedRun> claimed = store.claimNext(owner, leaseDuration);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(process(claimed.get(), owner));
    }

    private RunDetails process(ClaimedRun claimed, String owner) {
        ClaimedRun current = claimed;
        if (current.state() == RunState.GENERATING) {
            Optional<ClaimedRun> afterGenerate = generatePhase(current, owner);
            if (afterGenerate.isEmpty()) {
                return store.findRun(claimed.runId()).orElseThrow();
            }
            current = afterGenerate.get();
        }
        if (current.state() == RunState.REPLAYING) {
            return replayCoordinator.run(current, owner);
        }
        throw new IllegalStateException("unexpected claimed state " + current.state());
    }

    private Optional<ClaimedRun> generatePhase(ClaimedRun claimed, String owner) {
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval)) {
            GenerationInput input = store.loadGenerationInput(claimed.runId());
            var executionPolicy = store.loadExecutionPolicy(claimed.runId());
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(beat);
            CandidateGenerationCoordinator.Result result =
                    generationCoordinator.run(input, executionPolicy, session);
            return switch (result) {
                case CandidateGenerationCoordinator.Result.CandidateCommitted committed ->
                        Optional.of(committed.claim());
                case CandidateGenerationCoordinator.Result.RunFailed ignored -> Optional.empty();
            };
        }
    }

}

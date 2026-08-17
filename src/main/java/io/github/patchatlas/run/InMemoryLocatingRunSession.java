package io.github.patchatlas.run;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 默认测试用内存定位会话。 */
public final class InMemoryLocatingRunSession implements LocatingRunSession {

    private ClaimedRun claim;
    private List<LocatingTraceStep> traces = List.of();
    private ContextOrigin origin;
    private List<SourceSnapshot> committedSnapshots = List.of();
    private RunDetails terminal;

    public InMemoryLocatingRunSession(ClaimedRun initial) {
        this.claim = Objects.requireNonNull(initial, "initial");
        if (initial.state() != RunState.LOCATING) {
            throw new IllegalArgumentException("session requires LOCATING claim");
        }
    }

    @Override
    public void replaceTrace(List<LocatingTraceStep> steps) {
        this.traces = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    @Override
    public ClaimedRun commitContext(ContextOrigin origin, List<SourceSnapshot> snapshots) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(snapshots, "snapshots");
        this.origin = origin;
        this.committedSnapshots = List.copyOf(snapshots);
        this.claim = new ClaimedRun(
                claim.runId(),
                claim.mode(),
                RunState.GENERATING,
                claim.version() + 1,
                claim.lease(),
                claim.recoveryCount(),
                claim.replayRound(),
                Optional.empty());
        return claim;
    }

    @Override
    public RunDetails fail(RunFailure failure) {
        Objects.requireNonNull(failure, "failure");
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        this.terminal = new RunDetails(
                claim.runId(),
                claim.mode(),
                RunState.FAILED,
                claim.version() + 1,
                null,
                "https://github.com/ex/repo.git",
                "t",
                "a".repeat(40),
                claim.mode() == VerificationMode.HISTORICAL ? "b".repeat(40) : null,
                Optional.empty(),
                Optional.of(failure),
                Optional.empty(),
                now,
                now,
                now);
        return terminal;
    }

    public List<LocatingTraceStep> traces() {
        return traces;
    }

    public ContextOrigin origin() {
        return origin;
    }

    public List<SourceSnapshot> committedSnapshots() {
        return committedSnapshots;
    }

    public ClaimedRun claim() {
        return claim;
    }
}

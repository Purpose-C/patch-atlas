package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 默认测试用内存定位会话。 */
public final class InMemoryLocatingRunSession implements LocatingRunSession {

    private ClaimedRun claim;
    private final List<LocatingTraceStep> traces = new ArrayList<>();
    private ContextOrigin origin;
    private List<SourceSnapshot> committedSnapshots = List.of();
    private RunDetails terminal;
    private LocatingUsage locatingUsage = LocatingUsage.none();

    public InMemoryLocatingRunSession(ClaimedRun initial) {
        this.claim = Objects.requireNonNull(initial, "initial");
        if (initial.state() != RunState.LOCATING) {
            throw new IllegalArgumentException("session requires LOCATING claim");
        }
    }

    @Override
    public void replaceTrace(List<LocatingTraceStep> steps) {
        beginTrace();
        for (LocatingTraceStep step : Objects.requireNonNull(steps, "steps")) {
            appendTrace(step);
        }
    }

    @Override
    public void beginTrace() {
        traces.clear();
        locatingUsage = LocatingUsage.none();
    }

    @Override
    public void appendTrace(LocatingTraceStep step) {
        traces.add(Objects.requireNonNull(step, "step"));
    }

    @Override
    public void recordUsage(Optional<ModelUsage> usage) {
        Objects.requireNonNull(usage, "usage");
        int calls = locatingUsage.callCount() + 1;
        int recorded = locatingUsage.usageRecordCount() == null ? 0 : locatingUsage.usageRecordCount();
        long in = locatingUsage.inputTokens();
        long out = locatingUsage.outputTokens();
        long total = locatingUsage.totalTokens();
        if (usage.isPresent()) {
            recorded++;
            in += usage.orElseThrow().inputTokens();
            out += usage.orElseThrow().outputTokens();
            total += usage.orElseThrow().totalTokens();
        }
        locatingUsage = new LocatingUsage(calls, recorded, in, out, total);
    }

    @Override
    public ClaimedRun commitContext(ContextOrigin origin, List<SourceSnapshot> snapshots) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("COMMIT_CONTEXT requires a non-empty context");
        }
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
                Optional.empty(),
                claim.completionDiagnostics());
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
        return List.copyOf(traces);
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

    public LocatingUsage locatingUsage() {
        return locatingUsage;
    }
}

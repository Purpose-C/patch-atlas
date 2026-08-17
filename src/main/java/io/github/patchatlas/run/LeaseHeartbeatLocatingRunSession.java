package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 生产定位会话：所有写均在 {@link LeaseHeartbeat} 锁内完成。 */
public final class LeaseHeartbeatLocatingRunSession implements LocatingRunSession {

    private final LeaseHeartbeat heartbeat;

    public LeaseHeartbeatLocatingRunSession(LeaseHeartbeat heartbeat) {
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
    }

    @Override
    public void replaceTrace(List<LocatingTraceStep> steps) {
        heartbeat.replaceLocatingTrace(steps);
    }

    @Override
    public void beginTrace() {
        heartbeat.beginLocatingTrace();
    }

    @Override
    public void appendTrace(LocatingTraceStep step) {
        heartbeat.appendLocatingTrace(step);
    }

    @Override
    public void recordUsage(Optional<ModelUsage> usage) {
        heartbeat.recordLocatingUsage(usage);
    }

    @Override
    public ClaimedRun commitContext(ContextOrigin origin, List<SourceSnapshot> snapshots) {
        return heartbeat.commitContext(origin, snapshots);
    }

    @Override
    public RunDetails fail(RunFailure failure) {
        RunDetails details = heartbeat.fail(failure);
        RunEvents.runFailed(details.runId(), details.mode(), failure);
        return details;
    }
}

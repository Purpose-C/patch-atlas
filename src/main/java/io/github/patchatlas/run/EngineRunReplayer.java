package io.github.patchatlas.run;

import io.github.patchatlas.replay.HistoricalReplayEngine;
import io.github.patchatlas.replay.HistoricalReplayRequest;
import io.github.patchatlas.replay.LiveReplayEngine;
import io.github.patchatlas.replay.LiveReplayRequest;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.util.Objects;

/** 使用共享 {@link SideReplayRunner} 执行 Live/Historical Formal Replay。 */
public final class EngineRunReplayer implements RunReplayer {

    private final LiveReplayEngine liveEngine;
    private final HistoricalReplayEngine historicalEngine;

    public EngineRunReplayer(SideReplayRunner sideReplayRunner) {
        Objects.requireNonNull(sideReplayRunner, "sideReplayRunner");
        this.liveEngine = new LiveReplayEngine(sideReplayRunner);
        this.historicalEngine = new HistoricalReplayEngine(sideReplayRunner);
    }

    @Override
    public ReplayResult replay(
            ClaimedRun claimed,
            PersistedCandidatePatch candidate,
            PreparedReplayWorkspace workspace) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(workspace, "workspace");
        MavenExecutionPolicy policy = workspace.executionPolicy();
        MavenTestCommand command = new MavenTestCommand(
                workspace.modulePath(),
                candidate.targetTest().className() + "#" + candidate.targetTest().methodName(),
                policy.networkMode(),
                policy.javaVersion());
        return switch (workspace) {
            case PreparedReplayWorkspace.Live live -> liveEngine.verify(
                    new LiveReplayRequest(live.workspace(), command, candidate.targetTest()));
            case PreparedReplayWorkspace.Historical historical -> historicalEngine.verify(
                    new HistoricalReplayRequest(
                            historical.buggyWorkspace(),
                            historical.fixedWorkspace(),
                            command,
                            candidate.targetTest()));
        };
    }
}

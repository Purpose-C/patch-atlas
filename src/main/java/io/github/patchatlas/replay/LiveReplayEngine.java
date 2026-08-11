package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Path;
import java.util.Objects;

/** Live Issue 编排：仅在缺陷工作区双跑，裁决最多 {@link ReplayVerdict#REPRODUCTION_CANDIDATE}。 */
public final class LiveReplayEngine {

    private final SideReplayRunner sideRunner;
    private final ReplayVerdictMachine verdictMachine;

    public LiveReplayEngine(SandboxRunner sandboxRunner, Path allowedWorkspaceRoot) {
        this(new SideReplayRunner(sandboxRunner, allowedWorkspaceRoot), new ReplayVerdictMachine());
    }

    LiveReplayEngine(SideReplayRunner sideRunner, ReplayVerdictMachine verdictMachine) {
        this.sideRunner = Objects.requireNonNull(sideRunner, "sideRunner");
        this.verdictMachine = Objects.requireNonNull(verdictMachine, "verdictMachine");
    }

    public ReplayResult verify(LiveReplayRequest request) {
        Objects.requireNonNull(request, "request");
        SideExecutionResult defectSide =
                sideRunner.runSide(request.workspace(), request.command(), request.targetTest());
        ReplayVerdict verdict = verdictMachine.decideLive(defectSide.stableEvidence());
        return ReplayResult.live(verdict, request.targetTest(), defectSide);
    }
}

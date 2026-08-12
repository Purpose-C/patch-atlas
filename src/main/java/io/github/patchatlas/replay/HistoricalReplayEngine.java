package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Historical Verification 编排：先 Buggy 双跑；仅当稳定目标断言失败时再 Fixed 双跑。
 */
public final class HistoricalReplayEngine {

    private final SideReplayRunner sideRunner;
    private final ReplayVerdictMachine verdictMachine;

    public HistoricalReplayEngine(SandboxRunner sandboxRunner, Path allowedWorkspaceRoot) {
        this(new SideReplayRunner(sandboxRunner, allowedWorkspaceRoot), new ReplayVerdictMachine());
    }

    public HistoricalReplayEngine(SideReplayRunner sideRunner) {
        this(sideRunner, new ReplayVerdictMachine());
    }

    HistoricalReplayEngine(SideReplayRunner sideRunner, ReplayVerdictMachine verdictMachine) {
        this.sideRunner = Objects.requireNonNull(sideRunner, "sideRunner");
        this.verdictMachine = Objects.requireNonNull(verdictMachine, "verdictMachine");
    }

    public ReplayResult verify(HistoricalReplayRequest request) {
        Objects.requireNonNull(request, "request");
        // 请求构造已拒同目录；此处再校验一次，防止构造后路径被替换为别名（防御性）
        WorkspaceTrust.requireDistinctWorkspaces(request.buggyWorkspace(), request.fixedWorkspace());

        SideExecutionResult buggySide =
                sideRunner.runSide(request.buggyWorkspace(), request.command(), request.targetTest());

        StableSideEvidence buggyEvidence = buggySide.stableEvidence();
        if (buggyEvidence != StableSideEvidence.TARGET_ASSERTION_FAILURE) {
            ReplayVerdict verdict =
                    verdictMachine.decideHistorical(buggyEvidence, FixedSide.notExecuted());
            String reason =
                    buggyEvidence == StableSideEvidence.TARGET_PASSED
                            ? "buggy side stable target passed; fixed not executed"
                            : "buggy side evidence not stable target assertion failure; fixed not executed";
            return ReplayResult.historicalShortCircuited(
                    verdict, request.targetTest(), buggySide, reason);
        }

        SideExecutionResult fixedSide =
                sideRunner.runSide(request.fixedWorkspace(), request.command(), request.targetTest());
        ReplayVerdict verdict = verdictMachine.decideHistorical(
                buggyEvidence, FixedSide.executed(fixedSide.stableEvidence()));
        return ReplayResult.historicalWithFixed(
                verdict, request.targetTest(), buggySide, fixedSide);
    }
}

package io.github.patchatlas.replay;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Live 或 Historical 验证的完整结果。
 *
 * <p>构造时固化 mode / verdict / Fixed 侧 / 与侧证据一致的不变量。
 */
public record ReplayResult(
        VerificationMode mode,
        ReplayVerdict verdict,
        TargetTest targetTest,
        SideExecutionResult primarySide,
        Optional<SideExecutionResult> fixedSide,
        Optional<String> fixedNotExecutedReason) {

    private static final ReplayVerdictMachine MACHINE = new ReplayVerdictMachine();

    public ReplayResult {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(targetTest, "targetTest");
        Objects.requireNonNull(primarySide, "primarySide");
        fixedSide = Objects.requireNonNull(fixedSide, "fixedSide");
        fixedNotExecutedReason = Objects.requireNonNull(fixedNotExecutedReason, "fixedNotExecutedReason");

        if (fixedSide.isPresent() && fixedNotExecutedReason.isPresent()) {
            throw new IllegalArgumentException(
                    "fixedNotExecutedReason is only for short-circuit when fixed was not run");
        }

        switch (mode) {
            case LIVE -> {
                if (fixedSide.isPresent()) {
                    throw new IllegalArgumentException("Live result cannot include fixed side");
                }
                if (fixedNotExecutedReason.isPresent()) {
                    throw new IllegalArgumentException("Live result cannot include fixedNotExecutedReason");
                }
                if (verdict == ReplayVerdict.VALID_REPRODUCTION
                        || verdict == ReplayVerdict.FAILED_ON_BOTH_COMMITS) {
                    throw new IllegalArgumentException("Live result cannot have verdict " + verdict);
                }
                ReplayVerdict expected = MACHINE.decideLive(primarySide.stableEvidence());
                if (verdict != expected) {
                    throw new IllegalArgumentException(
                            "Live verdict inconsistent with primary side evidence: expected " + expected);
                }
            }
            case HISTORICAL -> {
                if (fixedSide.isEmpty() && fixedNotExecutedReason.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Historical result requires fixed side or short-circuit reason");
                }
                if (verdict == ReplayVerdict.REPRODUCTION_CANDIDATE) {
                    throw new IllegalArgumentException(
                            "Historical result cannot have REPRODUCTION_CANDIDATE");
                }
                if ((verdict == ReplayVerdict.VALID_REPRODUCTION
                                || verdict == ReplayVerdict.FAILED_ON_BOTH_COMMITS)
                        && fixedSide.isEmpty()) {
                    throw new IllegalArgumentException(
                            "verdict " + verdict + " requires executed fixed side");
                }
                FixedSide fixed =
                        fixedSide
                                .map(side -> FixedSide.executed(side.stableEvidence()))
                                .orElseGet(FixedSide::notExecuted);
                // short-circuit 时 primary 不得是 TARGET_ASSERTION_FAILURE（否则应已执行 Fixed）
                if (fixedSide.isEmpty()
                        && primarySide.stableEvidence() == StableSideEvidence.TARGET_ASSERTION_FAILURE) {
                    throw new IllegalArgumentException(
                            "Historical short-circuit cannot have buggy TARGET_ASSERTION_FAILURE");
                }
                ReplayVerdict expected = MACHINE.decideHistorical(primarySide.stableEvidence(), fixed);
                if (verdict != expected) {
                    throw new IllegalArgumentException(
                            "Historical verdict inconsistent with side evidence: expected " + expected);
                }
            }
        }
    }

    public static ReplayResult live(
            ReplayVerdict verdict, TargetTest targetTest, SideExecutionResult defectSide) {
        return new ReplayResult(
                VerificationMode.LIVE,
                verdict,
                targetTest,
                defectSide,
                Optional.empty(),
                Optional.empty());
    }

    public static ReplayResult historicalWithFixed(
            ReplayVerdict verdict,
            TargetTest targetTest,
            SideExecutionResult buggySide,
            SideExecutionResult fixedSide) {
        return new ReplayResult(
                VerificationMode.HISTORICAL,
                verdict,
                targetTest,
                buggySide,
                Optional.of(fixedSide),
                Optional.empty());
    }

    public static ReplayResult historicalShortCircuited(
            ReplayVerdict verdict,
            TargetTest targetTest,
            SideExecutionResult buggySide,
            String fixedNotExecutedReason) {
        return new ReplayResult(
                VerificationMode.HISTORICAL,
                verdict,
                targetTest,
                buggySide,
                Optional.empty(),
                Optional.of(fixedNotExecutedReason));
    }
}

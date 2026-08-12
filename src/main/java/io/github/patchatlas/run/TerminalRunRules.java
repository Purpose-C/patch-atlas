package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayVerdict;

/**
 * 终态字段互斥：COMPLETED 必须有 verdict、不得有 failure；FAILED 相反。
 *
 * <p>{@link ReplayVerdict#INCONCLUSIVE} 属于 COMPLETED，不是 FAILED。
 */
public final class TerminalRunRules {

    private TerminalRunRules() {}

    public static void requireCompleted(ReplayVerdict verdict, RunFailure failure) {
        if (verdict == null) {
            throw new IllegalArgumentException("COMPLETED requires a ReplayVerdict");
        }
        if (failure != null) {
            throw new IllegalArgumentException("COMPLETED must not carry failure fields");
        }
    }

    public static void requireFailed(ReplayVerdict verdict, RunFailure failure) {
        if (verdict != null) {
            throw new IllegalArgumentException("FAILED must not carry a ReplayVerdict");
        }
        if (failure == null) {
            throw new IllegalArgumentException("FAILED requires a RunFailure");
        }
    }
}

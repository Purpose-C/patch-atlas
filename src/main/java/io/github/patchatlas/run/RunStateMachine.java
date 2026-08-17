package io.github.patchatlas.run;

import java.util.Objects;

/**
 * 纯状态机：Verification Run 生命周期迁移。
 *
 * <p>租约过期后的 {@link RunTransition#RECLAIM} 不改变 state（规格：state 不倒退）。
 */
public final class RunStateMachine {

    public boolean canApply(RunState from, RunTransition transition) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(transition, "transition");
        return switch (transition) {
            case CLAIM -> from == RunState.QUEUED;
            case COMMIT_CONTEXT -> from == RunState.LOCATING;
            case COMMIT_CANDIDATE -> from == RunState.GENERATING;
            case COMPLETE -> from == RunState.REPLAYING;
            case FAIL -> from == RunState.LOCATING
                    || from == RunState.GENERATING
                    || from == RunState.REPLAYING;
            case RECLAIM -> from == RunState.LOCATING
                    || from == RunState.GENERATING
                    || from == RunState.REPLAYING;
        };
    }

    public RunState apply(RunState from, RunTransition transition) {
        if (!canApply(from, transition)) {
            throw new IllegalStateException(
                    "illegal transition " + transition + " from " + from);
        }
        return switch (transition) {
            case CLAIM -> RunState.LOCATING;
            case COMMIT_CONTEXT -> RunState.GENERATING;
            case COMMIT_CANDIDATE -> RunState.REPLAYING;
            case COMPLETE -> RunState.COMPLETED;
            case FAIL -> RunState.FAILED;
            case RECLAIM -> from;
        };
    }
}

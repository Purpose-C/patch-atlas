package io.github.patchatlas.run;

/** 合法状态迁移动作（不含数据库细节）。 */
public enum RunTransition {
    /** QUEUED → GENERATING */
    CLAIM,
    /** GENERATING → REPLAYING（与 candidate 落库同事务） */
    COMMIT_CANDIDATE,
    /** REPLAYING → COMPLETED（与 attempts 落库同事务） */
    COMPLETE,
    /** GENERATING / REPLAYING → FAILED */
    FAIL,
    /** GENERATING / REPLAYING 租约过期后被新 owner 接管；state 不变 */
    RECLAIM
}

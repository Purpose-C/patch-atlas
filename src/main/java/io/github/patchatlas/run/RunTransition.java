package io.github.patchatlas.run;

/** 合法状态迁移动作（不含数据库细节）。 */
public enum RunTransition {
    /** QUEUED → LOCATING */
    CLAIM,
    /** LOCATING → GENERATING（上下文已就绪） */
    COMMIT_CONTEXT,
    /** GENERATING → REPLAYING（与 candidate 落库同事务） */
    COMMIT_CANDIDATE,
    /** REPLAYING → COMPLETED（与 attempts 落库同事务） */
    COMPLETE,
    /** LOCATING / GENERATING / REPLAYING → FAILED */
    FAIL,
    /** LOCATING / GENERATING / REPLAYING 租约过期后被新 owner 接管；state 不变 */
    RECLAIM
}

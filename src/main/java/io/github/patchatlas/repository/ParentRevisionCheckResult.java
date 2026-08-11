package io.github.patchatlas.repository;

/**
 * 校验 {@code fixed^ == buggy} 的结构化结果。
 *
 * <p>三类失败可区分,不混成一个 boolean。
 */
public sealed interface ParentRevisionCheckResult {

    record Match(CommitId buggy, CommitId fixed) implements ParentRevisionCheckResult {}

    record InvalidRevision(String which) implements ParentRevisionCheckResult {}

    record RevisionMissing(String which, String revision) implements ParentRevisionCheckResult {}

    record NotCommit(String which, String revision) implements ParentRevisionCheckResult {}

    record ParentMismatch(CommitId expectedBuggy, CommitId actualParent, CommitId fixed)
            implements ParentRevisionCheckResult {}

    /** Fixed 不是恰好一个父提交(根提交或 merge),无法做 {@code fixed^ == buggy} 判定。 */
    record NotSingleParent(CommitId fixed, int parentCount) implements ParentRevisionCheckResult {}

    record RepositoryUnreadable(String reason) implements ParentRevisionCheckResult {}
}

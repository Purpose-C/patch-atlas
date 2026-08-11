package io.github.patchatlas.repository;

/**
 * 校验一个 Revision 的结构化结果。
 *
 * <p>用 sealed interface 而不是 boolean 或异常:调用方需要区分「找到了」「这个 SHA 不存在」
 * 「仓库根本读不了」三种情况,boolean 会丢掉失败原因;而「用户给的 SHA 不存在」是预期内的
 * 正常输入,不是程序缺陷,不该用异常做控制流。
 */
public sealed interface RevisionCheckResult {

    /** Revision 存在,解析出唯一的 commit 指纹。 */
    record Found(CommitId commitId) implements RevisionCheckResult {}

    /** 输入不是完整的 40 位十六进制对象 ID。 */
    record InvalidRevision() implements RevisionCheckResult {}

    /** 格式可能合法,但这个仓库里没有这个 Revision。 */
    record NotFound(String revision) implements RevisionCheckResult {}

    /** 对象存在,但不是 commit（例如 tree 或 blob）。 */
    record NotCommit(String revision) implements RevisionCheckResult {}

    /** 仓库本身打不开或读不了(路径不存在、不是 git 仓库、IO 错误)。 */
    record RepositoryUnreadable(String reason) implements RevisionCheckResult {}
}

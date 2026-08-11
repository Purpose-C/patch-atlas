package io.github.patchatlas.repository;

import java.io.File;
import java.io.IOException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;

/**
 * 校验某个 Revision 在给定的本地 git 仓库中是否真实存在。
 *
 * <p>属于 AD-002 的事实层:结论只来自 git 对象库,可复算、可追溯,不涉及模型判断。
 */
public class RevisionValidator {

    /**
     * 在 {@code repositoryDir} 这个 git 仓库里查找 {@code revision}。
     *
     * @return Found / NotFound / RepositoryUnreadable,永不抛出预期内输入错误
     */
    public RevisionCheckResult check(File repositoryDir, String revision) {
        if (repositoryDir == null) {
            return new RevisionCheckResult.RepositoryUnreadable("repositoryDir is null");
        }
        if (!CommitId.isFullSha(revision)) {
            return new RevisionCheckResult.InvalidRevision();
        }

        try (Git git = Git.open(repositoryDir)) {
            ObjectId objectId = git.getRepository().resolve(revision);
            // resolve() 对 40 位十六进制只做解析,不查对象库;必须再确认对象真实存在。
            if (objectId == null || !git.getRepository().getObjectDatabase().has(objectId)) {
                return new RevisionCheckResult.NotFound(revision);
            }
            if (git.getRepository().open(objectId).getType() != Constants.OBJ_COMMIT) {
                return new RevisionCheckResult.NotCommit(revision);
            }
            return new RevisionCheckResult.Found(new CommitId(objectId.getName()));
        } catch (IOException ex) {
            return new RevisionCheckResult.RepositoryUnreadable(ex.getMessage());
        }
    }
}

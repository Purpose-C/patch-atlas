package io.github.patchatlas.repository;

import java.io.File;
import java.io.IOException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * 校验 Fixed Revision 的第一父提交是否等于 Buggy Revision。
 *
 * <p>这是 Benchmark 机械质量闸门之一(AD-002 事实层)。
 */
public class ParentRevisionValidator {

    public ParentRevisionCheckResult check(File repositoryDir, String buggyRevision, String fixedRevision) {
        if (repositoryDir == null) {
            return new ParentRevisionCheckResult.RepositoryUnreadable("repositoryDir is null");
        }
        if (!CommitId.isFullSha(buggyRevision)) {
            return new ParentRevisionCheckResult.InvalidRevision("buggy");
        }
        if (!CommitId.isFullSha(fixedRevision)) {
            return new ParentRevisionCheckResult.InvalidRevision("fixed");
        }

        try (Git git = Git.open(repositoryDir);
                RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repository = git.getRepository();

            ObjectId buggyId = repository.resolve(buggyRevision);
            if (buggyId == null || !repository.getObjectDatabase().has(buggyId)) {
                return new ParentRevisionCheckResult.RevisionMissing("buggy", buggyRevision);
            }
            if (repository.open(buggyId).getType() != Constants.OBJ_COMMIT) {
                return new ParentRevisionCheckResult.NotCommit("buggy", buggyRevision);
            }

            ObjectId fixedId = repository.resolve(fixedRevision);
            if (fixedId == null || !repository.getObjectDatabase().has(fixedId)) {
                return new ParentRevisionCheckResult.RevisionMissing("fixed", fixedRevision);
            }
            if (repository.open(fixedId).getType() != Constants.OBJ_COMMIT) {
                return new ParentRevisionCheckResult.NotCommit("fixed", fixedRevision);
            }

            RevCommit fixed = walk.parseCommit(fixedId);
            if (fixed.getParentCount() != 1) {
                return new ParentRevisionCheckResult.NotSingleParent(
                        new CommitId(fixedId.getName()), fixed.getParentCount());
            }

            ObjectId parentId = fixed.getParent(0).getId();
            CommitId buggy = new CommitId(buggyId.getName());
            CommitId fixedCommit = new CommitId(fixedId.getName());
            CommitId parent = new CommitId(parentId.getName());

            if (buggyId.equals(parentId)) {
                return new ParentRevisionCheckResult.Match(buggy, fixedCommit);
            }
            return new ParentRevisionCheckResult.ParentMismatch(buggy, parent, fixedCommit);
        } catch (IOException ex) {
            return new ParentRevisionCheckResult.RepositoryUnreadable(ex.getMessage());
        }
    }
}

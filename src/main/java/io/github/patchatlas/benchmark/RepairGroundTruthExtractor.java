package io.github.patchatlas.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

/**
 * 从 buggy..fixed 的文件 diff 提取人类修复地面真值：排除测试源，且只保留 {@code .java}。
 *
 * <p>测试根判定与 {@code PatchGate} 相同：{@code src/test/...}（含模块前缀）。
 * {@code .java} 过滤与 {@code BuggyRepositoryReader} 相同：定位读不到 CHANGELOG / README，
 * 把它们算进分母是与被测能力无关的噪音。
 */
public final class RepairGroundTruthExtractor {

    public sealed interface Result permits Result.Applicable, Result.NotApplicable {
        record Applicable(Set<String> paths) implements Result {
            public Applicable {
                paths = Set.copyOf(Objects.requireNonNull(paths, "paths"));
                if (paths.isEmpty()) {
                    throw new IllegalArgumentException("empty production paths must be NotApplicable");
                }
            }
        }

        record NotApplicable() implements Result {}
    }

    public Result extract(Path repository, String buggyRevision, String fixedRevision, String modulePath) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(modulePath, "modulePath");
        requireSha(buggyRevision, "buggyRevision");
        requireSha(fixedRevision, "fixedRevision");

        Set<String> changed = new LinkedHashSet<>();
        try (Git git = Git.open(repository.toFile())) {
            Repository repo = git.getRepository();
            try (ObjectReader reader = repo.newObjectReader();
                    RevWalk walk = new RevWalk(repo)) {
                ObjectId buggyId = repo.resolve(buggyRevision);
                ObjectId fixedId = repo.resolve(fixedRevision);
                if (buggyId == null || fixedId == null) {
                    throw new IllegalStateException("revision missing");
                }
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, walk.parseCommit(buggyId).getTree());
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, walk.parseCommit(fixedId).getTree());
                List<DiffEntry> diffs = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
                for (DiffEntry entry : diffs) {
                    addPath(changed, entry.getOldPath());
                    addPath(changed, entry.getNewPath());
                }
            }
        } catch (IOException | GitAPIException ex) {
            throw new IllegalStateException("cannot diff buggy and fixed revisions", ex);
        }

        Set<String> production = new TreeSet<>();
        for (String path : changed) {
            if (!isTestSourcePath(path, modulePath) && isJavaSourcePath(path)) {
                production.add(path);
            }
        }
        if (production.isEmpty()) {
            return new Result.NotApplicable();
        }
        return new Result.Applicable(production);
    }

    /**
     * 与 PatchGate 相同的测试根：空模块为 {@code src/test/java}，否则 {@code module/src/test/java}。
     * 其它模块下的 {@code src/test/} 同样排除，避免人类测试进入分母。
     */
    static boolean isTestSourcePath(String path, String modulePath) {
        String testRoot = modulePath.isEmpty() ? "src/test/java" : modulePath + "/src/test/java";
        if (path.equals(testRoot) || path.startsWith(testRoot + "/")) {
            return true;
        }
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    /** 与 {@code BuggyRepositoryReader} 相同：只认 {@code .java} 后缀。 */
    static boolean isJavaSourcePath(String path) {
        return path.endsWith(".java");
    }

    private static void addPath(Set<String> paths, String path) {
        if (path == null || path.isBlank() || DiffEntry.DEV_NULL.equals(path)) {
            return;
        }
        paths.add(path);
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException(name + " must be a 40-char lowercase SHA");
        }
    }
}

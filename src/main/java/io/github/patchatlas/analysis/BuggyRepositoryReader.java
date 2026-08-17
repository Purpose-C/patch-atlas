package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.SourceSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** 从已固定的 Buggy Revision 读取 Java blob；不访问 Fixed Revision 或工作树新增内容。 */
public final class BuggyRepositoryReader {

    public static final int MAX_JAVA_FILES = 200;

    private static final String OVERSIZED_SENTINEL =
            "x".repeat(SourceSnapshot.MAX_CONTENT_BYTES + 1);

    public record JavaFiles(
            List<BuggyOnlyGeneratorContextBuilder.BuggyFile> files, boolean truncated) {
        public JavaFiles {
            files = List.copyOf(files);
        }
    }

    public List<BuggyOnlyGeneratorContextBuilder.BuggyFile> readJavaFiles(
            Path workspace, String expectedRevision) throws IOException {
        return readJavaFiles(workspace, expectedRevision, "", Integer.MAX_VALUE).files();
    }

    public JavaFiles readJavaFiles(
            Path workspace, String expectedRevision, String modulePath, int maxFiles)
            throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(modulePath, "modulePath");
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be positive");
        }
        if (expectedRevision == null || !expectedRevision.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("expectedRevision must be 40 lowercase hex chars");
        }

        List<BuggyOnlyGeneratorContextBuilder.BuggyFile> files = new ArrayList<>();
        boolean truncated = false;
        try (Git git = Git.open(workspace.toFile())) {
            Repository repository = git.getRepository();
            try (RevWalk revisions = new RevWalk(repository);
                    TreeWalk tree = new TreeWalk(repository)) {
                ObjectId head = repository.resolve(Constants.HEAD);
                if (head == null || !expectedRevision.equals(head.name())) {
                    throw new IOException("buggy workspace revision mismatch");
                }
                tree.addTree(revisions.parseCommit(head).getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    String path = tree.getPathString();
                    if (!path.endsWith(".java") || !underModule(path, modulePath)) {
                        continue;
                    }
                    if (files.size() >= maxFiles) {
                        truncated = true;
                        break;
                    }
                    ObjectId blob = tree.getObjectId(0);
                    ObjectLoader loader = repository.open(blob, Constants.OBJ_BLOB);
                    String content = readContent(loader);
                    files.add(new BuggyOnlyGeneratorContextBuilder.BuggyFile(
                            path, blob.name(), content));
                }
            }
        }
        return new JavaFiles(files, truncated);
    }

    static boolean underModule(String path, String modulePath) {
        if (modulePath.isEmpty()) {
            return true;
        }
        return path.equals(modulePath) || path.startsWith(modulePath + "/");
    }

    private static String readContent(ObjectLoader loader) throws IOException {
        if (loader.getSize() > SourceSnapshot.MAX_CONTENT_BYTES) {
            return OVERSIZED_SENTINEL;
        }
        byte[] bytes = loader.getBytes(SourceSnapshot.MAX_CONTENT_BYTES);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            return OVERSIZED_SENTINEL;
        }
    }
}

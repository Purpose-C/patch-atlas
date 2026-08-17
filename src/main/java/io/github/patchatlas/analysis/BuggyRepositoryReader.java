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

    private static final String OVERSIZED_SENTINEL =
            "x".repeat(SourceSnapshot.MAX_CONTENT_BYTES + 1);

    public List<BuggyOnlyGeneratorContextBuilder.BuggyFile> readJavaFiles(
            Path workspace, String expectedRevision) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        if (expectedRevision == null || !expectedRevision.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("expectedRevision must be 40 lowercase hex chars");
        }

        List<BuggyOnlyGeneratorContextBuilder.BuggyFile> files = new ArrayList<>();
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
                if (!path.endsWith(".java")) {
                    continue;
                }
                ObjectId blob = tree.getObjectId(0);
                ObjectLoader loader = repository.open(blob, Constants.OBJ_BLOB);
                String content = readContent(loader);
                files.add(new BuggyOnlyGeneratorContextBuilder.BuggyFile(
                        path, blob.name(), content));
            }
            }
        }
        return List.copyOf(files);
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

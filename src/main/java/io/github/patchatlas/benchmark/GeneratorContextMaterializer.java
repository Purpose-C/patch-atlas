package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import io.github.patchatlas.benchmark.BuggyOnlyGeneratorContextBuilder.BuggyFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从 buggyRevision 重建 Source Snapshot 并逐个比对 contentSha256。
 *
 * <p>这是 Oracle 隔离证明链的一环：只有摘要一致，才能证明送进模型的上下文
 * 就是冻结时记录的那份。此类不引用 Oracle 读取器或其元数据类型。
 */
public final class GeneratorContextMaterializer {

    private final BenchmarkGitWorkspace git;
    private final BuggyRepositoryReader repositoryReader;

    public GeneratorContextMaterializer(
            BenchmarkGitWorkspace git, BuggyRepositoryReader repositoryReader) {
        this.git = Objects.requireNonNull(git, "git");
        this.repositoryReader = Objects.requireNonNull(repositoryReader, "repositoryReader");
    }

    /**
     * 从 buggyRevision 重建 Source Snapshot，逐个比对 contentSha256。
     *
     * @param context 冻结的 generator context 元数据
     * @param repositoryUrl 仓库 URL
     * @param caseId 用作 workspace 目录名
     * @return 与冻结时一致的 Source Snapshot 列表
     * @throws IOException 如果内容摘要不一致或文件不存在
     */
    public List<SourceSnapshot> materialize(
            GeneratorContextMetadata context,
            String repositoryUrl,
            String caseId) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(caseId, "caseId");

        BenchmarkGitWorkspace.CheckoutResult checkout = git.checkout(
                repositoryUrl, context.buggyRevision(), caseId + "-materialize");
        if (!(checkout instanceof BenchmarkGitWorkspace.CheckoutResult.Success success)) {
            throw new IOException("checkout failed for materialization: " + caseId);
        }

        List<BuggyFile> files = repositoryReader.readJavaFiles(
                success.workspace(), context.buggyRevision());
        Map<String, BuggyFile> byPath = files.stream()
                .collect(Collectors.toMap(BuggyFile::relativePath, Function.identity()));

        List<SourceSnapshot> snapshots = new ArrayList<>(context.sources().size());
        for (SourceReference ref : context.sources()) {
            BuggyFile file = byPath.get(ref.path());
            if (file == null) {
                throw new IOException(
                        "source file not found in buggy revision: " + ref.path());
            }
            String actualSha = sha256(file.content());
            if (!actualSha.equals(ref.contentSha256())) {
                throw new IOException(
                        "contentSha256 mismatch for " + ref.path()
                                + ": expected " + ref.contentSha256()
                                + " got " + actualSha);
            }
            if (!file.blobId().equals(ref.gitBlobId())) {
                throw new IOException(
                        "gitBlobId mismatch for " + ref.path()
                                + ": expected " + ref.gitBlobId()
                                + " got " + file.blobId());
            }
            snapshots.add(new SourceSnapshot(ref.path(), file.content()));
        }
        return List.copyOf(snapshots);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

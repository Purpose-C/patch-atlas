package io.github.patchatlas.agent;

import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.WorkspaceTrust;
// CandidateDraft in same package
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Patch Gate：完整解析 → 策略校验 → workspace 安全 → 应用。
 * 任何拒绝发生在第一次写入之前；不暴露 JGit/DOM 类型。
 */
public final class PatchGate {

    private static final Pattern SAFE_CLASS =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern SAFE_METHOD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Path allowedWorkspaceRoot;

    public PatchGate(Path allowedWorkspaceRoot) {
        this.allowedWorkspaceRoot = WorkspaceTrust.normalizeAllowedRoot(allowedWorkspaceRoot);
    }

    public PatchPreparationResult prepare(
            Path workspace,
            String modulePath,
            CandidateDraft candidate,
            MavenNetworkMode networkMode) {
        return prepare(
                workspace,
                modulePath,
                candidate,
                new MavenExecutionPolicy(MavenExecutionPolicy.DEFAULT_JAVA_VERSION, networkMode));
    }

    public PatchPreparationResult prepare(
            Path workspace,
            String modulePath,
            CandidateDraft candidate,
            MavenExecutionPolicy executionPolicy) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(executionPolicy, "executionPolicy");

        final Path trustedWorkspace;
        try {
            trustedWorkspace = WorkspaceTrust.requireUnderAllowedRoot(workspace, allowedWorkspaceRoot);
        } catch (IllegalArgumentException ex) {
            return reject(PatchRejectionCategory.WORKSPACE_UNSAFE, "workspace outside allowed root");
        }

        try {
            validateModulePath(modulePath);
        } catch (IllegalArgumentException ex) {
            return reject(PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "unsafe module path");
        }

        UnifiedDiffParser.ParseOutcome parsed = UnifiedDiffParser.parse(candidate.patchText());
        if (!parsed.isOk()) {
            return reject(parsed.category(), parsed.reason());
        }

        String testRoot = testSourceRoot(modulePath);
        List<String> changedPaths = new ArrayList<>();
        for (ParsedFileDiff file : parsed.files()) {
            PatchPreparationResult pathCheck = validateFilePath(file.path(), testRoot);
            if (pathCheck != null) {
                return pathCheck;
            }
            changedPaths.add(file.path());
        }

        PatchPreparationResult targetCheck =
                validateTargetBinding(candidate.targetTest(), testRoot, changedPaths);
        if (targetCheck != null) {
            return targetCheck;
        }

        // 路径/symlink 校验（写入前）
        for (ParsedFileDiff file : parsed.files()) {
            PatchPreparationResult safety = validateFilesystemSafety(trustedWorkspace, file);
            if (safety != null) {
                return safety;
            }
        }

        // 写入前完成 Maven 命令构造，避免 policy/selector 失败时已修改 workspace
        final MavenTestCommand command;
        try {
            String selector =
                    candidate.targetTest().className() + "#" + candidate.targetTest().methodName();
            command = new MavenTestCommand(
                    modulePath,
                    selector,
                    executionPolicy.networkMode(),
                    executionPolicy.javaVersion());
        } catch (IllegalArgumentException ex) {
            return reject(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH, "unsafe target test selector");
        }

        try {
            for (ParsedFileDiff file : parsed.files()) {
                applyFile(trustedWorkspace, file);
            }
        } catch (IOException ex) {
            return reject(PatchRejectionCategory.APPLICATION_FAILURE, "application failure");
        }

        return new PatchPreparationResult.PreparedCandidate(
                trustedWorkspace, modulePath, candidate.targetTest(), command);
    }

    private static void validateModulePath(String modulePath) {
        // 复用 Maven 白名单：空或安全段
        if (modulePath.length() > 512) {
            throw new IllegalArgumentException("modulePath too long");
        }
        if (modulePath.isEmpty()) {
            return;
        }
        for (String segment : modulePath.split("/", -1)) {
            if (!segment.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("unsafe module segment");
            }
        }
    }

    private static String testSourceRoot(String modulePath) {
        return modulePath.isEmpty() ? "src/test/java" : modulePath + "/src/test/java";
    }

    private static PatchPreparationResult validateFilePath(String path, String testRoot) {
        if (!path.startsWith(testRoot + "/") || !path.endsWith(".java")) {
            return reject(PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "path outside test sources");
        }
        String rest = path.substring(testRoot.length() + 1);
        if (rest.isBlank() || rest.contains("..") || rest.contains("\\")) {
            return reject(PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "unsafe relative path");
        }
        return null;
    }

    private static PatchPreparationResult validateTargetBinding(
            TargetTest target, String testRoot, List<String> changedPaths) {
        if (!SAFE_CLASS.matcher(target.className()).matches() || target.className().contains("$")) {
            return reject(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH, "unsafe target class name");
        }
        if (!SAFE_METHOD.matcher(target.methodName()).matches()) {
            return reject(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH, "unsafe target method name");
        }
        String expected = testRoot + "/" + target.className().replace('.', '/') + ".java";
        if (!changedPaths.contains(expected)) {
            return reject(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH, "target file not in patch");
        }
        return null;
    }

    private static PatchPreparationResult validateFilesystemSafety(Path workspace, ParsedFileDiff file) {
        Path target = workspace.resolve(file.path()).normalize();
        if (!target.startsWith(workspace)) {
            return reject(PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "path escapes workspace");
        }
        // 祖先不得为 symlink
        Path cursor = workspace;
        Path relative = workspace.relativize(target);
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                return reject(PatchRejectionCategory.WORKSPACE_UNSAFE, "symlink in target path");
            }
        }
        if (file.kind() == ParsedFileDiff.Kind.CREATE) {
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                // 允许创建中间目录，但最近已存在父目录必须安全
                Path walk = workspace;
                for (Path part : workspace.relativize(target.getParent() == null ? target : target.getParent())) {
                    Path next = walk.resolve(part);
                    if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                        break;
                    }
                    if (Files.isSymbolicLink(next)) {
                        return reject(PatchRejectionCategory.WORKSPACE_UNSAFE, "symlink parent");
                    }
                    walk = next;
                }
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return reject(PatchRejectionCategory.APPLICATION_FAILURE, "create target already exists");
            }
        } else {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return reject(PatchRejectionCategory.APPLICATION_FAILURE, "modify target missing");
            }
            if (Files.isSymbolicLink(target)) {
                return reject(PatchRejectionCategory.WORKSPACE_UNSAFE, "target is symlink");
            }
        }
        return null;
    }

    private static void applyFile(Path workspace, ParsedFileDiff file) throws IOException {
        Path target = workspace.resolve(file.path()).normalize();
        if (file.kind() == ParsedFileDiff.Kind.CREATE) {
            Files.createDirectories(target.getParent());
            // 新建文件统一 LF + 末尾换行（patch 新增行本身不含 CR）
            String body = file.addedLines().isEmpty()
                    ? ""
                    : String.join("\n", file.addedLines()) + "\n";
            Files.writeString(target, body, StandardCharsets.UTF_8);
            return;
        }

        TextFileContent originalFile = TextFileContent.read(target);
        List<String> result = applyHunks(originalFile.lines(), file.hunks());
        Files.writeString(target, originalFile.serialize(result), StandardCharsets.UTF_8);
    }

    private static List<String> applyHunks(List<String> original, List<ParsedFileDiff.Hunk> hunks)
            throws IOException {
        // 升序应用；newStart 已在解析阶段相对累计插入校验
        List<ParsedFileDiff.Hunk> ordered = new ArrayList<>(hunks);
        ordered.sort((a, b) -> Integer.compare(a.oldStart(), b.oldStart()));
        List<String> current = new ArrayList<>(original);
        long shift = 0;
        for (ParsedFileDiff.Hunk hunk : ordered) {
            int plus = 0;
            for (String line : hunk.lines()) {
                if (line.startsWith("+")) {
                    plus++;
                }
            }
            long mappedOldStart;
            try {
                mappedOldStart = Math.addExact(hunk.oldStart(), shift);
            } catch (ArithmeticException ex) {
                throw new IOException("hunk coordinate overflow");
            }
            if (mappedOldStart > Integer.MAX_VALUE) {
                throw new IOException("hunk coordinate overflow");
            }
            current = applyOneHunk(current, (int) mappedOldStart, hunk);
            try {
                shift = Math.addExact(shift, plus);
            } catch (ArithmeticException ex) {
                throw new IOException("hunk coordinate overflow");
            }
        }
        return current;
    }

    private static List<String> applyOneHunk(
            List<String> original, int mappedOldStart, ParsedFileDiff.Hunk hunk) throws IOException {
        long size = original.size();
        if (hunk.oldCount() == 0) {
            long insertAt = mappedOldStart;
            if (insertAt < 0 || insertAt > size) {
                throw new IOException("insert position out of range");
            }
            List<String> added = new ArrayList<>();
            for (String line : hunk.lines()) {
                if (line.startsWith("+")) {
                    added.add(line.substring(1));
                } else if (line.startsWith(" ")) {
                    throw new IOException("unexpected context in pure insertion");
                }
            }
            int at = (int) insertAt;
            List<String> result = new ArrayList<>(original.size() + added.size());
            result.addAll(original.subList(0, at));
            result.addAll(added);
            result.addAll(original.subList(at, original.size()));
            return result;
        }

        long startIdx = (long) mappedOldStart - 1L;
        long endIdx;
        try {
            endIdx = Math.addExact(startIdx, hunk.oldCount());
        } catch (ArithmeticException ex) {
            throw new IOException("hunk range overflow");
        }
        if (startIdx < 0 || endIdx > size) {
            throw new IOException("hunk context out of range");
        }
        int start = (int) startIdx;
        int ctx = 0;
        for (String line : hunk.lines()) {
            if (line.startsWith(" ")) {
                if (!original.get(start + ctx).equals(line.substring(1))) {
                    throw new IOException("hunk context mismatch");
                }
                ctx++;
            }
        }
        List<String> rebuilt = new ArrayList<>();
        for (String line : hunk.lines()) {
            if (line.startsWith(" ") || line.startsWith("+")) {
                rebuilt.add(line.substring(1));
            }
        }
        List<String> result = new ArrayList<>(original.size() + rebuilt.size());
        result.addAll(original.subList(0, start));
        result.addAll(rebuilt);
        result.addAll(original.subList((int) endIdx, original.size()));
        return result;
    }

    private static PatchPreparationResult reject(PatchRejectionCategory category, String reason) {
        return new PatchPreparationResult.RejectedCandidate(category, reason);
    }
}

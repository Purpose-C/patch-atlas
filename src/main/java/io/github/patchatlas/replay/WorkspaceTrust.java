package io.github.patchatlas.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 可信工作区边界：任何宿主写/删/读报告前，workspace 必须落在配置的 allowed root 下。
 *
 * <p>规则与 {@code DockerSandboxConfig} 对齐：拒绝文件系统根、用户 Home 及其祖先。
 * 生产装配应让 sandbox 与 replay 使用同一 root，避免双份配置漂移。
 */
public final class WorkspaceTrust {

    private WorkspaceTrust() {}

    public static Path normalizeAllowedRoot(Path allowedRoot) {
        Objects.requireNonNull(allowedRoot, "allowedRoot");
        try {
            Path real = allowedRoot.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("allowed workspace root must be a directory");
            }
            if (real.getParent() == null) {
                throw new IllegalArgumentException("allowed workspace root must not be the filesystem root");
            }
            Path userHome = Path.of(System.getProperty("user.home")).toRealPath();
            if (real.equals(userHome) || userHome.startsWith(real)) {
                throw new IllegalArgumentException(
                        "allowed workspace root must not expose the user home or its ancestor");
            }
            return real;
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "allowed workspace root must exist and be resolvable", ex);
        }
    }

    /**
     * 从与 Docker sandbox 相同的配置派生 replay 可信根，避免双根漂移。
     */
    public static Path fromDockerWorkspaceRoot(Path dockerWorkspaceRoot) {
        return normalizeAllowedRoot(dockerWorkspaceRoot);
    }

    /**
     * @return workspace 的 real path
     */
    public static Path requireUnderAllowedRoot(Path workspace, Path allowedRoot) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(allowedRoot, "allowedRoot");
        try {
            if (!Files.isDirectory(workspace)) {
                throw new IllegalArgumentException("workspace must be an existing directory");
            }
            Path realWorkspace = workspace.toRealPath();
            if (!realWorkspace.startsWith(allowedRoot)) {
                throw new IllegalArgumentException("workspace outside allowed root");
            }
            return realWorkspace;
        } catch (IOException ex) {
            throw new IllegalArgumentException("workspace is not resolvable", ex);
        }
    }

    /** 比较两个工作区是否指向同一真实目录（含符号链接别名）。 */
    public static void requireDistinctWorkspaces(Path first, Path second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        try {
            if (!Files.isDirectory(first) || !Files.isDirectory(second)) {
                throw new IllegalArgumentException("both workspaces must be existing directories");
            }
            Path a = first.toRealPath();
            Path b = second.toRealPath();
            if (a.equals(b)) {
                throw new IllegalArgumentException(
                        "buggy and fixed workspaces must be distinct real directories");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("cannot compare workspace real paths", ex);
        }
    }
}

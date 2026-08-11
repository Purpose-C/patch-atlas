package io.github.patchatlas.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 由已验证工作区与白名单 module path 推导 Surefire 报告目录。
 *
 * <p>逐步检查路径分量，拒绝符号链接祖先；若目录已存在，则校验 real path 仍在 workspace 下。
 * 异常信息不包含绝对宿主路径。
 */
public final class SurefireReportsLocation {

    private SurefireReportsLocation() {}

    public static Path resolve(Path workspaceRoot, String modulePath) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(modulePath, "modulePath");

        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("workspace root must not be a symbolic link");
        }

        List<String> segments = new ArrayList<>();
        if (!modulePath.isEmpty()) {
            for (String segment : modulePath.split("/")) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new IllegalArgumentException("modulePath contains unsafe segment");
                }
                segments.add(segment);
            }
        }
        segments.add("target");
        segments.add("surefire-reports");

        Path cursor = root;
        for (String segment : segments) {
            Path next = cursor.resolve(segment).normalize();
            if (!next.startsWith(root)) {
                throw new IllegalArgumentException("surefire reports path escapes workspace");
            }
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(next)) {
                throw new IllegalArgumentException(
                        "surefire path component must not be a symbolic link: " + segment);
            }
            cursor = next;
        }

        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
                Path realReports = cursor.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!realReports.startsWith(realRoot)) {
                    throw new IllegalArgumentException("surefire reports real path escapes workspace");
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException(
                        "unable to resolve real surefire reports path: " + ex.getClass().getSimpleName(),
                        ex);
            }
        }
        return cursor;
    }
}

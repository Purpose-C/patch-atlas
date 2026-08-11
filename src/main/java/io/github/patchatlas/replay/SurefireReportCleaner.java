package io.github.patchatlas.replay;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 安全清理目标模块的 {@code target/surefire-reports}。
 *
 * <p>不跟随符号链接；校验真实路径仍在 workspace 下；失败信息不含绝对宿主路径。
 */
public final class SurefireReportCleaner {

    public SurefireReportCleanup clean(Path workspaceRoot, String modulePath) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(modulePath, "modulePath");

        final Path reportsDir;
        try {
            reportsDir = SurefireReportsLocation.resolve(workspaceRoot, modulePath);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return SurefireReportCleanup.failed(safeMessage(ex));
        }

        if (!Files.exists(reportsDir, LinkOption.NOFOLLOW_LINKS)) {
            return SurefireReportCleanup.succeeded();
        }
        if (Files.isSymbolicLink(reportsDir)) {
            return SurefireReportCleanup.failed("surefire reports directory must not be a symbolic link");
        }
        if (!Files.isDirectory(reportsDir, LinkOption.NOFOLLOW_LINKS)) {
            return SurefireReportCleanup.failed("surefire reports path is not a directory");
        }

        try {
            Path realRoot = workspaceRoot.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realReports = reportsDir.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realReports.startsWith(realRoot)) {
                return SurefireReportCleanup.failed("surefire reports real path escapes workspace");
            }
        } catch (IOException ex) {
            return SurefireReportCleanup.failed(
                    "unable to resolve real paths: " + ex.getClass().getSimpleName());
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry)) {
                    return SurefireReportCleanup.failed(
                            "refusing to delete symbolic link in surefire reports");
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    return SurefireReportCleanup.failed(
                            "refusing to delete nested directory in surefire reports");
                }
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(entry);
                }
            }
        } catch (IOException ex) {
            return SurefireReportCleanup.failed(
                    "failed to clean surefire reports: " + ex.getClass().getSimpleName());
        }
        return SurefireReportCleanup.succeeded();
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        // 已约定 resolve 消息不含绝对路径；再剥一层保险
        return message;
    }
}

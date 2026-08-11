package io.github.patchatlas.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Docker Runner 的可信应用配置，不接收模型或仓库内容。 */
public record DockerSandboxConfig(
        String image,
        Duration timeout,
        int maxOutputBytes,
        Path workspaceRoot,
        Path mavenCacheDirectory,
        SandboxLimits limits) {

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

    public DockerSandboxConfig {
        if (image == null || !image.matches("maven:[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("image must be an allowlisted Maven image tag");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be in (0, 30 minutes]");
        }
        if (maxOutputBytes < 32 || maxOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be between 32 bytes and 1 MiB");
        }
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        try {
            workspaceRoot = workspaceRoot.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("workspaceRoot must be an existing directory", ex);
        }
        if (!Files.isDirectory(workspaceRoot) || workspaceRoot.getParent() == null) {
            throw new IllegalArgumentException("workspaceRoot must be a non-root directory");
        }
        try {
            Path userHome = Path.of(System.getProperty("user.home")).toRealPath();
            if (workspaceRoot.equals(userHome) || userHome.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException(
                        "workspaceRoot must not expose the user home or its ancestor");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("cannot validate workspaceRoot against user home", ex);
        }
        Objects.requireNonNull(mavenCacheDirectory, "mavenCacheDirectory");
        mavenCacheDirectory = mavenCacheDirectory.toAbsolutePath().normalize();
        boolean dedicatedCache = false;
        for (Path segment : mavenCacheDirectory) {
            if (segment.toString().equals(".patch-atlas-cache")) {
                dedicatedCache = true;
                break;
            }
        }
        if (!dedicatedCache) {
            throw new IllegalArgumentException(
                    "mavenCacheDirectory must be inside .patch-atlas-cache");
        }
        Objects.requireNonNull(limits, "limits");
    }

    public static DockerSandboxConfig defaults(Path workspaceRoot, Path mavenCacheDirectory) {
        return new DockerSandboxConfig(
                "maven:3.9-eclipse-temurin-21",
                Duration.ofMinutes(10),
                64 * 1024,
                workspaceRoot,
                mavenCacheDirectory,
                SandboxLimits.defaults());
    }
}

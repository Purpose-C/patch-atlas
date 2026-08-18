package io.github.patchatlas.analysis;

import io.github.patchatlas.replay.WorkspaceTrust;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 受信任的 Buggy 工作区根；所有相对路径解析共用这一份。 */
final class TrustedWorkspace {

    private final Path workspace;

    TrustedWorkspace(Path workspace) {
        Objects.requireNonNull(workspace, "workspace");
        try {
            Path realWorkspace = workspace.toRealPath();
            this.workspace = WorkspaceTrust.requireUnderAllowedRoot(realWorkspace, realWorkspace);
        } catch (IOException ex) {
            throw new IllegalArgumentException("workspace is not resolvable");
        }
    }

    Path path() {
        return workspace;
    }

    Path resolveInside(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path rejected");
        }
        if (relativePath.startsWith("/")
                || relativePath.contains("\\")
                || relativePath.contains("\0")
                || relativePath.contains("..")) {
            throw new IllegalArgumentException("path rejected");
        }
        Path candidate = workspace.resolve(relativePath).normalize();
        if (!candidate.startsWith(workspace)) {
            throw new IllegalArgumentException("path rejected");
        }
        return requireInside(candidate);
    }

    Path requireInside(Path candidate) {
        try {
            if (Files.exists(candidate)) {
                Path real = candidate.toRealPath();
                if (!real.startsWith(workspace)) {
                    throw new IllegalArgumentException("path rejected");
                }
                return real;
            }
            return candidate;
        } catch (IOException ex) {
            throw new IllegalArgumentException("path rejected");
        }
    }
}

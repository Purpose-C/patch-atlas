package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 工作区 read / submit 的唯一实现。 */
public final class WorkspaceFileTools implements WorkspaceTools {

    private final TrustedWorkspace workspace;

    public WorkspaceFileTools(Path workspace) {
        this(new TrustedWorkspace(workspace));
    }

    WorkspaceFileTools(TrustedWorkspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    @Override
    public LocalizationTools.FileSlice read(String path, Integer startLine, Integer span) {
        Path file = workspace.resolveInside(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("path is not a file");
        }
        int start = startLine == null ? 1 : startLine;
        if (start < 1) {
            throw new IllegalArgumentException("startLine must be at least 1");
        }
        if (span != null && span <= 0) {
            throw new IllegalArgumentException("span must be at least 1");
        }
        int maxLines = span == null
                ? LocalizationTools.MAX_READ_LINES
                : Math.min(span, LocalizationTools.MAX_READ_LINES);
        List<String> all;
        try {
            all = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("read failed");
        }
        int totalLines = all.size();
        if (start > totalLines) {
            throw new IllegalArgumentException("startLine exceeds file length");
        }
        int from = start - 1;
        List<String> slice = new ArrayList<>();
        int bytes = 0;
        for (int i = from; i < all.size() && slice.size() < maxLines; i++) {
            String line = all.get(i);
            int next = bytes + line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (next > LocalizationTools.MAX_READ_BYTES) {
                break;
            }
            slice.add(line);
            bytes = next;
        }
        boolean reachedEnd = from + slice.size() >= totalLines;
        Integer nextStartLine = reachedEnd ? null : start + slice.size();
        String relative = workspace.path().relativize(file).toString().replace('\\', '/');
        return new LocalizationTools.FileSlice(relative, start, slice, !reachedEnd, totalLines, nextStartLine);
    }

    @Override
    public LocalizationTools.SubmitDecision validateSubmit(List<String> paths) {
        Objects.requireNonNull(paths, "paths");
        List<String> unique = List.copyOf(new LinkedHashSet<>(paths));
        if (unique.size() > GenerationInput.MAX_SNAPSHOTS) {
            return LocalizationTools.SubmitDecision.reject("at most 12 paths after deduplication");
        }
        List<SourceSnapshot> snapshots = new ArrayList<>();
        int total = 0;
        for (String path : unique) {
            Path file;
            try {
                file = workspace.resolveInside(path);
            } catch (RuntimeException ex) {
                return LocalizationTools.SubmitDecision.reject("path rejected");
            }
            if (!Files.isRegularFile(file)) {
                return LocalizationTools.SubmitDecision.reject("path does not exist: " + path);
            }
            byte[] content;
            try {
                content = Files.readAllBytes(file);
            } catch (IOException ex) {
                return LocalizationTools.SubmitDecision.reject("path does not exist: " + path);
            }
            if (content.length > SourceSnapshot.MAX_CONTENT_BYTES) {
                return LocalizationTools.SubmitDecision.reject("file exceeds 64 KiB: " + path);
            }
            total += content.length;
            if (total > GenerationInput.MAX_TOTAL_SOURCE_BYTES) {
                return LocalizationTools.SubmitDecision.reject("source snapshots exceed 256 KiB total");
            }
            String relative = workspace.path().relativize(file).toString().replace('\\', '/');
            snapshots.add(new SourceSnapshot(relative, new String(content, StandardCharsets.UTF_8)));
        }
        return LocalizationTools.SubmitDecision.accept(snapshots);
    }

    TrustedWorkspace trustedWorkspace() {
        return workspace;
    }
}

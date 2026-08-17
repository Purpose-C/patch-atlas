package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.WorkspaceTrust;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 在单个 Buggy 工作区上做文本搜索 / 列举 / 读取。 */
public final class TextSearchTools implements LocalizationTools {

    private final Path workspace;

    public TextSearchTools(Path workspace) {
        Objects.requireNonNull(workspace, "workspace");
        try {
            Path realWorkspace = workspace.toRealPath();
            this.workspace = WorkspaceTrust.requireUnderAllowedRoot(realWorkspace, realWorkspace);
        } catch (IOException ex) {
            throw new IllegalArgumentException("workspace is not resolvable");
        }
    }

    @Override
    public SearchHits search(String pattern, String pathGlob) {
        Objects.requireNonNull(pattern, "pattern");
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid search pattern");
        }
        PathMatcher matcher = pathGlob == null || pathGlob.isBlank()
                ? path -> true
                : workspace.getFileSystem().getPathMatcher("glob:" + pathGlob);
        List<LocalizationTools.SearchHits.Hit> hits = new ArrayList<>();
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (hits.size() >= MAX_SEARCH_HITS) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isSymbolicLink()) {
                        requireInside(file);
                    }
                    Path relative = workspace.relativize(file);
                    if (!matcher.matches(relative) && !matcher.matches(relative.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    collectHits(file, relative.toString().replace('\\', '/'), compiled, hits);
                    return hits.size() >= MAX_SEARCH_HITS
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("search failed");
        }
        boolean truncated = hits.size() >= MAX_SEARCH_HITS;
        return new SearchHits(
                truncated ? hits.subList(0, MAX_SEARCH_HITS) : hits, truncated);
    }

    @Override
    public DirectoryListing list(String path) {
        Path dir = resolveInside(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("path is not a directory");
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted().forEach(entry -> {
                if (names.size() < MAX_LIST_ENTRIES) {
                    names.add(entry.getFileName().toString());
                }
            });
            boolean truncated = false;
            try (Stream<Path> count = Files.list(dir)) {
                truncated = count.count() > MAX_LIST_ENTRIES;
            }
            return new DirectoryListing(names, truncated);
        } catch (IOException ex) {
            throw new IllegalArgumentException("list failed");
        }
    }

    @Override
    public FileSlice read(String path, Integer startLine, Integer span) {
        Path file = resolveInside(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("path is not a file");
        }
        int start = startLine == null ? 1 : startLine;
        if (start < 1) {
            throw new IllegalArgumentException("startLine must be at least 1");
        }
        int maxLines = span == null ? MAX_READ_LINES : Math.min(span, MAX_READ_LINES);
        if (maxLines < 1) {
            throw new IllegalArgumentException("span must be at least 1");
        }
        List<String> all;
        try {
            all = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("read failed");
        }
        int from = Math.min(start - 1, all.size());
        List<String> slice = new ArrayList<>();
        int bytes = 0;
        boolean truncated = from + maxLines < all.size();
        for (int i = from; i < all.size() && slice.size() < maxLines; i++) {
            String line = all.get(i);
            int next = bytes + line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (next > MAX_READ_BYTES) {
                truncated = true;
                break;
            }
            slice.add(line);
            bytes = next;
        }
        String relative = workspace.relativize(file).toString().replace('\\', '/');
        return new FileSlice(relative, start, slice, truncated);
    }

    @Override
    public SubmitDecision validateSubmit(List<String> paths) {
        Objects.requireNonNull(paths, "paths");
        List<String> unique = List.copyOf(new LinkedHashSet<>(paths));
        if (unique.size() > GenerationInput.MAX_SNAPSHOTS) {
            return SubmitDecision.reject("at most 12 paths after deduplication");
        }
        List<SourceSnapshot> snapshots = new ArrayList<>();
        int total = 0;
        for (String path : unique) {
            Path file;
            try {
                file = resolveInside(path);
            } catch (RuntimeException ex) {
                return SubmitDecision.reject("path rejected");
            }
            if (!Files.isRegularFile(file)) {
                return SubmitDecision.reject("path does not exist: " + path);
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException ex) {
                return SubmitDecision.reject("path does not exist: " + path);
            }
            if (bytes.length > SourceSnapshot.MAX_CONTENT_BYTES) {
                return SubmitDecision.reject("file exceeds 64 KiB: " + path);
            }
            total += bytes.length;
            if (total > GenerationInput.MAX_TOTAL_SOURCE_BYTES) {
                return SubmitDecision.reject("source snapshots exceed 256 KiB total");
            }
            String relative = workspace.relativize(file).toString().replace('\\', '/');
            snapshots.add(new SourceSnapshot(relative, new String(bytes, StandardCharsets.UTF_8)));
        }
        return SubmitDecision.accept(snapshots);
    }

    Path workspace() {
        return workspace;
    }

    private Path resolveInside(String relativePath) {
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

    private Path requireInside(Path candidate) {
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

    private static void collectHits(
            Path file, String relative, Pattern compiled, List<LocalizationTools.SearchHits.Hit> hits) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return;
        }
        for (int i = 0; i < lines.size() && hits.size() < MAX_SEARCH_HITS; i++) {
            Matcher matcher = compiled.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }
            int from = Math.max(0, i - SEARCH_CONTEXT_LINES);
            int to = Math.min(lines.size(), i + SEARCH_CONTEXT_LINES + 1);
            hits.add(new LocalizationTools.SearchHits.Hit(relative, i + 1, lines.subList(from, to)));
        }
    }
}

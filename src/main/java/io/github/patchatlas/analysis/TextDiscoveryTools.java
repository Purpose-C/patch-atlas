package io.github.patchatlas.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 文本发现：search / list。不实现 read 或 submit。 */
public final class TextDiscoveryTools implements DiscoveryTools {

    private final TrustedWorkspace workspace;

    public TextDiscoveryTools(Path workspace) {
        this(new TrustedWorkspace(workspace));
    }

    TextDiscoveryTools(TrustedWorkspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public LocalizationTools.SearchHits search(String pattern, String pathGlob) {
        Objects.requireNonNull(pattern, "pattern");
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid search pattern");
        }
        Path root = workspace.path();
        PathMatcher matcher = pathGlob == null || pathGlob.isBlank()
                ? path -> true
                : root.getFileSystem().getPathMatcher("glob:" + pathGlob);
        List<LocalizationTools.SearchHits.Hit> hits = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (hits.size() >= LocalizationTools.MAX_SEARCH_HITS) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isSymbolicLink()) {
                        workspace.requireInside(file);
                    }
                    Path relative = root.relativize(file);
                    if (!matcher.matches(relative) && !matcher.matches(relative.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    collectHits(file, relative.toString().replace('\\', '/'), compiled, hits);
                    return hits.size() >= LocalizationTools.MAX_SEARCH_HITS
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("search failed");
        }
        boolean truncated = hits.size() >= LocalizationTools.MAX_SEARCH_HITS;
        return new LocalizationTools.SearchHits(
                truncated ? hits.subList(0, LocalizationTools.MAX_SEARCH_HITS) : hits, truncated);
    }

    public LocalizationTools.DirectoryListing list(String path) {
        Path dir = workspace.resolveInside(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("path is not a directory");
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted().forEach(entry -> {
                if (names.size() < LocalizationTools.MAX_LIST_ENTRIES) {
                    names.add(entry.getFileName().toString());
                }
            });
            boolean truncated = false;
            try (Stream<Path> count = Files.list(dir)) {
                truncated = count.count() > LocalizationTools.MAX_LIST_ENTRIES;
            }
            return new LocalizationTools.DirectoryListing(names, truncated);
        } catch (IOException ex) {
            throw new IllegalArgumentException("list failed");
        }
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                ToolDefinition.builder()
                        .name(LocalizationToolCallingManager.SEARCH)
                        .description("Search files")
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},\"pathGlob\":{\"type\":\"string\"}},\"required\":[\"pattern\"]}")
                        .build(),
                ToolDefinition.builder()
                        .name(LocalizationToolCallingManager.LIST)
                        .description("List a directory")
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}")
                        .build());
    }

    @Override
    public String invoke(String name, String args) {
        JsonNode node = args == null || args.isBlank()
                ? JsonMapper.shared().createObjectNode()
                : JsonMapper.shared().readTree(args);
        return switch (name) {
            case LocalizationToolCallingManager.SEARCH -> JsonMapper.shared()
                    .writeValueAsString(search(text(node, "pattern"), text(node, "pathGlob")));
            case LocalizationToolCallingManager.LIST -> JsonMapper.shared()
                    .writeValueAsString(list(text(node, "path")));
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
    }

    private static void collectHits(
            Path file, String relative, Pattern compiled, List<LocalizationTools.SearchHits.Hit> hits) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return;
        }
        for (int i = 0; i < lines.size() && hits.size() < LocalizationTools.MAX_SEARCH_HITS; i++) {
            Matcher matcher = compiled.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }
            int from = Math.max(0, i - LocalizationTools.SEARCH_CONTEXT_LINES);
            int to = Math.min(lines.size(), i + LocalizationTools.SEARCH_CONTEXT_LINES + 1);
            hits.add(new LocalizationTools.SearchHits.Hit(relative, i + 1, lines.subList(from, to)));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}

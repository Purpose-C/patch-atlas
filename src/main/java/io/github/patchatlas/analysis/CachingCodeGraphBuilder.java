package io.github.patchatlas.analysis;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 按 (repositoryUrl, revision, parserVersion, graphSchemaVersion) 缓存代码关系图。
 * 并发时各自写到临时目录再原子重命名，不加锁。
 */
public final class CachingCodeGraphBuilder implements CodeGraphBuilder {

    private static final JsonMapper JSON = JsonMapper.shared();

    private final CodeGraphBuilder inner;
    private final Path cacheRoot;
    private final String repositoryUrl;
    private final String parserVersion;
    private final int schemaVersion;

    public CachingCodeGraphBuilder(
            CodeGraphBuilder inner,
            Path cacheRoot,
            String repositoryUrl,
            String parserVersion,
            int schemaVersion) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.repositoryUrl = Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        this.parserVersion = Objects.requireNonNull(parserVersion, "parserVersion");
        this.schemaVersion = schemaVersion;
    }

    public CachingCodeGraphBuilder(CodeGraphBuilder inner, Path cacheRoot, String repositoryUrl) {
        this(
                inner,
                cacheRoot,
                repositoryUrl,
                JavaParserCodeGraphBuilder.PARSER_VERSION,
                CodeGraph.SCHEMA_VERSION);
    }

    @Override
    public CodeGraph build(Path workspace, String revision) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(revision, "revision");
        Path dest = cacheRoot.resolve(key(revision));
        Path cached = dest.resolve("graph.json");
        if (Files.isRegularFile(cached)) {
            return readGraph(cached);
        }
        CodeGraph graph = inner.build(workspace, revision);
        Path tmp = cacheRoot.resolve("." + dest.getFileName() + "." + UUID.randomUUID());
        try {
            Files.createDirectories(tmp);
            Files.writeString(tmp.resolve("graph.json"), writeGraph(graph), StandardCharsets.UTF_8);
            Files.createDirectories(cacheRoot);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                if (!Files.isRegularFile(cached)) {
                    throw ex;
                }
                deleteRecursively(tmp);
            }
        } catch (IOException ex) {
            deleteRecursively(tmp);
            throw new IllegalStateException("failed to cache code graph", ex);
        }
        if (Files.isRegularFile(cached)) {
            return readGraph(cached);
        }
        return graph;
    }

    public boolean hasCachedGraph(String revision) {
        Objects.requireNonNull(revision, "revision");
        return Files.isRegularFile(cacheRoot.resolve(key(revision)).resolve("graph.json"));
    }

    private String key(String revision) {
        String material = repositoryUrl + '\0' + revision + '\0' + parserVersion + '\0' + schemaVersion;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String writeGraph(CodeGraph graph) {
        ObjectNode root = JSON.createObjectNode();
        root.put("revision", graph.revision());
        ArrayNode nodes = root.putArray("nodes");
        for (Node node : graph.nodes()) {
            nodes.add(writeNode(node));
        }
        ArrayNode edges = root.putArray("edges");
        for (Edge edge : graph.edges()) {
            edges.add(writeEdge(edge));
        }
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode writeNode(Node node) {
        ObjectNode json = JSON.createObjectNode();
        json.put("id", node.id());
        json.put("kind", node.kind().name());
        json.put("name", node.name());
        json.set("location", writeLocation(node.location()));
        return json;
    }

    private static ObjectNode writeEdge(Edge edge) {
        ObjectNode json = JSON.createObjectNode();
        json.put("kind", edge.kind().name());
        json.put("confidence", edge.confidence().name());
        json.set("source", writeNode(edge.source()));
        if (edge.target() == null) {
            json.putNull("target");
        } else {
            json.set("target", writeNode(edge.target()));
        }
        json.set("location", writeLocation(edge.location()));
        if (edge.unresolvedKind() == null) {
            json.putNull("unresolvedKind");
        } else {
            json.put("unresolvedKind", edge.unresolvedKind().name());
        }
        ArrayNode candidates = json.putArray("candidates");
        for (Node candidate : edge.candidates()) {
            candidates.add(writeNode(candidate));
        }
        return json;
    }

    private static ObjectNode writeLocation(SourceLocation location) {
        ObjectNode json = JSON.createObjectNode();
        json.put("relativePath", location.relativePath());
        json.put("line", location.line());
        return json;
    }

    private static CodeGraph readGraph(Path file) {
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            List<Node> nodes = new ArrayList<>();
            for (JsonNode node : root.get("nodes")) {
                nodes.add(readNode(node));
            }
            List<Edge> edges = new ArrayList<>();
            for (JsonNode edge : root.get("edges")) {
                edges.add(readEdge(edge));
            }
            return new CodeGraph(root.get("revision").asString(), nodes, edges);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read cached code graph", ex);
        }
    }

    private static Edge readEdge(JsonNode json) {
        JsonNode unresolved = json.get("unresolvedKind");
        List<Node> candidates = new ArrayList<>();
        JsonNode candidateNodes = json.get("candidates");
        if (candidateNodes != null && candidateNodes.isArray()) {
            for (JsonNode candidate : candidateNodes) {
                candidates.add(readNode(candidate));
            }
        }
        return new Edge(
                EdgeKind.valueOf(json.get("kind").asString()),
                ImpactConfidence.valueOf(json.get("confidence").asString()),
                readNode(json.get("source")),
                json.get("target") == null || json.get("target").isNull()
                        ? null
                        : readNode(json.get("target")),
                readLocation(json.get("location")),
                unresolved == null || unresolved.isNull()
                        ? null
                        : UnresolvedKind.valueOf(unresolved.asString()),
                candidates);
    }

    private static Node readNode(JsonNode json) {
        return new Node(
                json.get("id").asString(),
                NodeKind.valueOf(json.get("kind").asString()),
                json.get("name").asString(),
                readLocation(json.get("location")));
    }

    private static SourceLocation readLocation(JsonNode json) {
        return new SourceLocation(json.get("relativePath").asString(), json.get("line").asInt());
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException("failed to delete " + path, ex);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("failed to delete " + root, ex);
        }
    }
}

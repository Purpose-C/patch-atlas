package io.github.patchatlas.analysis;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 图发现：find / expand。内容扫描复用 {@link TextDiscoveryTools}，对模型不暴露 search。
 */
public final class GraphDiscoveryTools implements DiscoveryTools {

    public static final String FIND = "find";
    public static final String EXPAND = "expand";
    public static final int MAX_HITS = LocalizationTools.MAX_SEARCH_HITS;

    private final CodeGraph graph;
    private final TextDiscoveryTools contentSearch;

    public GraphDiscoveryTools(CodeGraph graph, Path workspace) {
        this(graph, new TextDiscoveryTools(workspace));
    }

    GraphDiscoveryTools(CodeGraph graph, TextDiscoveryTools contentSearch) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.contentSearch = Objects.requireNonNull(contentSearch, "contentSearch");
    }

    public FindHits find(String query) {
        Objects.requireNonNull(query, "query");
        LinkedHashMap<String, EntityRef> found = new LinkedHashMap<>();
        boolean overflow = collectNameMatches(query, found);
        LocalizationTools.SearchHits content = contentSearch.search(query, null);
        overflow |= collectContentMatches(content, found);
        List<EntityRef> entities = List.copyOf(found.values());
        return new FindHits(entities, overflow);
    }

    public ExpandHits expand(
            String entityId,
            List<EdgeKind> edgeKinds,
            ImpactConfidence minConfidence,
            Direction direction) {
        Objects.requireNonNull(entityId, "entityId");
        Direction dir = direction == null ? Direction.BOTH : direction;
        List<Neighbor> neighbors = new ArrayList<>();
        boolean overflow = false;
        for (Edge edge : graph.edges()) {
            if (!kindAllowed(edge.kind(), edgeKinds) || !meets(edge.confidence(), minConfidence)) {
                continue;
            }
            boolean out = matchesOut(edge, entityId, dir);
            boolean in = matchesIn(edge, entityId, dir);
            if (!out && !in) {
                continue;
            }
            if (neighbors.size() >= MAX_HITS) {
                overflow = true;
                break;
            }
            neighbors.add(toNeighbor(edge, out));
        }
        return new ExpandHits(List.copyOf(neighbors), overflow);
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                ToolDefinition.builder()
                        .name(FIND)
                        .description("Find entities by name or file content")
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}")
                        .build(),
                ToolDefinition.builder()
                        .name(EXPAND)
                        .description("Expand graph neighbors of an entity")
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"entity\":{\"type\":\"string\"},\"edgeKinds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"minConfidence\":{\"type\":\"string\"},\"direction\":{\"type\":\"string\"}},\"required\":[\"entity\"]}")
                        .build());
    }

    @Override
    public String invoke(String name, String args) {
        JsonNode node = args == null || args.isBlank()
                ? JsonMapper.shared().createObjectNode()
                : JsonMapper.shared().readTree(args);
        return switch (name) {
            case FIND -> JsonMapper.shared().writeValueAsString(find(text(node, "query")));
            case EXPAND -> JsonMapper.shared()
                    .writeValueAsString(expand(
                            text(node, "entity"),
                            parseEdgeKinds(node.get("edgeKinds")),
                            parseConfidence(text(node, "minConfidence")),
                            parseDirection(text(node, "direction"))));
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
    }

    private boolean collectNameMatches(String query, LinkedHashMap<String, EntityRef> found) {
        String needle = query.toLowerCase(Locale.ROOT);
        boolean overflow = false;
        for (Node node : graph.nodes()) {
            if (node.kind() == NodeKind.FILE) {
                continue;
            }
            if (!node.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (found.size() >= MAX_HITS) {
                overflow = true;
                break;
            }
            found.putIfAbsent(node.id(), toRef(node));
        }
        return overflow;
    }

    private boolean collectContentMatches(
            LocalizationTools.SearchHits content, LinkedHashMap<String, EntityRef> found) {
        boolean overflow = false;
        for (LocalizationTools.SearchHits.Hit hit : content.hits()) {
            Node owner = owningEntity(hit.path(), hit.line());
            if (owner == null) {
                continue;
            }
            if (found.containsKey(owner.id())) {
                continue;
            }
            if (found.size() >= MAX_HITS) {
                overflow = true;
                break;
            }
            found.put(owner.id(), toRef(owner));
        }
        return overflow;
    }

    private Node owningEntity(String relativePath, int line) {
        Node best = null;
        for (Node node : graph.nodes()) {
            if (!node.location().relativePath().equals(relativePath) || node.location().line() > line) {
                continue;
            }
            if (best == null
                    || node.location().line() > best.location().line()
                    || (node.location().line() == best.location().line()
                            && specificity(node.kind()) > specificity(best.kind()))) {
                best = node;
            }
        }
        return best;
    }

    private static int specificity(NodeKind kind) {
        return switch (kind) {
            case METHOD, FIELD -> 3;
            case TYPE -> 2;
            case FILE -> 1;
            default -> 0;
        };
    }

    private static Neighbor toNeighbor(Edge edge, boolean outgoing) {
        Node other = outgoing ? edge.target() : edge.source();
        if (other == null) {
            return new Neighbor(
                    "",
                    "",
                    null,
                    edge.location().relativePath(),
                    edge.location().line(),
                    edge.kind(),
                    edge.confidence(),
                    edge.unresolvedKind());
        }
        return new Neighbor(
                other.id(),
                other.name(),
                other.kind(),
                other.location().relativePath(),
                other.location().line(),
                edge.kind(),
                edge.confidence(),
                edge.unresolvedKind());
    }

    private static EntityRef toRef(Node node) {
        return new EntityRef(
                node.id(),
                node.name(),
                node.kind(),
                node.location().relativePath(),
                node.location().line());
    }

    private static boolean matchesOut(Edge edge, String entityId, Direction direction) {
        return switch (direction) {
            case IN -> false;
            case OUT, BOTH -> entityId.equals(edge.source().id());
            default -> false;
        };
    }

    private static boolean matchesIn(Edge edge, String entityId, Direction direction) {
        return switch (direction) {
            case OUT -> false;
            case IN, BOTH -> edge.target() != null && entityId.equals(edge.target().id());
            default -> false;
        };
    }

    private static boolean kindAllowed(EdgeKind kind, List<EdgeKind> allowed) {
        return allowed == null || allowed.isEmpty() || allowed.contains(kind);
    }

    private static boolean meets(ImpactConfidence actual, ImpactConfidence min) {
        if (min == null) {
            return true;
        }
        return rank(actual) <= rank(min);
    }

    private static int rank(ImpactConfidence confidence) {
        return switch (confidence) {
            case CONFIRMED -> 0;
            case INFERRED -> 1;
            case POSSIBLE -> 2;
            default -> 3;
        };
    }

    private static List<EdgeKind> parseEdgeKinds(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<EdgeKind> kinds = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isString()) {
                kinds.add(EdgeKind.valueOf(item.asString()));
            }
        }
        return List.copyOf(kinds);
    }

    private static ImpactConfidence parseConfidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ImpactConfidence.valueOf(raw);
    }

    private static Direction parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return Direction.BOTH;
        }
        return Direction.valueOf(raw);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    public enum Direction {
        OUT,
        IN,
        BOTH
    }

    public record EntityRef(
            String entityId, String name, NodeKind nodeKind, String relativePath, int line) {
        public EntityRef {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nodeKind, "nodeKind");
            Objects.requireNonNull(relativePath, "relativePath");
        }
    }

    public record Neighbor(
            String entityId,
            String name,
            NodeKind nodeKind,
            String relativePath,
            int line,
            EdgeKind edgeKind,
            ImpactConfidence confidence,
            UnresolvedKind unresolvedKind) {
        public Neighbor {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(edgeKind, "edgeKind");
            Objects.requireNonNull(confidence, "confidence");
        }
    }

    public record FindHits(List<EntityRef> entities, boolean truncated) {
        public FindHits {
            entities = List.copyOf(entities);
        }
    }

    public record ExpandHits(List<Neighbor> neighbors, boolean truncated) {
        public ExpandHits {
            neighbors = List.copyOf(neighbors);
        }
    }
}

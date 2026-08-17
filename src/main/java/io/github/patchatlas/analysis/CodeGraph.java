package io.github.patchatlas.analysis;

import java.util.List;
import java.util.Objects;

/**
 * 某个 Revision 上由源码机械解析得到的代码关系图。
 *
 * <p>{@link #SCHEMA_VERSION} 进入缓存键；边模型或分级规则变化时必须递增。
 */
public record CodeGraph(String revision, List<Node> nodes, List<Edge> edges) {

    public static final int SCHEMA_VERSION = 1;

    public CodeGraph {
        Objects.requireNonNull(revision, "revision");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    public enum NodeKind {
        FILE,
        TYPE,
        METHOD,
        FIELD
    }

    public enum EdgeKind {
        DECLARES,
        EXTENDS,
        IMPLEMENTS,
        CALLS,
        INJECTS,
        PUBLISHES,
        LISTENS,
        ADVISES,
        CONDITIONAL_ON
    }

    public enum UnresolvedKind {
        REFLECTION,
        SPEL,
        PROXY
    }

    public record SourceLocation(String relativePath, int line) {
        public SourceLocation {
            Objects.requireNonNull(relativePath, "relativePath");
            if (line < 1) {
                throw new IllegalArgumentException("line must be positive");
            }
        }
    }

    public record Node(String id, NodeKind kind, String name, SourceLocation location) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * 一条带分级的关系边。{@code target} 与 {@code unresolvedKind} 可为空：
     * 仓外或不可解析的动态调用不得编造目标节点。
     */
    public record Edge(
            EdgeKind kind,
            ImpactConfidence confidence,
            Node source,
            Node target,
            SourceLocation location,
            UnresolvedKind unresolvedKind,
            List<Node> candidates) {
        public Edge {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(location, "location");
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
        }
    }
}

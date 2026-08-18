package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import io.github.patchatlas.analysis.GraphDiscoveryTools.EntityRef;
import io.github.patchatlas.analysis.GraphDiscoveryTools.Neighbor;
import io.github.patchatlas.run.LocatingStepKind;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 图发现：find 双路、expand 邻居；不返回源码，不编造无目标边的终点。 */
class GraphDiscoveryToolsTest {

    private static final Path FIXTURE = Path.of("fixtures/weekend-surcharge");

    @TempDir
    Path temp;

    @Test
    void findMatchesEntityNameCaseInsensitiveSubstring() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("names"));
        Node type = type("BillingService", "src/BillingService.java", 3);
        CodeGraph graph = new CodeGraph("rev", List.of(type), List.of());
        GraphDiscoveryTools tools = new GraphDiscoveryTools(graph, workspace);

        GraphDiscoveryTools.FindHits hits = tools.find("billingservice");

        assertThat(hits.entities()).extracting(EntityRef::entityId).containsExactly("type:BillingService");
        assertThat(hits.entities().getFirst().name()).isEqualTo("BillingService");
        assertThat(hits.truncated()).isFalse();
    }

    @Test
    void findMapsContentHitsToOwningEntityWhenQueryHasNoTypeName() throws Exception {
        Path workspace = FIXTURE.toRealPath();
        CodeGraph graph = new JavaParserCodeGraphBuilder().build(workspace, "fixture");
        GraphDiscoveryTools tools = new GraphDiscoveryTools(graph, workspace);

        GraphDiscoveryTools.FindHits hits = tools.find("weekend surcharge");

        assertThat(hits.entities())
                .extracting(EntityRef::relativePath)
                .anyMatch(path -> path.endsWith("WeekendSurchargePolicy.java"));
        assertThat(hits.entities())
                .extracting(EntityRef::name)
                .noneMatch(name -> name.contains("weekend surcharge"));
    }

    @Test
    void findTruncatesAtFiftyAndMarksTruncated() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("cap"));
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            nodes.add(type("Entity" + i, "src/E" + i + ".java", 1));
        }
        GraphDiscoveryTools tools = new GraphDiscoveryTools(new CodeGraph("rev", nodes, List.of()), workspace);

        GraphDiscoveryTools.FindHits hits = tools.find("Entity");

        assertThat(hits.entities()).hasSize(50);
        assertThat(hits.truncated()).isTrue();
    }

    @Test
    void expandReturnsNeighborRefsWithEdgeKindAndConfidenceAndNoSource() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("expand"));
        Node caller = method("demo.Caller#run", "src/Caller.java", 8);
        Node callee = method("demo.Target#ping", "src/Target.java", 4);
        Edge call = new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.CONFIRMED,
                caller,
                callee,
                new SourceLocation("src/Caller.java", 9),
                null,
                List.of());
        GraphDiscoveryTools tools =
                new GraphDiscoveryTools(new CodeGraph("rev", List.of(caller, callee), List.of(call)), workspace);

        GraphDiscoveryTools.ExpandHits hits = tools.expand(caller.id(), null, null, null);
        String json = tools.invoke(
                GraphDiscoveryTools.EXPAND, "{\"entity\":\"" + caller.id() + "\"}");

        assertThat(hits.neighbors()).hasSize(1);
        Neighbor neighbor = hits.neighbors().getFirst();
        assertThat(neighbor.entityId()).isEqualTo(callee.id());
        assertThat(neighbor.name()).isEqualTo(callee.name());
        assertThat(neighbor.nodeKind()).isEqualTo(NodeKind.METHOD);
        assertThat(neighbor.relativePath()).isEqualTo("src/Target.java");
        assertThat(neighbor.line()).isEqualTo(4);
        assertThat(neighbor.edgeKind()).isEqualTo(EdgeKind.CALLS);
        assertThat(neighbor.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(neighbor.unresolvedKind()).isNull();
        assertThat(json).doesNotContain("public ").doesNotContain("void ping");
        assertThat(Neighbor.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("source", "sourceCode", "lines", "snippet", "body");
    }

    @Test
    void expandMinConfidenceDropsWeakerEdges() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("conf"));
        Node source = type("demo.Client", "src/Client.java", 3);
        Node confirmed = type("demo.A", "src/A.java", 1);
        Node inferred = type("demo.B", "src/B.java", 1);
        Node possible = type("demo.C", "src/C.java", 1);
        List<Edge> edges = List.of(
                inject(source, confirmed, ImpactConfidence.CONFIRMED),
                inject(source, inferred, ImpactConfidence.INFERRED),
                inject(source, possible, ImpactConfidence.POSSIBLE));
        GraphDiscoveryTools tools =
                new GraphDiscoveryTools(new CodeGraph("rev", List.of(source, confirmed, inferred, possible), edges), workspace);

        GraphDiscoveryTools.ExpandHits confirmedOnly =
                tools.expand(source.id(), null, ImpactConfidence.CONFIRMED, GraphDiscoveryTools.Direction.OUT);

        assertThat(confirmedOnly.neighbors())
                .extracting(Neighbor::entityId)
                .containsExactly(confirmed.id());
    }

    @Test
    void expandUnresolvedHoleReturnsEmptyEntityIdAndDoesNotInventTarget() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("hole"));
        Node loader = method("shop.PluginLoader#load", "src/PluginLoader.java", 5);
        Edge hole = new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.POSSIBLE,
                loader,
                null,
                new SourceLocation("src/PluginLoader.java", 6),
                UnresolvedKind.REFLECTION,
                List.of());
        GraphDiscoveryTools tools =
                new GraphDiscoveryTools(new CodeGraph("rev", List.of(loader), List.of(hole)), workspace);

        GraphDiscoveryTools.ExpandHits hits = tools.expand(loader.id(), null, null, GraphDiscoveryTools.Direction.OUT);

        assertThat(hits.neighbors()).hasSize(1);
        Neighbor neighbor = hits.neighbors().getFirst();
        assertThat(neighbor.entityId()).isEmpty();
        assertThat(neighbor.unresolvedKind()).isEqualTo(UnresolvedKind.REFLECTION);
        assertThat(neighbor.relativePath()).isEqualTo("src/PluginLoader.java");
        assertThat(neighbor.line()).isEqualTo(6);
        assertThat(neighbor.edgeKind()).isEqualTo(EdgeKind.CALLS);
        assertThat(neighbor.name()).isEmpty();
        assertThat(neighbor.nodeKind()).isNull();
    }

    @Test
    void eachDiscoverySeamExposesTwoToolsAndGraphHasNoSearch() {
        assertThat(new TextDiscoveryTools(temp).definitions())
                .extracting(ToolDefinition::name)
                .containsExactly(
                        LocalizationToolCallingManager.SEARCH, LocalizationToolCallingManager.LIST);
        GraphDiscoveryTools graph = new GraphDiscoveryTools(new CodeGraph("rev", List.of(), List.of()), temp);
        assertThat(graph.definitions())
                .extracting(ToolDefinition::name)
                .containsExactly(GraphDiscoveryTools.FIND, GraphDiscoveryTools.EXPAND);
        assertThat(graph.definitions()).hasSize(2);
        assertThat(WorkspaceTools.class.isAssignableFrom(GraphDiscoveryTools.class)).isFalse();
        assertThat(LocatingStepKind.FIND).isNotNull();
        assertThat(LocatingStepKind.EXPAND).isNotNull();
    }

    @Test
    void invokeFindAndExpandHonorFiltersDirectionCapAndOwningEntity() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("invoke"));
        Files.writeString(
                workspace.resolve("Caller.java"),
                """
                class Caller {
                    void run() {
                        String marker = "needle";
                    }
                }
                """);
        Node file = new Node(
                "file:Caller.java", NodeKind.FILE, "Caller.java", new SourceLocation("Caller.java", 1));
        Node type = new Node("type:Caller", NodeKind.TYPE, "Caller", new SourceLocation("Caller.java", 1));
        Node method = new Node(
                "method:Caller#run", NodeKind.METHOD, "Caller#run", new SourceLocation("Caller.java", 2));
        Node callee = method("demo.Target#ping", "Target.java", 4);
        Edge call = new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.CONFIRMED,
                method,
                callee,
                new SourceLocation("Caller.java", 3),
                null,
                List.of());
        Edge inferred = inject(type, callee, ImpactConfidence.INFERRED);
        GraphDiscoveryTools tools = new GraphDiscoveryTools(
                new CodeGraph("rev", List.of(file, type, method, callee), List.of(call, inferred)), workspace);

        assertThat(tools.invoke(GraphDiscoveryTools.FIND, "{\"query\":\"Caller\"}"))
                .contains("type:Caller")
                .doesNotContain("file:Caller.java");
        assertThat(tools.find("needle").entities())
                .extracting(EntityRef::entityId)
                .contains("method:Caller#run");
        assertThat(tools.find("Caller").entities())
                .extracting(EntityRef::entityId)
                .contains("type:Caller", "method:Caller#run");

        String incoming = tools.invoke(
                GraphDiscoveryTools.EXPAND,
                "{\"entity\":\""
                        + callee.id()
                        + "\",\"direction\":\"IN\",\"edgeKinds\":[\"CALLS\"],\"minConfidence\":\"CONFIRMED\"}");
        assertThat(incoming).contains("method:Caller#run").doesNotContain("INJECTS");
        assertThat(tools.expand(
                        callee.id(),
                        List.of(EdgeKind.CALLS),
                        ImpactConfidence.CONFIRMED,
                        GraphDiscoveryTools.Direction.IN)
                .neighbors())
                .extracting(Neighbor::entityId)
                .containsExactly(method.id());
        assertThatThrownBy(() -> tools.invoke("search", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tool");
        assertThatThrownBy(() -> tools.invoke(GraphDiscoveryTools.FIND, " "))
                .isInstanceOf(NullPointerException.class);

        Node source = type("demo.Fanout", "Fanout.java", 1);
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        nodes.add(source);
        for (int i = 0; i < 51; i++) {
            Node target = type("demo.N" + i, "N" + i + ".java", 1);
            nodes.add(target);
            edges.add(inject(source, target, ImpactConfidence.CONFIRMED));
        }
        GraphDiscoveryTools capped = new GraphDiscoveryTools(new CodeGraph("rev", nodes, edges), workspace);
        GraphDiscoveryTools.ExpandHits overflow =
                capped.expand(source.id(), null, null, GraphDiscoveryTools.Direction.OUT);
        assertThat(overflow.neighbors()).hasSize(50);
        assertThat(overflow.truncated()).isTrue();
    }

    @Test
    void publicTypesDoNotAcceptFixedRevisionOrOracle() {
        Set<String> offenders = new HashSet<>();
        for (Class<?> type : List.of(GraphDiscoveryTools.class, GraphDiscoveryTools.EntityRef.class, Neighbor.class)) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                collect(method.getReturnType(), offenders);
                for (Class<?> param : method.getParameterTypes()) {
                    collect(param, offenders);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static Node type(String name, String path, int line) {
        return new Node("type:" + name, NodeKind.TYPE, name, new SourceLocation(path, line));
    }

    private static Node method(String name, String path, int line) {
        return new Node("method:" + name, NodeKind.METHOD, name, new SourceLocation(path, line));
    }

    private static Edge inject(Node source, Node target, ImpactConfidence confidence) {
        return new Edge(
                EdgeKind.INJECTS,
                confidence,
                source,
                target,
                source.location(),
                null,
                List.of());
    }

    private static void collect(Class<?> type, Set<String> offenders) {
        String name = type.getName();
        if (name.contains("Oracle")
                || name.contains("Fixed")
                || name.startsWith("io.github.patchatlas.benchmark.")) {
            offenders.add(name);
        }
    }
}

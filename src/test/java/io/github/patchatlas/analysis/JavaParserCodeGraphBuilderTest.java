package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 仅源码内符号表：DECLARES / EXTENDS / IMPLEMENTS / CALLS 及其分级。 */
class JavaParserCodeGraphBuilderTest {

    @TempDir
    Path workspace;

    @Test
    void declaresExtendsAndImplementsAreConfirmed() throws Exception {
        write(
                "src/main/java/demo/Base.java",
                """
                package demo;
                public class Base {}
                """);
        write(
                "src/main/java/demo/Marker.java",
                """
                package demo;
                public interface Marker {}
                """);
        write(
                "src/main/java/demo/Child.java",
                """
                package demo;
                public class Child extends Base implements Marker {}
                """);

        CodeGraph graph = new JavaParserCodeGraphBuilder().build(workspace, "rev-b");

        assertThat(edge(graph, EdgeKind.DECLARES, "src/main/java/demo/Child.java", "demo.Child")
                        .confidence())
                .isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(edge(graph, EdgeKind.EXTENDS, "demo.Child", "demo.Base").confidence())
                .isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(edge(graph, EdgeKind.IMPLEMENTS, "demo.Child", "demo.Marker").confidence())
                .isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(graph.nodes())
                .filteredOn(node -> node.kind() == NodeKind.TYPE)
                .extracting(Node::name)
                .contains("demo.Base", "demo.Marker", "demo.Child");
    }

    @Test
    void inRepoCallsAreConfirmed() throws Exception {
        write(
                "src/main/java/demo/Target.java",
                """
                package demo;
                public class Target {
                    public void ping() {}
                }
                """);
        write(
                "src/main/java/demo/Caller.java",
                """
                package demo;
                public class Caller {
                    private Target target;
                    public void run() {
                        target.ping();
                    }
                }
                """);

        CodeGraph graph = new JavaParserCodeGraphBuilder().build(workspace, "rev-b");

        Edge call = edge(graph, EdgeKind.CALLS, "demo.Caller#run", "demo.Target#ping");
        assertThat(call.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(call.target()).isNotNull();
    }

    @Test
    void interfaceCallWithMultipleImplementationsIsPossibleAndListsCandidates() throws Exception {
        write(
                "src/main/java/demo/Sink.java",
                """
                package demo;
                public interface Sink {
                    void write(String value);
                }
                """);
        write(
                "src/main/java/demo/ASink.java",
                """
                package demo;
                public class ASink implements Sink {
                    public void write(String value) {}
                }
                """);
        write(
                "src/main/java/demo/BSink.java",
                """
                package demo;
                public class BSink implements Sink {
                    public void write(String value) {}
                }
                """);
        write(
                "src/main/java/demo/Publisher.java",
                """
                package demo;
                public class Publisher {
                    private Sink sink;
                    public void pub() {
                        sink.write("x");
                    }
                }
                """);

        CodeGraph graph = new JavaParserCodeGraphBuilder().build(workspace, "rev-b");

        Edge call = edge(graph, EdgeKind.CALLS, "demo.Publisher#pub", "demo.Sink#write");
        assertThat(call.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(call.candidates()).extracting(Node::name)
                .containsExactlyInAnyOrder("demo.ASink#write", "demo.BSink#write");
    }

    @Test
    void externalCallsArePossibleWithoutFabricatingATarget() throws Exception {
        write(
                "src/main/java/demo/Logger.java",
                """
                package demo;
                public class Logger {
                    public String log(int value) {
                        return String.valueOf(value);
                    }
                }
                """);

        CodeGraph graph = new JavaParserCodeGraphBuilder().build(workspace, "rev-b");

        List<Edge> calls = graph.edges().stream()
                .filter(edge -> edge.kind() == EdgeKind.CALLS)
                .filter(edge -> "demo.Logger#log".equals(edge.source().name()))
                .toList();
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(edge -> {
            assertThat(edge.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
            assertThat(edge.target()).isNull();
            assertThat(edge.candidates()).isEmpty();
        });
        assertThat(graph.nodes())
                .extracting(Node::name)
                .doesNotContain("java.lang.String");
    }

    private void write(String relative, String source) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static Edge edge(CodeGraph graph, EdgeKind kind, String sourceName, String targetName) {
        return graph.edges().stream()
                .filter(edge -> edge.kind() == kind)
                .filter(edge -> sourceName.equals(edge.source().name()))
                .filter(edge -> edge.target() != null && targetName.equals(edge.target().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + kind + " " + sourceName + " -> " + targetName
                                + " in " + graph.edges()));
    }
}

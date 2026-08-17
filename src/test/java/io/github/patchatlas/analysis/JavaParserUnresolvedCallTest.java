package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 反射 / SpEL / 动态代理产出无目标端的 POSSIBLE 边，带位置与种类。 */
class JavaParserUnresolvedCallTest {

    @TempDir
    Path workspace;

    @Test
    void reflectionCallIsPossibleWithNoTargetAndReflectionKind() throws Exception {
        write(
                "src/main/java/demo/Reflective.java",
                """
                package demo;
                public class Reflective {
                    public void run() throws Exception {
                        Class<?> type = Class.forName("demo.Hidden");
                        type.getMethod("ping").invoke(null);
                    }
                }
                """);

        Edge edge = unresolved(build(), UnresolvedKind.REFLECTION, "demo.Reflective#run");
        assertThat(edge.kind()).isEqualTo(EdgeKind.CALLS);
        assertThat(edge.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(edge.target()).isNull();
        assertThat(edge.candidates()).isEmpty();
        assertThat(edge.location().relativePath()).isEqualTo("src/main/java/demo/Reflective.java");
        assertThat(edge.location().line()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void spelCallIsPossibleWithNoTargetAndSpelKind() throws Exception {
        write(
                "src/main/java/demo/SpelHolder.java",
                """
                package demo;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.expression.ExpressionParser;
                public class SpelHolder {
                    @Value("#{order.total}")
                    private String total;
                    public Object eval(ExpressionParser parser) {
                        return parser.parseExpression("order.total").getValue();
                    }
                }
                """);

        Edge call = unresolved(build(), UnresolvedKind.SPEL, "demo.SpelHolder#eval");
        assertThat(call.kind()).isEqualTo(EdgeKind.CALLS);
        assertThat(call.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(call.target()).isNull();
        assertThat(call.location().relativePath()).isEqualTo("src/main/java/demo/SpelHolder.java");
        assertThat(call.location().line()).isGreaterThanOrEqualTo(7);

        Edge field = unresolved(build(), UnresolvedKind.SPEL, "demo.SpelHolder#total");
        assertThat(field.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(field.target()).isNull();
        assertThat(field.location().relativePath()).isEqualTo("src/main/java/demo/SpelHolder.java");
    }

    @Test
    void proxyAndTransactionalArePossibleWithNoTargetAndProxyKind() throws Exception {
        write(
                "src/main/java/demo/Store.java",
                """
                package demo;
                import org.springframework.transaction.annotation.Transactional;
                public class Store {
                    @Transactional
                    public void save() {}
                }
                """);
        write(
                "src/main/java/demo/Proxies.java",
                """
                package demo;
                import java.lang.reflect.Proxy;
                public class Proxies {
                    public Object wrap(Object target) {
                        return Proxy.newProxyInstance(
                                target.getClass().getClassLoader(),
                                target.getClass().getInterfaces(),
                                (proxy, method, args) -> null);
                    }
                }
                """);

        CodeGraph graph = build();
        Edge transactional = unresolved(graph, UnresolvedKind.PROXY, "demo.Store#save");
        assertThat(transactional.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(transactional.target()).isNull();
        assertThat(transactional.location().relativePath()).isEqualTo("src/main/java/demo/Store.java");
        assertThat(transactional.location().line()).isGreaterThanOrEqualTo(4);

        Edge proxy = unresolved(graph, UnresolvedKind.PROXY, "demo.Proxies#wrap");
        assertThat(proxy.kind()).isEqualTo(EdgeKind.CALLS);
        assertThat(proxy.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(proxy.target()).isNull();
        assertThat(proxy.location().relativePath()).isEqualTo("src/main/java/demo/Proxies.java");
    }

    private CodeGraph build() {
        return new JavaParserCodeGraphBuilder().build(workspace, "rev-d");
    }

    private void write(String relative, String source) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static Edge unresolved(CodeGraph graph, UnresolvedKind kind, String sourceName) {
        return graph.edges().stream()
                .filter(edge -> kind == edge.unresolvedKind())
                .filter(edge -> sourceName.equals(edge.source().name()))
                .filter(edge -> edge.target() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing unresolved " + kind + " from " + sourceName
                                + " in " + graph.edges()));
    }
}

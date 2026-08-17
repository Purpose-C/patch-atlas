package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spring 语义边按注解简单名与 import 识别，不解析 Spring 类型。 */
class JavaParserSpringGraphTest {

    @TempDir
    Path workspace;

    @Test
    void singleImplementationAutowiredIsConfirmedInject() throws Exception {
        writeServiceAndImpl();
        write(
                "src/main/java/demo/Client.java",
                """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                public class Client {
                    @Autowired
                    private BillingService billingService;
                }
                """);

        Edge inject = edge(build(), EdgeKind.INJECTS, "demo.Client#billingService", "demo.BillingServiceImpl");
        assertThat(inject.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(inject.target()).isNotNull();
    }

    @Test
    void qualifiedMultipleImplementationsRemainPossible() throws Exception {
        write(
                "src/main/java/demo/Notifier.java",
                """
                package demo;
                public interface Notifier {
                    void send(String message);
                }
                """);
        write(
                "src/main/java/demo/MailNotifier.java",
                """
                package demo;
                public class MailNotifier implements Notifier {
                    public void send(String message) {}
                }
                """);
        write(
                "src/main/java/demo/SmsNotifier.java",
                """
                package demo;
                public class SmsNotifier implements Notifier {
                    public void send(String message) {}
                }
                """);
        write(
                "src/main/java/demo/Relay.java",
                """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Qualifier;
                public class Relay {
                    @Autowired
                    @Qualifier("mail")
                    private Notifier notifier;
                }
                """);

        Edge inject = edge(build(), EdgeKind.INJECTS, "demo.Relay#notifier", "demo.Notifier");
        assertThat(inject.confidence()).isEqualTo(ImpactConfidence.POSSIBLE);
        assertThat(inject.candidates()).extracting(Node::name)
                .containsExactlyInAnyOrder("demo.MailNotifier", "demo.SmsNotifier");
    }

    @Test
    void eventListenerPublishAndListenAreInferred() throws Exception {
        write(
                "src/main/java/demo/OrderPaid.java",
                """
                package demo;
                public class OrderPaid {}
                """);
        write(
                "src/main/java/demo/OrderPublisher.java",
                """
                package demo;
                import org.springframework.context.ApplicationEventPublisher;
                public class OrderPublisher {
                    private ApplicationEventPublisher publisher;
                    public void paid() {
                        publisher.publishEvent(new OrderPaid());
                    }
                }
                """);
        write(
                "src/main/java/demo/OrderListener.java",
                """
                package demo;
                import org.springframework.context.event.EventListener;
                public class OrderListener {
                    @EventListener
                    public void onPaid(OrderPaid event) {}
                }
                """);

        CodeGraph graph = build();
        Edge publishes = edge(graph, EdgeKind.PUBLISHES, "demo.OrderPublisher#paid", "demo.OrderPaid");
        Edge listens = edge(graph, EdgeKind.LISTENS, "demo.OrderListener#onPaid", "demo.OrderPaid");
        assertThat(publishes.confidence()).isEqualTo(ImpactConfidence.INFERRED);
        assertThat(listens.confidence()).isEqualTo(ImpactConfidence.INFERRED);
    }

    @Test
    void aspectPointcutAdvisesTargetType() throws Exception {
        write(
                "src/main/java/demo/BillingService.java",
                """
                package demo;
                public class BillingService {
                    public void charge() {}
                }
                """);
        write(
                "src/main/java/demo/BillingAspect.java",
                """
                package demo;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Around;
                @Aspect
                public class BillingAspect {
                    @Around("execution(* demo.BillingService.charge(..))")
                    public Object around(Object joinPoint) { return joinPoint; }
                }
                """);

        Edge advises = edge(build(), EdgeKind.ADVISES, "demo.BillingAspect#around", "demo.BillingService");
        assertThat(advises.confidence()).isEqualTo(ImpactConfidence.INFERRED);
    }

    @Test
    void conditionalOnPropertyIsInferred() throws Exception {
        write(
                "src/main/java/demo/FeatureGate.java",
                """
                package demo;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                @ConditionalOnProperty(name = "demo.feature")
                public class FeatureGate {}
                """);

        Edge conditional = build().edges().stream()
                .filter(edge -> edge.kind() == EdgeKind.CONDITIONAL_ON)
                .filter(edge -> "demo.FeatureGate".equals(edge.source().name()))
                .findFirst()
                .orElseThrow();
        assertThat(conditional.confidence()).isEqualTo(ImpactConfidence.INFERRED);
        assertThat(conditional.target()).isNull();
    }

    @Test
    void springAnnotationEdgesAreRecognizedWithoutResolvingSpringTypes() throws Exception {
        writeServiceAndImpl();
        write(
                "src/main/java/demo/Client.java",
                """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                public class Client {
                    @Autowired
                    private BillingService billingService;
                }
                """);

        assertThat(edge(build(), EdgeKind.INJECTS, "demo.Client#billingService", "demo.BillingServiceImpl")
                        .confidence())
                .isEqualTo(ImpactConfidence.CONFIRMED);

        String source = Files.readString(Path.of(
                "src/main/java/io/github/patchatlas/analysis/JavaParserCodeGraphBuilder.java"));
        assertThat(source).doesNotContain("import org.springframework");
        assertThat(source).doesNotContain("Class.forName");
        assertThat(source).doesNotContain("SymbolSolver");
        assertThat(source).doesNotContain("ReflectionTypeSolver");
        assertThat(source).doesNotContain("CombinedTypeSolver");
    }

    private CodeGraph build() {
        return new JavaParserCodeGraphBuilder().build(workspace, "rev-c");
    }

    private void writeServiceAndImpl() throws Exception {
        write(
                "src/main/java/demo/BillingService.java",
                """
                package demo;
                public interface BillingService {
                    void charge();
                }
                """);
        write(
                "src/main/java/demo/BillingServiceImpl.java",
                """
                package demo;
                public class BillingServiceImpl implements BillingService {
                    public void charge() {}
                }
                """);
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

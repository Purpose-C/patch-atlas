package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.UnresolvedKind;
import io.github.patchatlas.analysis.LocalizationTools.SearchHits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Spring fixture：可构建、三级分级可解析，且缺陷能被文本搜索命中。 */
class WeekendSurchargeFixtureTest {

    private static final Path FIXTURE = Path.of("fixtures/weekend-surcharge");

    @Test
    void mavenBuildAndTestsRun() throws Exception {
        assertThat(Files.isRegularFile(FIXTURE.resolve("pom.xml"))).isTrue();
        Process process = new ProcessBuilder(
                        Path.of("mvnw").toAbsolutePath().toString(),
                        "-o",
                        "-B",
                        "-f",
                        FIXTURE.resolve("pom.xml").toAbsolutePath().toString(),
                        "test")
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    @Test
    void parsedGraphHasConfirmedInferredAndPossibleEdges() {
        CodeGraph graph = new JavaParserCodeGraphBuilder().build(FIXTURE, "fixture");

        assertThat(graph.edges())
                .anyMatch(edge -> edge.confidence() == ImpactConfidence.CONFIRMED);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.confidence() == ImpactConfidence.INFERRED);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.confidence() == ImpactConfidence.POSSIBLE);

        assertThat(graph.edges())
                .anyMatch(edge -> edge.kind() == EdgeKind.CALLS
                        && edge.confidence() == ImpactConfidence.CONFIRMED
                        && edge.target() != null
                        && "shop.WeekendSurchargePolicy#surchargeCents"
                                .equals(edge.target().name()));
        assertThat(graph.edges())
                .anyMatch(edge -> edge.kind() == EdgeKind.INJECTS
                        && edge.confidence() == ImpactConfidence.CONFIRMED);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.kind() == EdgeKind.PUBLISHES
                        && edge.confidence() == ImpactConfidence.INFERRED);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.kind() == EdgeKind.LISTENS
                        && edge.confidence() == ImpactConfidence.INFERRED);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.kind() == EdgeKind.CONDITIONAL_ON
                        && edge.confidence() == ImpactConfidence.INFERRED);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.kind() == EdgeKind.INJECTS
                        && edge.confidence() == ImpactConfidence.POSSIBLE
                        && edge.candidates().size() > 1);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.unresolvedKind() == UnresolvedKind.REFLECTION
                        && edge.target() == null);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.unresolvedKind() == UnresolvedKind.PROXY
                        && edge.target() == null);
    }

    @Test
    void textSearchFindsWeekendSurchargeFromIssueTerms() throws Exception {
        String issue = Files.readString(FIXTURE.resolve("ISSUE.md"));
        assertThat(issue).contains("weekend surcharge").contains("Sunday");

        SearchHits hits = new TextSearchTools(FIXTURE.toRealPath())
                .search("weekend surcharge", "*.java");
        assertThat(hits.hits())
                .extracting(SearchHits.Hit::path)
                .anyMatch(path -> path.endsWith("WeekendSurchargePolicy.java"));
    }
}

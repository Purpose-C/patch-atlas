package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpringCaseStudyReportTest {

    private static final Path DIR = Path.of("benchmark-cases/spring-case-study");
    private static final String PROTOCOL_SHA =
            "e272e17f8cbcd885d85754a0d63942503adff68bb758bbd11db092ad680cca03";
    private static final String CANDIDATES_SHA =
            "f517451e0267450909335a343ddfb0fd5befe08e55c30299798a853f89c1335f";

    @Test
    void reportIsACaseStudyWithExpandChapterAndFrozenProtocol() throws Exception {
        String report = Files.readString(DIR.resolve("evidence-report.md"));
        JsonNode results = JsonMapper.shared().readTree(DIR.resolve("results.json").toFile());
        JsonNode protocol = JsonMapper.shared().readTree(DIR.resolve("protocol.json").toFile());

        assertThat(sha256(DIR.resolve("protocol.json"))).isEqualTo(PROTOCOL_SHA);
        assertThat(sha256(DIR.resolve("candidates.json"))).isEqualTo(CANDIDATES_SHA);
        assertThat(results.path("protocolSha256").stringValue()).isEqualTo(PROTOCOL_SHA);
        assertThat(results.path("candidatesSha256").stringValue()).isEqualTo(CANDIDATES_SHA);

        assertThat(protocol.path("registeredBeforeRuns").booleanValue()).isTrue();
        assertThat(protocol.path("evidenceAsymmetry").path("expandUnused").path("strength").stringValue())
                .isEqualTo("strong");
        assertThat(protocol.path("evidenceAsymmetry").path("expandUsed").path("strength").stringValue())
                .isEqualTo("weak");

        assertThat(results.path("n").intValue()).isEqualTo(1);
        assertThat(results.path("statisticalInference").stringValue()).isEqualTo("not-supported");
        assertThat(results.path("armComparison").stringValue()).isEqualTo("not-permitted");
        assertThat(results.path("evidenceAsymmetryApplied").stringValue()).isEqualTo("expandUnused");
        assertThat(results.path("coverageNotWrittenToLocatingTrace").booleanValue()).isTrue();
        assertThat(results.path("expand").path("callCount").intValue()).isEqualTo(0);
        assertThat(results.path("expand").path("used").booleanValue()).isFalse();
        assertThat(results.path("expand").path("entities")).isEmpty();
        assertThat(results.path("expand").path("edgeTypes")).isEmpty();
        assertThat(results.path("expand").path("enteredSubmit").isNull()).isTrue();
        assertThat(results.path("reruns")).isEmpty();
        assertThat(results.path("expand").path("findQueries")).hasSize(3);

        JsonNode graph = results.path("arms").get(2);
        assertThat(graph.path("origin").stringValue()).isEqualTo("GRAPH_TOOLS");
        assertThat(graph.path("locatingTrace").path("EXPAND").intValue()).isEqualTo(0);
        assertThat(graph.path("locatingTrace").path("FIND").intValue()).isEqualTo(3);
        assertThat(graph.path("locatingTrace").path("READ").intValue()).isEqualTo(8);
        assertThat(graph.path("selectedPaths").toPrettyString()).contains("PersistentAccountancy.java");

        assertThat(report).contains("n=1，案例研究，不支持统计结论，不得用于臂间比较");
        assertThat(report).contains("原因不再是队列性质");
        assertThat(report).contains("调用次数为 0");
        assertThat(report).contains("find");
        assertThat(report).contains("read");
        assertThat(report).contains("PersistentAccountancy.java");
        assertThat(report).contains("不适用：没有走过任何 expand 边");
        assertThat(report).contains("强");
        assertThat(report).contains("locating model tokens: unknown");
        assertThat(report).doesNotContain("locating model tokens: 0");
        assertThat(report).doesNotContain("综合得分");
        assertThat(report).doesNotContain("综合分");
        assertThat(report).doesNotContain("优于");
        assertThat(report).doesNotContain("更好");
        assertThat(report).doesNotContain("更差");
        assertThat(report).doesNotContain("均值");
        assertThat(report).doesNotContain("显著");
        assertThat(report).doesNotContain("没有可供遍历的边");
        assertThat(report).doesNotContain("VALID_REPRODUCTION 是成功标准");
        assertThat(report).doesNotContain("/Users/");
        assertThat(report).doesNotContain("Task 0");
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest);
    }
}

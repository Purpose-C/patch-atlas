package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpringSourceGateArtifactTest {

    private static final Path SCAN = Path.of("benchmark-cases/spring-source-gate/scan.json");
    private static final Path PRIOR_AUDIT = Path.of("benchmark-cases/task018/selection-audit.json");

    @Test
    void gatePassesOnDependencyGroupIdsWithoutReadingPatchesOrNames() throws Exception {
        JsonNode scan = JsonMapper.shared().readTree(SCAN.toFile());
        JsonNode prior = JsonMapper.shared().readTree(PRIOR_AUDIT.toFile());

        assertThat(strings(scan.get("inputsInspected"))).containsExactly("pom.xml");
        assertThat(strings(scan.get("inputsNotInspected")))
                .contains("test_patch", "fix_patch", "repository name");
        assertThat(scan.path("gateThreshold").intValue()).isEqualTo(12);
        assertThat(scan.path("unionSpringPresentCount").intValue()).isGreaterThanOrEqualTo(12);
        assertThat(scan.path("gatePassed").booleanValue()).isTrue();

        Set<String> priorIds = new HashSet<>();
        prior.path("staticExclusions").forEach(node -> priorIds.add(node.path("caseId").stringValue()));
        prior.path("probes").forEach(node -> priorIds.add(node.path("caseId").stringValue()));
        assertThat(priorIds).hasSize(194);

        JsonNode gitbug = source(scan, "gitbug-java");
        Set<String> gitbugIds = new HashSet<>();
        gitbug.path("cases").forEach(node -> gitbugIds.add(node.path("caseId").stringValue()));
        assertThat(gitbugIds).containsAll(priorIds);
        assertThat(gitbug.path("springPresentCount").intValue()).isEqualTo(11);
        assertThat(gitbug.path("nameContainsSpringCount").intValue()).isEqualTo(3);
        assertThat(gitbug.path("springPresentCount").intValue())
                .isGreaterThan(gitbug.path("nameContainsSpringCount").intValue());

        scan.path("unionSpringPresent").forEach(node -> {
            assertThat(node.has("test_patch")).isFalse();
            assertThat(node.has("fix_patch")).isFalse();
            assertThat(node.has("patch")).isFalse();
            node.path("matchingGroupIds").forEach(groupId ->
                    assertThat(groupId.stringValue()).contains(SpringDependencyPresence.SPRING_GROUP_MARKER));
            assertThat(node.path("springPresent").booleanValue()).isTrue();
            assertThat(node.path("caseId").stringValue()).doesNotContain("spring-cloud-openfeign");
        });

        JsonNode tdd = source(scan, "tdd-bench-java");
        assertThat(tdd.path("separatelyScanned").booleanValue()).isFalse();
        assertThat(tdd.path("uniqueInstanceCount").intValue()).isZero();
        assertThat(tdd.path("overlapWithMultiSweCount").intValue()
                        + tdd.path("overlapWithPolyBenchCount").intValue())
                .isGreaterThanOrEqualTo(tdd.path("candidateCount").intValue());
    }

    private static JsonNode source(JsonNode scan, String name) {
        for (JsonNode source : scan.path("sources")) {
            if (name.equals(source.path("name").stringValue())) {
                return source;
            }
        }
        throw new AssertionError("missing source " + name);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.stringValue()));
        return values;
    }
}

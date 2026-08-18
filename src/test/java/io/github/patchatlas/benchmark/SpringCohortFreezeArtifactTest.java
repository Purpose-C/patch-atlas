package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpringCohortFreezeArtifactTest {

    private static final Path AUDIT = Path.of("benchmark-cases/spring-v1/selection-audit.json");
    private static final Path COHORT = Path.of("benchmark-cases/spring-v1/cohort.json");
    private static final Path SCAN = Path.of("benchmark-cases/spring-source-gate/scan.json");

    @Test
    void freezeStoppedBelowMinimumWithoutRewritingThePriorCohort() throws Exception {
        assertThat(Files.exists(COHORT)).isFalse();
        JsonNode audit = JsonMapper.shared().readTree(AUDIT.toFile());
        JsonNode scan = JsonMapper.shared().readTree(SCAN.toFile());

        assertThat(audit.path("selectorVersion").stringValue())
                .isEqualTo(SpringCohortFreezeRules.SELECTOR_VERSION);
        assertThat(audit.path("datasetRevision").stringValue())
                .isEqualTo(SpringCohortFreezeRules.RANKING_REVISION);
        assertThat(audit.path("seed").stringValue()).isEqualTo(SpringCohortFreezeRules.SEED);
        assertThat(audit.path("maxDynamicProbes").intValue())
                .isEqualTo(SpringCohortFreezeRules.MAX_DYNAMIC_PROBES);

        List<String> unionIds = new ArrayList<>();
        scan.path("unionSpringPresent").forEach(node -> unionIds.add(node.path("caseId").stringValue()));
        Set<String> seen = new HashSet<>();
        audit.path("staticExclusions").forEach(node -> seen.add(node.path("caseId").stringValue()));
        audit.path("probes").forEach(node -> seen.add(node.path("caseId").stringValue()));
        assertThat(seen).containsExactlyInAnyOrderElementsOf(unionIds);
        assertThat(audit.path("staticExclusions")).hasSize(92);
        assertThat(audit.path("probes")).hasSize(1);

        long issueUnavailable = countCode(audit, "ISSUE_UNAVAILABLE");
        long metadataInvalid = countCode(audit, "METADATA_INVALID");
        assertThat(issueUnavailable).isEqualTo(10);
        assertThat(metadataInvalid).isEqualTo(82);

        JsonNode probe = audit.path("probes").get(0);
        assertThat(probe.path("caseId").stringValue())
                .isEqualTo("st-tu-dresden-salespoint-85a764f892aa");
        assertThat(probe.path("result").stringValue()).isEqualTo("ELIGIBLE");
        List<String> stages = new ArrayList<>();
        probe.path("stages").forEach(stage -> stages.add(stage.path("stage").stringValue()));
        assertThat(stages).contains("parent_revision");
        probe.path("stages").forEach(stage ->
                assertThat(stage.path("result").stringValue()).isEqualTo("PASSED"));
        assertThat(audit.toPrettyString()).doesNotContain("diff --git");
        assertThat(probe.path("caseId").stringValue()).doesNotContain("spring-cloud-openfeign");
        String readme = Files.readString(Path.of("benchmark-cases/spring-v1/README.md"));
        assertThat(readme).contains("描述性统计（未进入过滤器）");
        assertThat(readme).contains("org.springframework.boot");
        assertThat(readme).contains("不**据此增删成员");
    }

    private static long countCode(JsonNode audit, String code) {
        long count = 0;
        for (JsonNode node : audit.path("staticExclusions")) {
            if (code.equals(node.path("code").stringValue())) {
                count++;
            }
        }
        return count;
    }
}

package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpringCohortFreezeRulesTest {

    @Test
    void rankingRevisionIsTheTruncatedSha256OfDeclaredSourceInstanceIdHashes() throws Exception {
        JsonNode scan = JsonMapper.shared().readTree(Path.of(SpringCohortFreezeRules.SCAN_PATH).toFile());
        String gitbug = instanceIdsSha256(scan, "gitbug-java");
        String multiSwe = instanceIdsSha256(scan, "multi-swe-bench-java");
        String polybench = instanceIdsSha256(scan, "swe-polybench-java");

        assertThat(SpringCohortFreezeRules.rankingRevision(gitbug, multiSwe, polybench))
                .isEqualTo(SpringCohortFreezeRules.RANKING_REVISION)
                .isEqualTo("12d857dbb984911dcfbb40a89a6e246b6a13cdbd");
        assertThat(SpringCohortFreezeRules.RANKING_REVISION)
                .isNotEqualTo(BenchmarkArtifacts.DATASET_REVISION);
        assertThat(SpringCohortFreezeRules.SELECTOR_VERSION)
                .isEqualTo("spring-v1")
                .isNotEqualTo(BenchmarkArtifacts.SELECTOR_VERSION);
        assertThat(SpringCohortFreezeRules.SEED).isEqualTo(BenchmarkArtifacts.SEED);
        assertThat(SpringCohortFreezeRules.MAX_DYNAMIC_PROBES).isEqualTo(24);
        assertThat(SpringCohortFreezeRules.TARGET_SIZE).isEqualTo(6);
        assertThat(SpringCohortFreezeRules.MIN_SIZE).isEqualTo(4);
        assertThat(SpringCohortFreezeRules.RULES)
                .contains("selector=spring-v1")
                .contains("require_spring=true")
                .contains("diagnostic_denylist=spring-cloud-openfeign,scof-1326")
                .contains("ignore=parent_plugin_coordinates,repository_name,issue_text,test_patch,fix_patch");
    }

    @Test
    void interpretationTableIsPreRegisteredBeforeFreeze() throws Exception {
        String text = Files.readString(Path.of("docs/benchmark-graph-guidance-interpretation.md"));
        assertThat(text).contains("`expand` 仍然 0 次");
        assertThat(text).contains("沿边遍历不是模型会主动使用的能力");
        assertThat(text).contains("支持图引导");
        assertThat(text).contains("有价值的负面结果");
        assertThat(text).contains("合格冻结成员不足 4 例");
        assertThat(text).contains("不为凑数放宽闸门");
        assertThat(text).contains("不按这次修复是否需要遍历");
        assertThat(text).doesNotContain("修复是否跨越");
    }

    @Test
    void diagnosticDenylistMatchesOpenfeignCaseIdsOnly() {
        assertThat(SpringCohortFreezeRules.isDiagnosticCase("scof-1326")).isTrue();
        assertThat(SpringCohortFreezeRules.isDiagnosticCase("org.spring-cloud-openfeign-1326")).isTrue();
        assertThat(SpringCohortFreezeRules.isDiagnosticCase("spring-projects-spring-retry-1")).isFalse();
        assertThat(SpringCohortFreezeRules.isDiagnosticCase("")).isFalse();
        assertThat(SpringCohortFreezeRules.isDiagnosticCase(null)).isFalse();
    }

    @Test
    void artifactRecordsAcceptEitherDeclaredProbeLimit() {
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        new BenchmarkArtifacts.ProbeAudit(
                SpringCohortFreezeRules.MAX_DYNAMIC_PROBES,
                "case",
                now,
                now,
                "ELIGIBLE",
                List.of());
        new BenchmarkArtifacts.SelectionAudit(
                SpringCohortFreezeRules.RANKING_REVISION,
                SpringCohortFreezeRules.SEED,
                SpringCohortFreezeRules.SELECTOR_VERSION,
                SpringCohortFreezeRules.MAX_DYNAMIC_PROBES,
                List.of(),
                List.of());
        assertThatThrownBy(() -> new BenchmarkArtifacts.ProbeAudit(
                        SpringCohortFreezeRules.MAX_DYNAMIC_PROBES + 1,
                        "case",
                        now,
                        now,
                        "ELIGIBLE",
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probePosition");
        assertThatThrownBy(() -> new BenchmarkArtifacts.SelectionAudit(
                        SpringCohortFreezeRules.RANKING_REVISION,
                        SpringCohortFreezeRules.SEED,
                        SpringCohortFreezeRules.SELECTOR_VERSION,
                        23,
                        List.of(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probe limit");
    }

    private static String instanceIdsSha256(JsonNode scan, String name) {
        for (JsonNode source : scan.path("sources")) {
            if (name.equals(source.path("name").stringValue())) {
                return source.path("instanceIdsSha256").stringValue();
            }
        }
        throw new AssertionError("missing source " + name);
    }
}

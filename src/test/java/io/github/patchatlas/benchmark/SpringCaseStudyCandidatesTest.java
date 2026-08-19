package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpringCaseStudyCandidatesTest {

    private static final Path CANDIDATES =
            Path.of("benchmark-cases/spring-case-study/candidates.json");
    private static final Path README = Path.of("benchmark-cases/spring-case-study/candidates.md");
    private static final Path SCAN = Path.of("benchmark-cases/spring-source-gate/scan.json");
    private static final Path AUDIT = Path.of("benchmark-cases/spring-v1/selection-audit.json");

    @Test
    void mechanicalListHasOneEligibleCaseAndNoSelection() throws Exception {
        JsonNode candidates = JsonMapper.shared().readTree(CANDIDATES.toFile());
        JsonNode scan = JsonMapper.shared().readTree(SCAN.toFile());
        JsonNode audit = JsonMapper.shared().readTree(AUDIT.toFile());
        String markdown = Files.readString(README);

        assertThat(candidates.path("confirmationStatus").stringValue())
                .isEqualTo("PENDING_OPERATOR_CONFIRMATION");
        assertThat(candidates.path("selectedCaseId").isNull()).isTrue();
        assertThat(candidates.path("summary").path("sourceGateEligibleCount").intValue())
                .isEqualTo(1);
        assertThat(ids(candidates.path("summary").path("eligibleCaseIds")))
                .containsExactly("st-tu-dresden-salespoint-85a764f892aa");
        assertThat(candidates.path("eligible")).hasSize(1);

        List<String> unionIds = ids(scan.path("unionSpringPresent"));
        assertThat(unionIds).hasSize(93);
        List<String> listedIds = ids(candidates.path("sourceGateCases"));
        assertThat(listedIds).containsExactlyElementsOf(unionIds);

        Map<String, String> freezeCodes = new HashMap<>();
        audit.path("staticExclusions")
                .forEach(node -> freezeCodes.put(
                        node.path("caseId").stringValue(), node.path("code").stringValue()));
        Set<String> probed = new HashSet<>();
        audit.path("probes").forEach(node -> probed.add(node.path("caseId").stringValue()));

        int failedC2 = 0;
        int failedC3 = 0;
        for (JsonNode row : candidates.path("sourceGateCases")) {
            assertThat(row.path("C4").stringValue()).isEqualTo("PASS");
            String caseId = row.path("caseId").stringValue();
            if (probed.contains(caseId)) {
                assertThat(row.path("verdict").stringValue()).isEqualTo("ELIGIBLE");
                assertThat(row.path("failedCondition").isNull()).isTrue();
                assertThat(row.path("C1").stringValue()).isEqualTo("PASS");
                assertThat(row.path("C2").stringValue()).isEqualTo("PASS");
                assertThat(row.path("C3").stringValue()).isEqualTo("PASS");
                assertThat(row.path("C5").stringValue()).isEqualTo("PASS");
                continue;
            }
            String code = freezeCodes.get(caseId);
            assertThat(row.path("verdict").stringValue()).isEqualTo("INELIGIBLE");
            if ("METADATA_INVALID".equals(code)) {
                assertThat(row.path("failedCondition").stringValue()).isEqualTo("C2");
                failedC2++;
            } else {
                assertThat(code).isEqualTo("ISSUE_UNAVAILABLE");
                assertThat(row.path("failedCondition").stringValue()).isEqualTo("C3");
                failedC3++;
            }
        }
        assertThat(failedC2).isEqualTo(82);
        assertThat(failedC3).isEqualTo(10);

        JsonNode extra = candidates.path("additionalInventory").get(0);
        assertThat(extra.path("caseId").stringValue()).isEqualTo("scof-1326");
        assertThat(extra.path("verdict").stringValue()).isEqualTo("INELIGIBLE");
        assertThat(extra.path("failedCondition").stringValue()).isEqualTo("C1");

        JsonNode evidence = candidates.path("eligible").get(0).path("evidence");
        assertThat(evidence.path("C4").toPrettyString())
                .contains("org.springframework.boot")
                .contains("org.springframework.experimental");
        assertThat(evidence.path("C5").path("productionJavaPaths").get(0).stringValue())
                .isEqualTo(
                        "src/main/java/org/salespointframework/accountancy/PersistentAccountancy.java");
        assertThat(evidence.path("C5").path("productionJavaPaths").get(0).stringValue())
                .doesNotContain("src/test");

        String filters = candidates.path("filtersNotApplied").toPrettyString();
        assertThat(filters).contains("DI");
        assertThat(filters).contains("AOP");
        assertThat(filters).contains("event");
        assertThat(filters).contains("suggested pick");

        String jsonText = candidates.toPrettyString();
        assertNoSelectionLanguage(jsonText);
        assertNoSelectionLanguage(markdown);
        assertThat(jsonText).doesNotContain("Autowired");
        assertThat(markdown).contains("待确认");
        assertThat(markdown).contains("不选定案例");
        assertThat(markdown).contains("缺陷是否跨越 DI / AOP / 事件图边");
        assertThat(markdown).contains("st-tu-dresden-salespoint-85a764f892aa");
        assertThat(markdown).contains("scof-1326");
    }

    private static void assertNoSelectionLanguage(String text) {
        assertThat(text).doesNotContain("推荐");
        assertThat(text).doesNotContain("建议选择");
        assertThat(text).doesNotContain("看起来会走");
        assertThat(text).doesNotContain("should pick");
        assertThat(text).doesNotContain("recommend");
        assertThat(text).doesNotContain("更好");
    }

    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> {
            if (node.isTextual()) {
                values.add(node.stringValue());
            } else {
                values.add(node.path("caseId").stringValue());
            }
        });
        return values;
    }
}

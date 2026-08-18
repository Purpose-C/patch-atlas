package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frozen artifact readers: digest, role, constants, and case-directory suffix. */
class BenchmarkArtifactReaderTest {

    private static final Cohort VALID_COHORT = sampleCohort();
    private static final GeneratorContextMetadata VALID_CONTEXT = sampleContext();

    @TempDir
    Path tempDir;

    @Test
    void readCohortFromFrozenFileSucceedsWithSixCases() throws IOException {
        Cohort read = new BenchmarkArtifacts().readCohort(Path.of("benchmark-cases/task018/cohort.json"));

        assertThat(read.cases()).hasSize(6);
        assertThat(read.cases()).extracting(CohortCase::position).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(read.cases().subList(0, 3))
                .allMatch(item -> item.role() == BenchmarkArtifacts.Role.CALIBRATION);
        assertThat(read.cases().subList(3, 6))
                .allMatch(item -> item.role() == BenchmarkArtifacts.Role.AGENT_BENCHMARK);
        assertThat(read.datasetRevision()).isEqualTo(BenchmarkArtifacts.DATASET_REVISION);
        assertThat(read.seed()).isEqualTo(BenchmarkArtifacts.SEED);
        assertThat(read.selectorVersion()).isEqualTo(BenchmarkArtifacts.SELECTOR_VERSION);
        assertThat(read.cohortSha256()).isEqualTo(BenchmarkArtifacts.cohortSha256(read.cases()));
        assertThat(read.protocolLimitations()).isEmpty();
    }

    @Test
    void readProtocolFromFrozenFileSucceedsWithLimitations() throws IOException {
        BenchmarkArtifacts.ProtocolMetadata protocol = new BenchmarkArtifacts()
                .readProtocol(Path.of("benchmark-cases/task018/protocol.json"));

        assertThat(protocol.provider()).isEqualTo("agnes");
        assertThat(protocol.model()).isEqualTo("agnes-2.5-flash");
        assertThat(protocol.endpoint()).isEqualTo("https://apihub.agnes-ai.com/v1");
        assertThat(protocol.limitations()).hasSize(4);
        assertThat(protocol.limitations().get(0)).contains("无日期版本锚点");
        assertThat(protocol.limitations().get(1)).contains("训练数据构成");
        assertThat(protocol.limitations().get(2)).contains("32768");
        assertThat(protocol.limitations().get(2)).contains("测量误差而非能力信号");
        assertThat(protocol.limitations().get(3)).contains("GitBug-Java 数据源");
        assertThat(protocol.limitations().get(3)).contains("无法复现校准");
        assertThat(protocol.failureHandling()).contains("Patch Gate 的策略性拒绝可修正");
    }

    @Test
    void readProtocolFailsWhenFailureHandlingMissing() throws IOException {
        Path badProtocol = tempDir.resolve("protocol.json");
        Files.writeString(badProtocol, """
                {
                  "provider": "agnes",
                  "model": "agnes-2.5-flash",
                  "endpoint": "https://apihub.agnes-ai.com/v1",
                  "limitations": ["limitation 1"]
                }
                """);

        assertThatThrownBy(() -> new BenchmarkArtifacts().readProtocol(badProtocol))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureHandling");
    }

    @Test
    void readProtocolFailsWhenFileMissing() {
        assertThatThrownBy(() -> new BenchmarkArtifacts()
                        .readProtocol(Path.of("benchmark-cases/task018/nonexistent.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocol file missing");
    }

    @Test
    void readThreeArmProtocolFreezesModelIdentityAndKnownLimitations() throws IOException {
        BenchmarkArtifacts.ProtocolMetadata protocol = new BenchmarkArtifacts()
                .readProtocol(Path.of("benchmark-cases/batch5-three-arm/protocol.json"));

        assertThat(protocol.provider()).isEqualTo("ollama");
        assertThat(protocol.model()).isEqualTo("glm-5.2");
        assertThat(protocol.endpoint()).isEqualTo("https://ollama.com/v1");
        assertThat(protocol.limitations())
                .anySatisfy(item -> assertThat(item).contains("expand").contains("find").contains("search"))
                .anySatisfy(item -> assertThat(item).contains("jsoup#2002").contains("whatsapp#100").contains("文本臂"))
                .anySatisfy(item -> assertThat(item).contains("6 个二元").contains("不得用于臂间比较"));
        assertThat(protocol.failureHandling()).contains("Patch Gate 的策略性拒绝可修正");
        assertThat(protocol.failureHandling()).contains("在任何正式模型调用之前确定");
    }

    @Test
    void readThreeArmPreregisteredCriteriaFreezesTwoJudgements() throws IOException {
        BenchmarkArtifacts.PreregisteredCriteria table = new BenchmarkArtifacts()
                .readPreregisteredCriteria(
                        Path.of("benchmark-cases/batch5-three-arm/preregistered-criteria.json"));

        assertThat(table.criteria()).extracting(BenchmarkArtifacts.PreregisteredCriterion::id)
                .containsExactly("hunk-count-mismatch", "graph-expand-unused");
        BenchmarkArtifacts.PreregisteredCriterion hunk = table.criteria().get(0);
        assertThat(hunk.observation()).contains("count mismatch").contains("三分之一");
        assertThat(hunk.ifHolds()).contains("从正文重算").contains("finish_reason").contains("length");
        assertThat(hunk.ifDoesNotHold()).contains("维持现状");
        BenchmarkArtifacts.PreregisteredCriterion expand = table.criteria().get(1);
        assertThat(expand.observation()).contains("expand").contains("0");
        assertThat(expand.ifHolds()).contains("不提供").contains("不得据此断言");
        assertThat(expand.ifDoesNotHold()).doesNotContain("更好").doesNotContain("更差");
    }

    @Test
    void readThreeArmFirstRoundRejectionsCountsHunkMismatchWithoutPaths() throws IOException {
        ThreeArmEvidenceExporter.FirstRoundRejectionLog log = new BenchmarkArtifacts().readJson(
                Path.of("benchmark-cases/batch5-three-arm/generation-rejections.json"),
                ThreeArmEvidenceExporter.FirstRoundRejectionLog.class);

        assertThat(log.arms()).extracting(ThreeArmEvidenceExporter.ArmRejections::origin)
                .containsExactly("HEURISTIC", "TEXT_TOOLS", "GRAPH_TOOLS");
        int firstRound = 0;
        int hunk = 0;
        for (ThreeArmEvidenceExporter.ArmRejections arm : log.arms()) {
            assertThat(arm.cases()).hasSize(6);
            for (ThreeArmEvidenceExporter.CaseFirstRound item : arm.cases()) {
                if (item.firstRound() == null) {
                    continue;
                }
                firstRound++;
                if (ThreeArmEvidenceExporter.isHunkCountMismatch(item.firstRound().feedbackSummary())) {
                    hunk++;
                }
            }
        }
        assertThat(firstRound).isEqualTo(16);
        assertThat(hunk).isEqualTo(10);
        assertThat(hunk * 3).isGreaterThan(firstRound);
        String raw = Files.readString(Path.of("benchmark-cases/batch5-three-arm/generation-rejections.json"));
        assertThat(raw).doesNotContain("/Users/").doesNotContain("/home/");
    }

    @Test
    void threeArmEvidenceReportOpensWithLimitationsAndOmitsRankingClaims() throws IOException {
        String md = Files.readString(Path.of("benchmark-cases/batch5-three-arm/evidence-report.md"));
        int limitations = md.indexOf("## Known limitations");
        int heuristic = md.indexOf("## Arm HEURISTIC");
        int paired = md.indexOf("## Paired localization coverage");
        assertThat(limitations).isGreaterThanOrEqualTo(0);
        assertThat(limitations).isLessThan(heuristic);
        assertThat(md.indexOf("不得用于臂间比较")).isBetween(limitations, heuristic);
        assertThat(md).contains("VALID_REPRODUCTION 0 / 6");
        assertThat(md).contains("Denominator: every AGENT_BENCHMARK run on this arm");
        assertThat(md).contains("| # | Case | anyHit | recall | precision | selectedCount |");
        assertThat(md).contains("10 / 16 first-round rejections");
        assertThat(md).contains("expand count: 0");
        assertThat(md).contains("mean precision: 0.0889 (n=5)");
        assertThat(md).contains("generation token median: 49070 (n=5)");
        assertThat(md).contains("runs that never reached generation: 1");
        assertThat(md).contains("| false | 0.0000 | N/A | 0 |");
        assertThat(md).doesNotContain("| false | 0.0000 | 0.0000 | 0 |");
        int notes = md.indexOf("## Evaluation notes");
        assertThat(notes).isGreaterThan(heuristic);
        assertThat(md.substring(notes)).contains("17 次未进入 Docker Replay");
        assertThat(md.substring(notes)).contains("逐例 recall 相同");
        assertThat(md.substring(0, notes)).doesNotContain("17 次未进入 Docker Replay");
        assertThat(md).contains("另开工作把 hunk 计数改为从正文重算");
        assertThat(md).contains("不得据此断言图没有信息量");
        assertThat(md.substring(paired, md.indexOf("## Evaluation notes"))).doesNotContain("VALID_REPRODUCTION");
        assertThat(md)
                .doesNotContain("综合得分")
                .doesNotContain("图更好")
                .doesNotContain("文本更好")
                .doesNotContain("更好")
                .doesNotContain("更差")
                .doesNotContain("/Users/")
                .doesNotContain("Task 0");
        assertThat(Files.readString(Path.of("benchmark-cases/batch5-three-arm/results.json")))
                .contains("\"validReproductions\" : 0")
                .contains("\"runCount\" : 6")
                .doesNotContain("overallScore")
                .doesNotContain("composite");
    }

    @Test
    void readPreregisteredCriteriaFailsWhenFileMissing() {
        assertThatThrownBy(() -> new BenchmarkArtifacts()
                        .readPreregisteredCriteria(
                                Path.of("benchmark-cases/batch5-three-arm/nonexistent.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preregistered criteria file missing");
    }

    @Test
    void readPreregisteredCriteriaRejectsEmptyList() throws IOException {
        Path path = tempDir.resolve("preregistered-criteria.json");
        Files.writeString(path, """
                { "criteria": [] }
                """);

        assertThatThrownBy(() -> new BenchmarkArtifacts().readPreregisteredCriteria(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preregistered criteria must not be empty");
    }

    @Test
    void readPreregisteredCriteriaRejectsBlankObservation() throws IOException {
        Path path = tempDir.resolve("preregistered-criteria.json");
        Files.writeString(path, """
                {
                  "criteria": [
                    {
                      "id": "hunk-count-mismatch",
                      "observation": " ",
                      "ifHolds": "from body",
                      "ifDoesNotHold": "keep"
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> new BenchmarkArtifacts().readPreregisteredCriteria(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observation");
    }

    @Test
    void readCohortFromDiskSucceedsForValidFile() throws IOException {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        Path cohortPath = tempDir.resolve("cohort.json");
        artifacts.write(cohortPath, VALID_COHORT);

        Cohort read = artifacts.readCohort(cohortPath);

        assertThat(read.cases()).hasSize(6);
        assertThat(read.cohortSha256()).isEqualTo(VALID_COHORT.cohortSha256());
        assertThat(read.datasetRevision()).isEqualTo(BenchmarkArtifacts.DATASET_REVISION);
        assertThat(read.seed()).isEqualTo(BenchmarkArtifacts.SEED);
        assertThat(read.selectorVersion()).isEqualTo(BenchmarkArtifacts.SELECTOR_VERSION);
    }

    @Test
    void readCohortRejectsMismatchedSelectorConstants() throws IOException {
        String json = """
                {
                  "datasetRevision": "%s",
                  "seed": "%s",
                  "selectorVersion": "other-selector",
                  "rulesSha256": "%s",
                  "cohortSha256": "%s",
                  "cases": %s
                }
                """.formatted(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                VALID_COHORT.rulesSha256(),
                VALID_COHORT.cohortSha256(),
                casesJson());
        Path cohortPath = tempDir.resolve("cohort.json");
        Files.writeString(cohortPath, json);

        assertThatThrownBy(() -> new BenchmarkArtifacts().readCohort(cohortPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selectorVersion");
    }

    @Test
    void readCohortRejectsTamperedSha256() throws IOException {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        Path cohortPath = tempDir.resolve("cohort.json");
        artifacts.write(cohortPath, VALID_COHORT);

        String json = Files.readString(cohortPath);
        String tampered = json.replace(VALID_COHORT.cohortSha256(), "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        Files.writeString(cohortPath, tampered);

        assertThatThrownBy(() -> artifacts.readCohort(cohortPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cohortSha256");
    }

    @Test
    void readCohortRejectsRoleMisassignment() throws IOException {
        // Write raw JSON with role that doesn't match position (bypasses record constructor)
        CohortCase original = VALID_COHORT.cases().get(3);
        String sha = BenchmarkArtifacts.cohortSha256(List.of(
                VALID_COHORT.cases().get(0), VALID_COHORT.cases().get(1), VALID_COHORT.cases().get(2),
                new CohortCase(
                        4, BenchmarkArtifacts.Role.AGENT_BENCHMARK, original.caseId(),
                        original.sortKey(), original.repositoryUrl(), original.issueUrl(),
                        original.license(), original.modulePath(), original.javaVersion()),
                VALID_COHORT.cases().get(4), VALID_COHORT.cases().get(5)));
        // Write JSON with position 4 as CALIBRATION (bypasses CohortCase constructor)
        String json = """
                {
                  "datasetRevision": "%s",
                  "seed": "%s",
                  "selectorVersion": "%s",
                  "rulesSha256": "%s",
                  "cohortSha256": "%s",
                  "cases": [
                    {"position":1,"role":"CALIBRATION","caseId":"case-1","sortKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","repositoryUrl":"https://github.com/ex/repo.git","issueUrl":"https://github.com/ex/repo/issues/1","license":"MIT","modulePath":"","javaVersion":"17"},
                    {"position":2,"role":"CALIBRATION","caseId":"case-2","sortKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","repositoryUrl":"https://github.com/ex/repo.git","issueUrl":"https://github.com/ex/repo/issues/1","license":"MIT","modulePath":"","javaVersion":"17"},
                    {"position":3,"role":"CALIBRATION","caseId":"case-3","sortKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","repositoryUrl":"https://github.com/ex/repo.git","issueUrl":"https://github.com/ex/repo/issues/1","license":"MIT","modulePath":"","javaVersion":"17"},
                    {"position":4,"role":"CALIBRATION","caseId":"case-4","sortKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","repositoryUrl":"https://github.com/ex/repo.git","issueUrl":"https://github.com/ex/repo/issues/1","license":"MIT","modulePath":"","javaVersion":"17"},
                    {"position":5,"role":"AGENT_BENCHMARK","caseId":"case-5","sortKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","repositoryUrl":"https://github.com/ex/repo.git","issueUrl":"https://github.com/ex/repo/issues/1","license":"MIT","modulePath":"","javaVersion":"17"},
                    {"position":6,"role":"AGENT_BENCHMARK","caseId":"case-6","sortKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","repositoryUrl":"https://github.com/ex/repo.git","issueUrl":"https://github.com/ex/repo/issues/1","license":"MIT","modulePath":"","javaVersion":"17"}
                  ]
                }
                """.formatted(
                BenchmarkArtifacts.DATASET_REVISION, BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION, VALID_COHORT.rulesSha256(), sha);
        Path cohortPath = tempDir.resolve("cohort.json");
        Files.writeString(cohortPath, json);

        assertThatThrownBy(() -> new BenchmarkArtifacts().readCohort(cohortPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("role");
    }

    @Test
    void readGeneratorContextSucceedsForValidFile() throws IOException {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        Path ctxPath = tempDir.resolve("1-" + VALID_CONTEXT.caseId()).resolve("generator-context.json");
        artifacts.write(ctxPath, VALID_CONTEXT);

        GeneratorContextMetadata read = artifacts.readGeneratorContext(ctxPath);

        assertThat(read.caseId()).isEqualTo(VALID_CONTEXT.caseId());
        assertThat(read.sources()).hasSize(VALID_CONTEXT.sources().size());
    }

    @Test
    void readGeneratorContextRejectsCaseIdMismatchWithDirectory() throws IOException {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        Path ctxPath = tempDir.resolve("4-test-case").resolve("generator-context.json");
        artifacts.write(ctxPath, VALID_CONTEXT);

        assertThatThrownBy(() -> artifacts.readGeneratorContext(ctxPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caseId");
    }

    private static Cohort sampleCohort() {
        List<CohortCase> cases = List.of(
                caseAt(1, BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(2, BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(3, BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(4, BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(5, BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(6, BenchmarkArtifacts.Role.AGENT_BENCHMARK));
        return new Cohort(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                BenchmarkArtifacts.cohortSha256(cases),
                cases,
                List.of());
    }

    private static CohortCase caseAt(int position, BenchmarkArtifacts.Role role) {
        return new CohortCase(
                position, role, "case-" + position,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT", "", "17");
    }

    private static String casesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            CohortCase item = VALID_COHORT.cases().get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"position\":").append(item.position())
                    .append(",\"role\":\"").append(item.role()).append('"')
                    .append(",\"caseId\":\"").append(item.caseId()).append('"')
                    .append(",\"sortKey\":\"").append(item.sortKey()).append('"')
                    .append(",\"repositoryUrl\":\"").append(item.repositoryUrl()).append('"')
                    .append(",\"issueUrl\":\"").append(item.issueUrl()).append('"')
                    .append(",\"license\":\"").append(item.license()).append('"')
                    .append(",\"modulePath\":\"").append(item.modulePath()).append('"')
                    .append(",\"javaVersion\":\"").append(item.javaVersion()).append("\"}");
        }
        return json.append(']').toString();
    }

    private static GeneratorContextMetadata sampleContext() {
        return new GeneratorContextMetadata(
                "case-1",
                "https://github.com/ex/repo/issues/1",
                "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                "0123456789abcdef0123456789abcdef01234567",
                List.of(new SourceReference(
                        "src/main/java/Foo.java",
                        "fedcba9876543210fedcba9876543210fedcba98",
                        "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                        "ISSUE_PATH_MATCH")),
                List.of());
    }
}

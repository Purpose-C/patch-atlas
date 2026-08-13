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
        assertThat(protocol.limitations()).hasSize(2);
        assertThat(protocol.limitations().get(0)).contains("无日期版本锚点");
        assertThat(protocol.limitations().get(1)).contains("训练数据构成");
    }

    @Test
    void readProtocolFailsWhenFileMissing() {
        assertThatThrownBy(() -> new BenchmarkArtifacts()
                        .readProtocol(Path.of("benchmark-cases/task018/nonexistent.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocol file missing");
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

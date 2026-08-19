package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.ExcludedSource;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Role;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringCaseStudyCaseFilesTest {

    private static final Path ARTIFACTS = Path.of("benchmark-cases/spring-case-study");

    @Test
    void committedCaseFilesMatchTheConfirmedSelection() throws Exception {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        CohortCase study = artifacts.readCohortCase(ARTIFACTS.resolve("case.json"));
        assertThat(study.caseId()).isEqualTo(CaseStudyCaseFiles.SELECTED_CASE_ID);
        assertThat(study.position()).isEqualTo(CaseStudyCaseFiles.AGENT_POSITION);
        assertThat(study.role()).isEqualTo(Role.AGENT_BENCHMARK);
        assertThat(study.javaVersion()).isEqualTo("17");
        Path directory = CaseStudyCaseFiles.caseDirectory(ARTIFACTS, study);
        GeneratorContextMetadata context =
                artifacts.readGeneratorContext(directory.resolve("generator-context.json"));
        OracleMetadata oracle = new CalibrationOracleReader().read(directory.resolve("oracle.json"));
        assertThat(context.caseId()).isEqualTo(CaseStudyCaseFiles.SELECTED_CASE_ID);
        assertThat(oracle.caseId()).isEqualTo(CaseStudyCaseFiles.SELECTED_CASE_ID);
        assertThat(context.buggyRevision()).isEqualTo("e9c2fe5efee8ad76bf73738f46f911c18eb078b8");
        assertThat(oracle.fixedRevision()).startsWith("85a764f892aa");
        assertThat(Files.readString(directory.resolve("oracle.json"))).doesNotContain("diff --git");
        JsonNodeProtocol.protocolUnchanged();
    }

    @Test
    void writerStoresTheSelectedCase(@TempDir Path tempDir) throws Exception {
        CohortCase study = selectedCase();
        CaseStudyCaseFiles.write(tempDir, study, selectedContext(), selectedOracle());
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        assertThat(artifacts.readCohortCase(tempDir.resolve("case.json")).caseId())
                .isEqualTo(CaseStudyCaseFiles.SELECTED_CASE_ID);
        assertThat(Files.isRegularFile(CaseStudyCaseFiles.caseDirectory(tempDir, study)
                .resolve("oracle.json"))).isTrue();
    }

    @Test
    void writerRejectsADifferentCaseId(@TempDir Path tempDir) {
        CohortCase wrong = new CohortCase(
                4,
                Role.AGENT_BENCHMARK,
                "other-case-aaaaaaaaaaaa",
                "a".repeat(64),
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT",
                "",
                "17");
        assertThatThrownBy(() -> CaseStudyCaseFiles.write(
                        tempDir, wrong, selectedContext(), selectedOracle()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected case-study caseId");
    }

    private static CohortCase selectedCase() {
        return new CohortCase(
                CaseStudyCaseFiles.AGENT_POSITION,
                Role.AGENT_BENCHMARK,
                CaseStudyCaseFiles.SELECTED_CASE_ID,
                "a".repeat(64),
                "https://github.com/st-tu-dresden/salespoint.git",
                "https://github.com/st-tu-dresden/salespoint/issues/412",
                "Apache-2.0",
                "",
                "17");
    }

    private static GeneratorContextMetadata selectedContext() {
        return new GeneratorContextMetadata(
                CaseStudyCaseFiles.SELECTED_CASE_ID,
                "https://github.com/st-tu-dresden/salespoint/issues/412",
                "b".repeat(64),
                "e9c2fe5efee8ad76bf73738f46f911c18eb078b8",
                List.of(new SourceReference(
                        "src/Main.java", "c".repeat(40), "d".repeat(64), "ISSUE_CLASS_NAME")),
                List.of(new ExcludedSource("src/TooBig.java", "FILE_LIMIT")));
    }

    private static OracleMetadata selectedOracle() {
        return new OracleMetadata(
                CaseStudyCaseFiles.SELECTED_CASE_ID,
                "85a764f892aaca4cfcb6749e55f3131a23cc8f66",
                "org.ExampleTest",
                "fails",
                "e".repeat(64));
    }

    private static final class JsonNodeProtocol {
        private static void protocolUnchanged() throws Exception {
            String protocol = Files.readString(ARTIFACTS.resolve("protocol.json"));
            assertThat(protocol).contains("\"registeredBeforeRuns\": true");
            assertThat(protocol).contains("\"strength\": \"weak\"");
            assertThat(protocol).contains("\"strength\": \"strong\"");
            assertThat(protocol).contains("\"evaluationId\": \"spring-case-study\"");
        }
    }
}

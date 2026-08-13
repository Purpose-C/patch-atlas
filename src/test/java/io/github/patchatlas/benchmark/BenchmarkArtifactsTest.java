package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

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

class BenchmarkArtifactsTest {

    @TempDir
    Path tempDir;

    @Test
    void writesPhysicallySeparatedMetadataWithoutSourceOrPatchText() throws Exception {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        GeneratorContextMetadata generator = new GeneratorContextMetadata(
                "case-1",
                "https://github.com/o/r/issues/1",
                "a".repeat(64),
                "b".repeat(40),
                List.of(new SourceReference(
                        "src/A.java", "c".repeat(40), "d".repeat(64), "EXACT_PATH")),
                List.of(new ExcludedSource("src/B.java", "FILE_LIMIT")));
        OracleMetadata oracle = new OracleMetadata(
                "case-1", "e".repeat(40), "p.T", "fails", "f".repeat(64));

        Path generatorPath = tempDir.resolve("generator-context.json");
        Path oraclePath = tempDir.resolve("oracle.json");
        artifacts.write(generatorPath, generator);
        artifacts.write(oraclePath, oracle);

        String generatorJson = Files.readString(generatorPath);
        String oracleJson = Files.readString(oraclePath);
        assertThat(generatorJson).contains("issueContentSha256", "gitBlobId");
        assertThat(generatorJson).doesNotContain("class A", "fixedRevision", "patchText");
        assertThat(oracleJson).contains("fixedRevision", "knownTriggerPatchSha256");
        assertThat(oracleJson).doesNotContain("patchText");
    }

    @Test
    void cohortDigestIsDeterministicAndSensitiveToPosition() {
        CohortCase first = cohortCase(1, Role.CALIBRATION, "case-a");
        CohortCase second = cohortCase(2, Role.CALIBRATION, "case-b");

        String one = BenchmarkArtifacts.cohortSha256(List.of(first, second));
        String same = BenchmarkArtifacts.cohortSha256(List.of(first, second));
        String reversed = BenchmarkArtifacts.cohortSha256(List.of(
                cohortCase(1, Role.CALIBRATION, "case-b"),
                cohortCase(2, Role.CALIBRATION, "case-a")));

        assertThat(one).isEqualTo(same).isNotEqualTo(reversed).hasSize(64);
    }

    private static CohortCase cohortCase(int position, Role role, String caseId) {
        return new CohortCase(
                position,
                role,
                caseId,
                "0".repeat(64),
                "https://github.com/o/r.git",
                "https://github.com/o/r/issues/1",
                "MIT",
                "",
                "17");
    }
}

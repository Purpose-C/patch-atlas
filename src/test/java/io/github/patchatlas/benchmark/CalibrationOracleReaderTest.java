package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Calibration Oracle reader: SHA shape checks for Fixed Revision and known-trigger digest. */
class CalibrationOracleReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readValidOracleSucceeds() throws IOException {
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        OracleMetadata metadata = new OracleMetadata(
                "case-1",
                "0123456789abcdef0123456789abcdef01234567",
                "c.T", "m",
                "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd");
        Path path = tempDir.resolve("oracle.json");
        artifacts.write(path, metadata);

        CalibrationOracleReader reader = new CalibrationOracleReader();
        OracleMetadata read = reader.read(path);

        assertThat(read.caseId()).isEqualTo("case-1");
        assertThat(read.fixedRevision()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(read.targetClass()).isEqualTo("c.T");
        assertThat(read.targetMethod()).isEqualTo("m");
    }

    @Test
    void readRejectsInvalidFixedRevision() throws IOException {
        // Write raw JSON with invalid fixedRevision (bypasses record constructor)
        Files.writeString(tempDir.resolve("oracle.json"), """
                {
                  "caseId": "case-1",
                  "fixedRevision": "not-a-sha",
                  "targetClass": "c.T",
                  "targetMethod": "m",
                  "knownTriggerPatchSha256": "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                }
                """);

        CalibrationOracleReader reader = new CalibrationOracleReader();
        assertThatThrownBy(() -> reader.read(tempDir.resolve("oracle.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixedRevision");
    }

    @Test
    void readRejectsKnownTriggerDigestThatIsNot64Hex() throws IOException {
        Files.writeString(tempDir.resolve("oracle.json"), """
                {
                  "caseId": "case-1",
                  "fixedRevision": "0123456789abcdef0123456789abcdef01234567",
                  "targetClass": "c.T",
                  "targetMethod": "m",
                  "knownTriggerPatchSha256": "0123456789abcdef0123456789abcdef01234567"
                }
                """);

        CalibrationOracleReader reader = new CalibrationOracleReader();
        assertThatThrownBy(() -> reader.read(tempDir.resolve("oracle.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knownTriggerPatchSha256");
    }
}

package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 读取 Oracle 元数据（Fixed Revision、已知触发测试）。
 *
 * <p>仅 Calibration 路径可用；Agent 路径不得引用此类或 {@link OracleMetadata}。
 */
public final class CalibrationOracleReader {

    private static final Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA64 = Pattern.compile("^[0-9a-f]{64}$");

    private final BenchmarkArtifacts artifacts;

    public CalibrationOracleReader() {
        this(new BenchmarkArtifacts());
    }

    CalibrationOracleReader(BenchmarkArtifacts artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public OracleMetadata read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        OracleMetadata metadata;
        try {
            metadata = artifacts.readJson(path, OracleMetadata.class);
        } catch (RuntimeException ex) {
            throw illegalArgument(ex);
        }
        if (!SHA40.matcher(metadata.fixedRevision()).matches()) {
            throw new IllegalArgumentException(
                    "fixedRevision must be 40 lowercase hex chars: " + metadata.fixedRevision());
        }
        if (!SHA64.matcher(metadata.knownTriggerPatchSha256()).matches()) {
            throw new IllegalArgumentException(
                    "knownTriggerPatchSha256 must be 64 lowercase hex chars");
        }
        return metadata;
    }

    private static IllegalArgumentException illegalArgument(RuntimeException ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof IllegalArgumentException illegal) {
                return illegal;
            }
            cursor = cursor.getCause();
        }
        return new IllegalArgumentException("oracle metadata is invalid", ex);
    }
}

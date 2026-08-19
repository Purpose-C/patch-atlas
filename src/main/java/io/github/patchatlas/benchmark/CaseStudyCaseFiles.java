package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Writes the confirmed Spring case-study case files. Not a frozen six-case cohort. */
public final class CaseStudyCaseFiles {

    public static final String SELECTED_CASE_ID = "st-tu-dresden-salespoint-85a764f892aa";
    public static final int AGENT_POSITION = 4;

    private CaseStudyCaseFiles() {}

    public static Path caseDirectory(Path artifactsRoot, CohortCase studyCase) {
        Objects.requireNonNull(artifactsRoot, "artifactsRoot");
        Objects.requireNonNull(studyCase, "studyCase");
        return artifactsRoot.resolve("cases")
                .resolve("%d-%s".formatted(studyCase.position(), studyCase.caseId()));
    }

    public static void write(
            Path artifactsRoot,
            CohortCase studyCase,
            GeneratorContextMetadata context,
            OracleMetadata oracle) throws IOException {
        Objects.requireNonNull(artifactsRoot, "artifactsRoot");
        Objects.requireNonNull(studyCase, "studyCase");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(oracle, "oracle");
        if (!SELECTED_CASE_ID.equals(studyCase.caseId())) {
            throw new IllegalArgumentException("unexpected case-study caseId " + studyCase.caseId());
        }
        if (studyCase.position() != AGENT_POSITION) {
            throw new IllegalArgumentException("case-study position must be " + AGENT_POSITION);
        }
        if (!SELECTED_CASE_ID.equals(context.caseId()) || !SELECTED_CASE_ID.equals(oracle.caseId())) {
            throw new IllegalArgumentException("case file identities must match the selected case");
        }
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        artifacts.write(artifactsRoot.resolve("case.json"), studyCase);
        Path directory = caseDirectory(artifactsRoot, studyCase);
        artifacts.write(directory.resolve("generator-context.json"), context);
        artifacts.write(directory.resolve("oracle.json"), oracle);
    }
}

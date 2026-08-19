package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.Selection;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.ExcludedSource;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Role;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import io.github.patchatlas.benchmark.KnownTriggerResolver.ResolvedKnownTrigger;
import io.github.patchatlas.repository.CaseManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Writes generator-context and oracle files for the confirmed Spring case study. */
@Tag("benchmark")
class SpringCaseStudyPrepareHarnessTest {

    @Test
    void writeSelectedCaseFiles() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path output = projectRoot.resolve("benchmark-cases/spring-case-study");
        Path caseJson = output.resolve("case.json");
        if (Files.isRegularFile(caseJson)) {
            return;
        }

        Path gitbugRoot = gitbugRoot(projectRoot);
        var metadata = new GitBugJavaMetadataReader()
                .read(gitbugRoot.resolve("data/bugs"))
                .stream()
                .filter(item -> CaseStudyCaseFiles.SELECTED_CASE_ID.equals(
                        item.generatorData().caseId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "GitBug-Java metadata missing for " + CaseStudyCaseFiles.SELECTED_CASE_ID));

        Path cacheRoot = projectRoot.resolve(".patch-atlas-cache/spring-case-study");
        Files.createDirectories(cacheRoot);
        BenchmarkGitWorkspace git = new BenchmarkGitWorkspace(cacheRoot);
        var generator = metadata.generatorData();
        BenchmarkGitWorkspace.CheckoutResult checkout = git.checkout(
                generator.repositoryUrl(), generator.buggyRevision(), generator.caseId() + "-buggy");
        if (!(checkout instanceof BenchmarkGitWorkspace.CheckoutResult.Success success)) {
            throw new IllegalStateException("checkout failed for " + generator.caseId());
        }

        RepositoryStaticInspector.RepositoryFacts facts =
                new RepositoryStaticInspector().inspect(success.workspace());
        if (!facts.inspectionComplete() || facts.licenseSpdx().isEmpty()) {
            throw new IllegalStateException("static inspection incomplete for " + generator.caseId());
        }
        ResolvedKnownTrigger trigger = new KnownTriggerResolver()
                .resolve(
                        metadata.oracleData().knownTriggerPatch(),
                        metadata.oracleData().targetCandidates())
                .orElseThrow(() -> new IllegalStateException(
                        "known trigger did not resolve for " + generator.caseId()));

        CaseManifest.GeneratorContext context = new CaseManifest.GeneratorContext(
                generator.caseId(),
                generator.repositoryUrl(),
                facts.licenseSpdx().orElseThrow(),
                generator.issueUrl(),
                generator.buggyRevision(),
                trigger.modulePath(),
                preferredJava(facts));
        Selection selection = new BuggyOnlyGeneratorContextBuilder().build(
                context,
                generator.issueTitle(),
                generator.issueBody(),
                new BuggyRepositoryReader().readJavaFiles(
                        success.workspace(), generator.buggyRevision()));

        CohortCase studyCase = new CohortCase(
                CaseStudyCaseFiles.AGENT_POSITION,
                Role.AGENT_BENCHMARK,
                generator.caseId(),
                BenchmarkArtifacts.sha256(generator.caseId()),
                generator.repositoryUrl(),
                generator.issueUrl(),
                facts.licenseSpdx().orElseThrow(),
                trigger.modulePath(),
                preferredJava(facts));
        GeneratorContextMetadata generatorMetadata = new GeneratorContextMetadata(
                generator.caseId(),
                generator.issueUrl(),
                BenchmarkArtifacts.issueContentSha256(
                        generator.issueTitle(), generator.issueBody()),
                generator.buggyRevision(),
                selection.selected().stream()
                        .map(source -> new SourceReference(
                                source.snapshot().relativePath(),
                                source.blobId(),
                                source.contentSha256(),
                                source.reason().name()))
                        .toList(),
                selection.excluded().stream()
                        .map(source -> new ExcludedSource(
                                source.relativePath(), source.reason().name()))
                        .toList());
        OracleMetadata oracle = new OracleMetadata(
                generator.caseId(),
                metadata.oracleData().fixedRevision(),
                trigger.targetTest().className(),
                trigger.targetTest().methodName(),
                trigger.patchSha256());
        CaseStudyCaseFiles.write(output, studyCase, generatorMetadata, oracle);

        assertThat(caseJson).isRegularFile();
        assertThat(CaseStudyCaseFiles.caseDirectory(output, studyCase)
                .resolve("generator-context.json")).isRegularFile();
        assertThat(CaseStudyCaseFiles.caseDirectory(output, studyCase).resolve("oracle.json"))
                .isRegularFile();
        assertThat(Files.readString(CaseStudyCaseFiles.caseDirectory(output, studyCase)
                .resolve("oracle.json"))).doesNotContain("diff --git");
    }

    private static String preferredJava(RepositoryStaticInspector.RepositoryFacts facts) {
        if (facts.supportedJavaVersions().contains(17)) {
            return "17";
        }
        if (facts.supportedJavaVersions().contains(21)) {
            return "21";
        }
        throw new IllegalStateException("no supported Java version");
    }

    private static Path gitbugRoot(Path projectRoot) throws Exception {
        String configured = System.getenv("PATCHATLAS_GITBUG_JAVA_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toRealPath();
        }
        Path local = projectRoot.resolve(".patch-atlas-cache/GitBug-Java");
        if (Files.isDirectory(local.resolve("data/bugs"))) {
            return local.toRealPath();
        }
        throw new IllegalStateException("PATCHATLAS_GITBUG_JAVA_ROOT is required");
    }
}

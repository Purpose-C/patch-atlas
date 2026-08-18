package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.agent.SpringAiTestGenerator;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Role;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.GeneratorData;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.OracleData;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.StaticMetadata;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.FormalReplayCoordinator;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.RunSubmission;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Shared knobs across the three locating arms; only ContextOrigin is the factor under test. */
class ThreeArmControlVariablesTest {

    @TempDir
    Path tempDir;

    @Test
    void generatorContextLimitsAndAttemptCapAreShared() {
        assertThat(GenerationInput.MAX_SNAPSHOTS).isEqualTo(12);
        assertThat(GenerationInput.MAX_TOTAL_SOURCE_BYTES).isEqualTo(256 * 1024);
        assertThat(GenerationRequest.MAX_ATTEMPTS).isEqualTo(3);
        assertThat(OpenAiChatModelFactory.MAX_COMPLETION_TOKENS).isEqualTo(32768);
    }

    @Test
    void generationSystemPromptDoesNotTakeLocatingOrigin() {
        Method[] methods = SpringAiTestGenerator.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods).filter(method -> method.getName().equals("buildSystemPrompt")))
                .hasSize(1)
                .allSatisfy(method -> {
                    assertThat(method.getParameterCount()).isZero();
                    assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
                });
    }

    @Test
    void patchGateInspectDoesNotTakeLocatingOrigin() {
        assertThat(PatchGate.class.getMethods())
                .filteredOn(method -> "inspect".equals(method.getName()))
                .isNotEmpty()
                .allSatisfy(method ->
                        assertThat(Arrays.asList(method.getParameterTypes())).doesNotContain(ContextOrigin.class));
    }

    @Test
    void armLaunchUsesEmptySnapshotsAndRejectsPinned() {
        assertThat(FrozenBenchmarkOperations.snapshotsForOrigin(ContextOrigin.HEURISTIC)).isEmpty();
        assertThat(FrozenBenchmarkOperations.snapshotsForOrigin(ContextOrigin.TEXT_TOOLS)).isEmpty();
        assertThat(FrozenBenchmarkOperations.snapshotsForOrigin(ContextOrigin.GRAPH_TOOLS)).isEmpty();
        assertThat(FrozenBenchmarkOperations.selectedPathsFromSnapshots(List.of())).isEmpty();
        assertThat(FrozenBenchmarkOperations.selectedPathsFromSnapshots(null)).isEmpty();
        assertThat(FrozenBenchmarkOperations.selectedPathsFromSnapshots(
                        List.of(new SourceSnapshot("src/A.java", "class A {}"))))
                .containsExactly("src/A.java");
        assertThatThrownBy(() -> FrozenBenchmarkOperations.snapshotsForOrigin(ContextOrigin.PINNED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned");
    }

    @Test
    void launchAgentWithOriginSubmitsEmptySnapshotsAndSkipsMaterializer() throws Exception {
        Path caseDir = tempDir.resolve("cases/1-case-1");
        Files.createDirectories(caseDir);
        String title = "issue title";
        String body = "issue body";
        String issueSha = BenchmarkArtifacts.issueContentSha256(title, body);
        GeneratorContextMetadata context = new GeneratorContextMetadata(
                "case-1",
                "https://github.com/ex/repo/issues/1",
                issueSha,
                "a".repeat(40),
                List.of(new SourceReference(
                        "src/A.java",
                        "b".repeat(40),
                        "c".repeat(64),
                        "ISSUE_PATH_MATCH")),
                List.of());
        OracleMetadata oracle = new OracleMetadata(
                "case-1", "d".repeat(40), "p.T", "m", "e".repeat(64));
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        artifacts.write(caseDir.resolve("generator-context.json"), context);
        artifacts.write(caseDir.resolve("oracle.json"), oracle);

        CaseMetadata metadata = new CaseMetadata(
                new GeneratorData(
                        "case-1",
                        "https://github.com/ex/repo.git",
                        "https://github.com/ex/repo/issues/1",
                        title,
                        body,
                        "a".repeat(40)),
                new OracleData("d".repeat(40), "unused-patch", List.of(new TargetTest("p.T", "m"))),
                new StaticMetadata(true, true, true, true));

        GeneratorContextMaterializer materializer = Mockito.mock(GeneratorContextMaterializer.class);
        PostgresRunStore store = Mockito.mock(PostgresRunStore.class);
        UUID runId = UUID.randomUUID();
        when(store.submitAgentBenchmark(any())).thenReturn(runId);

        FrozenBenchmarkOperations operations = new FrozenBenchmarkOperations(
                tempDir,
                artifacts,
                new CalibrationOracleReader(),
                materializer,
                new KnownTriggerResolver(),
                store,
                Mockito.mock(FormalReplayCoordinator.class),
                new BenchmarkEvidenceExporter(),
                Mockito.mock(BenchmarkGitWorkspace.class),
                List.of(metadata),
                "owner",
                Duration.ofMinutes(1));
        CohortCase cohortCase = new CohortCase(
                1,
                Role.CALIBRATION,
                "case-1",
                "0".repeat(64),
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT",
                "",
                "17");

        assertThat(operations.launchAgent(cohortCase, ContextOrigin.TEXT_TOOLS)).isEqualTo(runId);

        verify(materializer, never()).materialize(any(), any(), any());
        ArgumentCaptor<RunSubmission> captor = ArgumentCaptor.forClass(RunSubmission.class);
        verify(store).submitAgentBenchmark(captor.capture());
        assertThat(captor.getValue().sourceSnapshots()).isEmpty();
        assertThat(captor.getValue().contextOrigin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(captor.getValue().caseId()).isEqualTo("case-1");
    }
}

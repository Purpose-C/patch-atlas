package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.Selection;
import io.github.patchatlas.benchmark.DynamicCaseQualifier.Result;
import io.github.patchatlas.benchmark.FrozenCohortSelector.CandidateFacts;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.GeneratorData;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.OracleData;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.StaticMetadata;
import io.github.patchatlas.benchmark.KnownTriggerResolver.ResolvedKnownTrigger;
import io.github.patchatlas.replay.TargetTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CohortFreezeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void freezesExactlySixCasesAndWritesSeparatedMetadata() throws Exception {
        List<CaseMetadata> metadata = cases(6);
        CohortFreezeService service = service();

        CohortFreezeService.FreezeResult result = service.freeze(metadata, tempDir);

        assertThat(result.cohort().cases()).hasSize(6);
        assertThat(result.cohort().cases()).extracting(BenchmarkArtifacts.CohortCase::position)
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(result.cohort().cases().subList(0, 3))
                .allMatch(item -> item.role() == BenchmarkArtifacts.Role.CALIBRATION);
        assertThat(result.cohort().cases().subList(3, 6))
                .allMatch(item -> item.role() == BenchmarkArtifacts.Role.AGENT_BENCHMARK);
        assertThat(result.audit().probes()).hasSize(6);
        assertThat(Files.readString(tempDir.resolve("cohort.json")))
                .contains("cohortSha256", BenchmarkArtifacts.DATASET_REVISION);
        assertThat(Files.readString(tempDir.resolve("selection-audit.json")))
                .contains("startedAt", "ELIGIBLE");
        for (BenchmarkArtifacts.CohortCase item : result.cohort().cases()) {
            Path caseDir = tempDir.resolve("cases")
                    .resolve(item.position() + "-" + item.caseId());
            assertThat(caseDir.resolve("generator-context.json")).isRegularFile();
            assertThat(caseDir.resolve("oracle.json")).isRegularFile();
            assertThat(Files.readString(caseDir.resolve("oracle.json")))
                    .doesNotContain("known patch text");
        }
    }

    @Test
    void writesAuditButNotCohortWhenSixEligibleCasesCannotBeFormed() {
        CohortFreezeService service = service();

        assertThatThrownBy(() -> service.freeze(cases(5), tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 6");

        assertThat(tempDir.resolve("selection-audit.json")).isRegularFile();
        assertThat(tempDir.resolve("cohort.json")).doesNotExist();
    }

    private CohortFreezeService service() {
        return new CohortFreezeService(
                metadata -> {
                    CandidateFacts facts = facts(metadata.generatorData().caseId());
                    ResolvedKnownTrigger trigger = new ResolvedKnownTrigger(
                            "",
                            new TargetTest("p.T", "fails"),
                            "known patch text",
                            "f".repeat(64));
                    return new CohortFreezeService.StaticAssessment(
                            facts,
                            java.util.Optional.of(new CohortFreezeService.PreparedCase(
                                    metadata, trigger, "MIT", "17", tempDir)));
                },
                prepared -> new Result.Eligible(List.of(
                        new DynamicCaseQualifier.Stage("probe", "PASSED", 1))),
                prepared -> new Selection(List.of(), List.of()),
                new BenchmarkArtifacts());
    }

    private static List<CaseMetadata> cases(int count) {
        List<CaseMetadata> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "case-" + i;
            result.add(new CaseMetadata(
                    new GeneratorData(
                            id,
                            "https://github.com/o/r.git",
                            "https://github.com/o/r/issues/" + (i + 1),
                            "Issue " + i,
                            "Body " + i,
                            Integer.toHexString(i + 1).repeat(40).substring(0, 40)),
                    new OracleData(
                            Integer.toHexString(i + 7).repeat(40).substring(0, 40),
                            "known patch text",
                            List.of(new TargetTest("p.T", "fails"))),
                    new StaticMetadata(true, true, true, true)));
        }
        return result;
    }

    private static CandidateFacts facts(String id) {
        return new CandidateFacts(
                id, true, true, Set.of(17, 21), false, true, true, true, true);
    }
}

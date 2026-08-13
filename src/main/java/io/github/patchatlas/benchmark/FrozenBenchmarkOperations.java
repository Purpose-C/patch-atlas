package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPolicyInspection;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import io.github.patchatlas.benchmark.KnownTriggerResolver.ResolvedKnownTrigger;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FormalReplayCoordinator;
import io.github.patchatlas.run.GatedCandidate;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.RunSubmission;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Production Operations for the formal harness. Calibration may read Oracle metadata;
 * Agent generation still goes through GeneratorContextMaterializer only.
 */
public final class FrozenBenchmarkOperations implements FormalBenchmarkRunner.Operations {

    private final Path artifactsRoot;
    private final BenchmarkArtifacts artifacts;
    private final CalibrationOracleReader oracleReader;
    private final GeneratorContextMaterializer materializer;
    private final KnownTriggerResolver triggerResolver;
    private final PostgresRunStore runStore;
    private final FormalReplayCoordinator replayCoordinator;
    private final BenchmarkEvidenceExporter exporter;
    private final Map<String, CaseMetadata> metadataById;
    private final String owner;
    private final Duration leaseDuration;

    public FrozenBenchmarkOperations(
            Path artifactsRoot,
            BenchmarkArtifacts artifacts,
            CalibrationOracleReader oracleReader,
            GeneratorContextMaterializer materializer,
            KnownTriggerResolver triggerResolver,
            PostgresRunStore runStore,
            FormalReplayCoordinator replayCoordinator,
            BenchmarkEvidenceExporter exporter,
            List<CaseMetadata> metadata,
            String owner,
            Duration leaseDuration) {
        this.artifactsRoot = Objects.requireNonNull(artifactsRoot, "artifactsRoot").toAbsolutePath().normalize();
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.oracleReader = Objects.requireNonNull(oracleReader, "oracleReader");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.triggerResolver = Objects.requireNonNull(triggerResolver, "triggerResolver");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.replayCoordinator = Objects.requireNonNull(replayCoordinator, "replayCoordinator");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.metadataById = Objects.requireNonNull(metadata, "metadata").stream()
                .collect(Collectors.toUnmodifiableMap(
                        item -> item.generatorData().caseId(), Function.identity()));
        this.owner = Objects.requireNonNull(owner, "owner");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    }

    @Override
    public UUID launchCalibration(CohortCase cohortCase) {
        try {
            Path caseDirectory = caseDirectory(cohortCase);
            GeneratorContextMetadata context =
                    artifacts.readGeneratorContext(caseDirectory.resolve("generator-context.json"));
            OracleMetadata oracle = oracleReader.read(caseDirectory.resolve("oracle.json"));
            CaseMetadata metadata = requireMetadata(cohortCase.caseId());
            requireIssueDigest(context, metadata);
            String patch = metadata.oracleData().knownTriggerPatch();
            if (patch == null || !BenchmarkArtifacts.sha256(patch).equals(oracle.knownTriggerPatchSha256())) {
                throw new IllegalStateException("known trigger digest mismatch for " + cohortCase.caseId());
            }
            ResolvedKnownTrigger trigger = triggerResolver
                    .resolve(patch, metadata.oracleData().targetCandidates())
                    .orElseThrow(() -> new IllegalStateException(
                            "known trigger did not pass patch policy for " + cohortCase.caseId()));
            if (!trigger.targetTest().className().equals(oracle.targetClass())
                    || !trigger.targetTest().methodName().equals(oracle.targetMethod())) {
                throw new IllegalStateException("known trigger target mismatch for " + cohortCase.caseId());
            }
            CandidateDraft draft = new CandidateDraft(trigger.patchText(), trigger.targetTest());
            MavenExecutionPolicy policy =
                    new MavenExecutionPolicy(cohortCase.javaVersion(), MavenNetworkMode.OFFLINE);
            PatchPolicyInspection inspection =
                    PatchGate.inspect(cohortCase.modulePath(), draft, policy);
            if (!(inspection instanceof PatchPolicyInspection.Accepted accepted)) {
                throw new IllegalStateException("known trigger rejected by patch gate");
            }
            GatedCandidate gated = GatedCandidate.afterSuccessfulGate(
                    draft,
                    new PatchPreparationResult.PreparedCandidate(
                            Path.of("."),
                            cohortCase.modulePath(),
                            draft.targetTest(),
                            accepted.command()));
            var claimed = runStore.startCalibration(
                    submission(cohortCase, context, metadata, oracle, List.of()),
                    gated,
                    owner,
                    leaseDuration);
            replayCoordinator.run(claimed, owner);
            return claimed.runId();
        } catch (IOException ex) {
            throw new IllegalStateException("calibration launch failed", ex);
        }
    }

    @Override
    public UUID launchAgent(CohortCase cohortCase) {
        try {
            Path caseDirectory = caseDirectory(cohortCase);
            GeneratorContextMetadata context =
                    artifacts.readGeneratorContext(caseDirectory.resolve("generator-context.json"));
            OracleMetadata oracle = oracleReader.read(caseDirectory.resolve("oracle.json"));
            CaseMetadata metadata = requireMetadata(cohortCase.caseId());
            requireIssueDigest(context, metadata);
            List<SourceSnapshot> snapshots = materializer.materialize(
                    context, cohortCase.repositoryUrl(), cohortCase.caseId());
            return runStore.submitAgentBenchmark(
                    submission(cohortCase, context, metadata, oracle, snapshots));
        } catch (IOException ex) {
            throw new IllegalStateException("agent launch failed", ex);
        }
    }

    @Override
    public Path exportEvidence(Cohort cohort, List<io.github.patchatlas.run.RunDetailView> details)
            throws IOException {
        exporter.export(
                cohort,
                BenchmarkRunReader.toCaseResults(cohort, details),
                artifactsRoot.resolve("protocol.json"),
                artifactsRoot);
        return artifactsRoot.resolve("results.json");
    }

    private Path caseDirectory(CohortCase cohortCase) {
        return artifactsRoot.resolve("cases")
                .resolve("%d-%s".formatted(cohortCase.position(), cohortCase.caseId()));
    }

    private CaseMetadata requireMetadata(String caseId) {
        CaseMetadata metadata = metadataById.get(caseId);
        if (metadata == null) {
            throw new IllegalStateException("gitbug metadata missing for " + caseId);
        }
        return metadata;
    }

    private static void requireIssueDigest(GeneratorContextMetadata context, CaseMetadata metadata) {
        String actual = BenchmarkArtifacts.issueContentSha256(
                metadata.generatorData().issueTitle(), metadata.generatorData().issueBody());
        if (!actual.equals(context.issueContentSha256())) {
            throw new IllegalStateException("issue digest mismatch for " + context.caseId());
        }
    }

    private static RunSubmission submission(
            CohortCase cohortCase,
            GeneratorContextMetadata context,
            CaseMetadata metadata,
            OracleMetadata oracle,
            List<SourceSnapshot> snapshots) {
        return new RunSubmission(
                VerificationMode.HISTORICAL,
                cohortCase.caseId(),
                cohortCase.repositoryUrl(),
                cohortCase.license(),
                context.issueUrl(),
                metadata.generatorData().issueTitle(),
                metadata.generatorData().issueBody(),
                context.buggyRevision(),
                oracle.fixedRevision(),
                cohortCase.modulePath(),
                cohortCase.javaVersion(),
                MavenNetworkMode.OFFLINE,
                snapshots);
    }
}

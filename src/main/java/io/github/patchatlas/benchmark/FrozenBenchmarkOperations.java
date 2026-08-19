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
import io.github.patchatlas.benchmark.LocalizationCoverageEvaluator.Score;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.ArmCaseFact;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.FirstRoundRejectionLog;
import io.github.patchatlas.benchmark.ThreeArmEvidenceExporter.LocatingCost;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FormalReplayCoordinator;
import io.github.patchatlas.run.GatedCandidate;
import io.github.patchatlas.run.Issue2TestWorker;
import io.github.patchatlas.run.LeaseHeartbeatReplayRunSession;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.ReplayRunSession;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunSubmission;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final BenchmarkGitWorkspace git;
    private final RepairGroundTruthExtractor groundTruthExtractor = new RepairGroundTruthExtractor();
    private final LocalizationCoverageEvaluator coverageEvaluator = new LocalizationCoverageEvaluator();
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
            BenchmarkGitWorkspace git,
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
        this.git = Objects.requireNonNull(git, "git");
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
            try (ReplayRunSession session = LeaseHeartbeatReplayRunSession.open(
                    runStore,
                    claimed,
                    owner,
                    leaseDuration,
                    Issue2TestWorker.DEFAULT_HEARTBEAT)) {
                replayCoordinator.run(claimed, session);
            }
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
    public UUID launchAgent(CohortCase cohortCase, ContextOrigin origin) {
        try {
            Path caseDirectory = caseDirectory(cohortCase);
            GeneratorContextMetadata context =
                    artifacts.readGeneratorContext(caseDirectory.resolve("generator-context.json"));
            OracleMetadata oracle = oracleReader.read(caseDirectory.resolve("oracle.json"));
            CaseMetadata metadata = requireMetadata(cohortCase.caseId());
            requireIssueDigest(context, metadata);
            return runStore.submitAgentBenchmark(
                    submission(
                            cohortCase,
                            context,
                            metadata,
                            oracle,
                            snapshotsForOrigin(origin),
                            origin));
        } catch (IOException ex) {
            throw new IllegalStateException("agent launch failed", ex);
        }
    }

    static List<SourceSnapshot> snapshotsForOrigin(ContextOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        if (origin == ContextOrigin.PINNED) {
            throw new IllegalArgumentException("arm launch cannot use pinned snapshots");
        }
        return List.of();
    }

    @Override
    public UUID launchDiagnostic() {
        return launchDiagnostic(ContextOrigin.HEURISTIC);
    }

    @Override
    public UUID launchDiagnostic(ContextOrigin origin) {
        String issueTitle =
                "SpringMvcContract warns about unwrapped parameters when @GetMapping has a single URI parameter";
        String issueBody =
                """
                When a @GetMapping method has a single URI-typed parameter (e.g., @PathVariable),
                SpringMvcContract incorrectly treats it as an unwrapped parameter and logs a warning:
                "OpenFeign Warning: ... is not annotated". The method should be accepted without warning
                because URI parameters are valid and do not require explicit annotations.""";
        CaseManifest.GeneratorContext generatorContext = new CaseManifest.GeneratorContext(
                "scof-1326-diagnostic",
                "https://github.com/spring-cloud/spring-cloud-openfeign",
                "Apache-2.0",
                "https://github.com/spring-cloud/spring-cloud-openfeign/issues/1326",
                "3f6cd2eb9b5a9675a3b5fd0a0987ad8cfc3e8398",
                "spring-cloud-openfeign-core",
                "17");
        RunSubmission diagnostic = new RunSubmission(
                VerificationMode.HISTORICAL,
                generatorContext.caseId(),
                generatorContext.repositoryUrl(),
                generatorContext.license(),
                generatorContext.issueUrl(),
                issueTitle,
                issueBody,
                generatorContext.buggyRevision(),
                "a91d8f565ed3682b9bc363f9f36745d30957c09d",
                generatorContext.modulePath(),
                generatorContext.javaVersion(),
                MavenNetworkMode.ONLINE,
                List.of(),
                origin);
        return runStore.submitDiagnostic(diagnostic);
    }

    @Override
    public Path exportEvidence(Cohort cohort, List<RunDetailView> details)
            throws IOException {
        List<Score> coverage = new ArrayList<>(details.size());
        for (int i = 0; i < details.size(); i++) {
            coverage.add(coverageFor(cohort.cases().get(i), details.get(i)));
        }
        exporter.export(
                cohort,
                BenchmarkRunReader.toCaseResults(cohort, details),
                artifactsRoot.resolve("protocol.json"),
                artifactsRoot,
                coverage);
        return artifactsRoot.resolve("results.json");
    }

    @Override
    public Path exportThreeArmEvidence(Cohort cohort, List<ThreeArmRun> runs) throws IOException {
        Objects.requireNonNull(cohort, "cohort");
        Objects.requireNonNull(runs, "runs");
        if (runs.size() != 18) {
            throw new IllegalArgumentException("expected 18 three-arm runs, got " + runs.size());
        }
        Path outputDir = artifactsRoot.resolveSibling("batch5b-three-arm");
        List<ArmCaseFact> facts = new ArrayList<>(18);
        for (ThreeArmRun run : runs) {
            CohortCase cohortCase = requireCohortCase(cohort, run.detail().caseId());
            Score coverage = coverageFromRunSnapshots(cohortCase, run.detail());
            LocatingCost locating = ThreeArmLocatingCosts.from(
                    run.origin(),
                    run.detail().locatingUsage(),
                    runStore.loadLocatingTrace(run.detail().runId()));
            facts.add(toArmFact(cohortCase, run, coverage, locating));
        }
        new ThreeArmEvidenceExporter(artifacts).export(
                cohort,
                outputDir.resolve("protocol.json"),
                outputDir.resolve("preregistered-criteria.json"),
                facts,
                artifacts.readJson(outputDir.resolve("generation-rejections.json"), FirstRoundRejectionLog.class),
                outputDir);
        return outputDir.resolve("results.json");
    }

    static Set<String> selectedPathsFromSnapshots(List<SourceSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Set.of();
        }
        return snapshots.stream()
                .map(SourceSnapshot::relativePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Score coverageFromRunSnapshots(CohortCase cohortCase, RunDetailView detail) throws IOException {
        Set<String> selected = selectedPathsFromSnapshots(
                runStore.loadGenerationInput(detail.runId()).sourceSnapshots());
        if (detail.mode() == VerificationMode.LIVE) {
            return coverageEvaluator.score(detail.state(), detail.mode(), null, selected);
        }
        Path workspace = checkoutBuggy(cohortCase, detail.input().buggyRevision());
        RepairGroundTruthExtractor.Result truth = groundTruthExtractor.extract(
                workspace,
                detail.input().buggyRevision(),
                detail.input().fixedRevision(),
                cohortCase.modulePath());
        return coverageEvaluator.score(detail.state(), detail.mode(), truth, selected);
    }

    private static ArmCaseFact toArmFact(
            CohortCase cohortCase, ThreeArmRun run, Score coverage, LocatingCost locating) {
        RunDetailView detail = run.detail();
        return new ArmCaseFact(
                cohortCase.position(),
                cohortCase.caseId(),
                run.origin(),
                detail.runId(),
                detail.state(),
                detail.verdict(),
                detail.failure().map(failure -> failure.category().name()),
                detail.generation().attemptCount(),
                detail.generation().modelProvider() == null ? "" : detail.generation().modelProvider(),
                detail.generation().modelName() == null ? "" : detail.generation().modelName(),
                detail.generation().inputTokens(),
                detail.generation().outputTokens(),
                detail.generation().totalTokens(),
                coverage,
                locating);
    }

    private static CohortCase requireCohortCase(Cohort cohort, String caseId) {
        return cohort.cases().stream()
                .filter(item -> item.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("cohort missing case " + caseId));
    }

    private Score coverageFor(CohortCase cohortCase, RunDetailView detail) throws IOException {
        Set<String> selected = selectedPaths(detail.runId(), cohortCase);
        if (detail.mode() == VerificationMode.LIVE) {
            return coverageEvaluator.score(detail.state(), detail.mode(), null, selected);
        }
        Path workspace = checkoutBuggy(cohortCase, detail.input().buggyRevision());
        RepairGroundTruthExtractor.Result truth = groundTruthExtractor.extract(
                workspace,
                detail.input().buggyRevision(),
                detail.input().fixedRevision(),
                cohortCase.modulePath());
        return coverageEvaluator.score(detail.state(), detail.mode(), truth, selected);
    }

    private Path checkoutBuggy(CohortCase cohortCase, String buggyRevision) throws IOException {
        BenchmarkGitWorkspace.CheckoutResult checkout =
                git.checkout(cohortCase.repositoryUrl(), buggyRevision, cohortCase.caseId() + "-coverage");
        if (!(checkout instanceof BenchmarkGitWorkspace.CheckoutResult.Success success)) {
            throw new IOException("checkout failed for coverage: " + cohortCase.caseId());
        }
        return success.workspace();
    }

    private Set<String> selectedPaths(UUID runId, CohortCase cohortCase) throws IOException {
        List<SourceSnapshot> snapshots = runStore.loadGenerationInput(runId).sourceSnapshots();
        if (!snapshots.isEmpty()) {
            return snapshots.stream()
                    .map(SourceSnapshot::relativePath)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Path contextFile = caseDirectory(cohortCase).resolve("generator-context.json");
        if (!Files.isRegularFile(contextFile)) {
            return Set.of();
        }
        return artifacts.readGeneratorContext(contextFile).sources().stream()
                .map(BenchmarkArtifacts.SourceReference::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
        return submission(cohortCase, context, metadata, oracle, snapshots, ContextOrigin.HEURISTIC);
    }

    private static RunSubmission submission(
            CohortCase cohortCase,
            GeneratorContextMetadata context,
            CaseMetadata metadata,
            OracleMetadata oracle,
            List<SourceSnapshot> snapshots,
            ContextOrigin origin) {
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
                snapshots,
                origin);
    }
}

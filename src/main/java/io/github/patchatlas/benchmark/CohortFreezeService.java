package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.ExcludedSource;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.OracleMetadata;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.ProbeAudit;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Role;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SelectionAudit;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.SourceReference;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.StageAudit;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.StaticExclusion;
import io.github.patchatlas.benchmark.BuggyOnlyGeneratorContextBuilder.Selection;
import io.github.patchatlas.benchmark.FrozenCohortSelector.CandidateFacts;
import io.github.patchatlas.benchmark.FrozenCohortSelector.RankedCandidate;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import io.github.patchatlas.benchmark.KnownTriggerResolver.ResolvedKnownTrigger;
import io.github.patchatlas.repository.CaseManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Frozen six-case cohort selector; dynamic results only decide eligibility. */
public final class CohortFreezeService {

    private static final String RULES = """
            dataset=gitbug-java@fe986fb7919be62c2a6f611ee16659e849646798
            selector=task018-v1
            seed=cc279be0a2cfe38a327d24d828a49b8425ae37e7
            max_dynamic_probes=18
            cohort_size=6
            role_split=1-3:calibration,4-6:agent
            java_preference=17,21
            """;

    record StaticAssessment(CandidateFacts facts, Optional<PreparedCase> prepared) {
        StaticAssessment {
            Objects.requireNonNull(facts, "facts");
            prepared = Objects.requireNonNull(prepared, "prepared");
        }
    }

    record PreparedCase(
            CaseMetadata metadata,
            ResolvedKnownTrigger trigger,
            String license,
            String javaVersion,
            Path pristineBuggyWorkspace) {
        PreparedCase {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(trigger, "trigger");
            BenchmarkArtifacts.requireText(license, "license");
            if (!javaVersion.equals("17") && !javaVersion.equals("21")) {
                throw new IllegalArgumentException("javaVersion must be 17 or 21");
            }
            Objects.requireNonNull(pristineBuggyWorkspace, "pristineBuggyWorkspace");
        }
    }

    @FunctionalInterface
    interface StaticPort {
        StaticAssessment assess(CaseMetadata metadata);
    }

    @FunctionalInterface
    interface DynamicPort {
        DynamicCaseQualifier.Result qualify(PreparedCase prepared);
    }

    @FunctionalInterface
    interface ContextPort {
        Selection build(PreparedCase prepared) throws IOException;
    }

    public record FreezeResult(Cohort cohort, SelectionAudit audit) {
        public FreezeResult {
            Objects.requireNonNull(cohort, "cohort");
            Objects.requireNonNull(audit, "audit");
        }
    }

    private final StaticPort staticPort;
    private final DynamicPort dynamicPort;
    private final ContextPort contextPort;
    private final BenchmarkArtifacts artifacts;
    private final FrozenCohortSelector selector;

    public CohortFreezeService(
            BenchmarkGitWorkspace git,
            RepositoryStaticInspector repositoryInspector,
            KnownTriggerResolver triggerResolver,
            DynamicCaseQualifier qualifier,
            BuggyRepositoryReader repositoryReader,
            BuggyOnlyGeneratorContextBuilder contextBuilder) {
        this(
                productionStaticPort(git, repositoryInspector, triggerResolver),
                prepared -> qualifier.qualify(dynamicInput(prepared)),
                prepared -> buildContext(prepared, repositoryReader, contextBuilder),
                new BenchmarkArtifacts());
    }

    CohortFreezeService(
            StaticPort staticPort,
            DynamicPort dynamicPort,
            ContextPort contextPort,
            BenchmarkArtifacts artifacts) {
        this.staticPort = Objects.requireNonNull(staticPort, "staticPort");
        this.dynamicPort = Objects.requireNonNull(dynamicPort, "dynamicPort");
        this.contextPort = Objects.requireNonNull(contextPort, "contextPort");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.selector = new FrozenCohortSelector(
                BenchmarkArtifacts.DATASET_REVISION, BenchmarkArtifacts.SEED);
    }

    public FreezeResult freeze(List<CaseMetadata> metadata, Path outputDirectory) throws IOException {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        ensureUniqueCaseIds(metadata);

        Map<String, PreparedCase> preparedById = new HashMap<>();
        List<CandidateFacts> facts = new ArrayList<>(metadata.size());
        for (CaseMetadata item : metadata) {
            StaticAssessment assessment = staticPort.assess(item);
            if (!assessment.facts().caseId().equals(item.generatorData().caseId())) {
                throw new IllegalStateException("static assessment caseId mismatch");
            }
            facts.add(assessment.facts());
            assessment.prepared().ifPresent(prepared ->
                    preparedById.put(item.generatorData().caseId(), prepared));
        }

        FrozenCohortSelector.StaticSelection staticSelection = selector.select(facts);
        List<StaticExclusion> staticExclusions = staticSelection.exclusions().stream()
                .map(excluded -> new StaticExclusion(
                        excluded.caseId(), excluded.code().name()))
                .toList();
        List<ProbeAudit> probeAudits = new ArrayList<>();
        List<RankedCandidate> eligible = new ArrayList<>(6);

        List<RankedCandidate> queue = staticSelection.probeQueue();
        for (int i = 0; i < queue.size() && eligible.size() < 6; i++) {
            RankedCandidate ranked = queue.get(i);
            PreparedCase prepared = Optional.ofNullable(preparedById.get(ranked.caseId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "eligible case lacks prepared facts: " + ranked.caseId()));
            Instant started = Instant.now();
            DynamicCaseQualifier.Result result = dynamicPort.qualify(prepared);
            Instant finished = Instant.now();
            String resultCode;
            if (result instanceof DynamicCaseQualifier.Result.Eligible) {
                eligible.add(ranked);
                resultCode = "ELIGIBLE";
            } else {
                resultCode = ((DynamicCaseQualifier.Result.Excluded) result).code().name();
            }
            probeAudits.add(new ProbeAudit(
                    i + 1,
                    ranked.caseId(),
                    started,
                    finished,
                    resultCode,
                    result.stages().stream()
                            .map(stage -> new StageAudit(
                                    stage.name(), stage.result(), stage.durationMs()))
                            .toList()));
        }

        SelectionAudit audit = new SelectionAudit(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                FrozenCohortSelector.MAX_DYNAMIC_PROBES,
                staticExclusions,
                probeAudits);
        artifacts.write(outputDirectory.resolve("selection-audit.json"), audit);
        if (eligible.size() != 6) {
            throw new IllegalStateException(
                    "dynamic qualification produced " + eligible.size() + " eligible cases; expected 6");
        }

        List<CohortCase> cases = new ArrayList<>(6);
        for (int i = 0; i < eligible.size(); i++) {
            int position = i + 1;
            RankedCandidate ranked = eligible.get(i);
            PreparedCase prepared = preparedById.get(ranked.caseId());
            var generator = prepared.metadata().generatorData();
            CohortCase cohortCase = new CohortCase(
                    position,
                    position <= 3 ? Role.CALIBRATION : Role.AGENT_BENCHMARK,
                    ranked.caseId(),
                    ranked.sortKey(),
                    generator.repositoryUrl(),
                    generator.issueUrl(),
                    prepared.license(),
                    prepared.trigger().modulePath(),
                    prepared.javaVersion());
            cases.add(cohortCase);
            writeCaseMetadata(outputDirectory, cohortCase, prepared);
        }

        String cohortSha = BenchmarkArtifacts.cohortSha256(cases);
        Cohort cohort = new Cohort(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                BenchmarkArtifacts.sha256(RULES),
                cohortSha,
                cases,
                List.of());
        artifacts.write(outputDirectory.resolve("cohort.json"), cohort);
        return new FreezeResult(cohort, audit);
    }

    private void writeCaseMetadata(
            Path outputDirectory, CohortCase cohortCase, PreparedCase prepared) throws IOException {
        Selection context = contextPort.build(prepared);
        var generator = prepared.metadata().generatorData();
        Path caseDirectory = outputDirectory.resolve("cases")
                .resolve("%d-%s".formatted(cohortCase.position(), cohortCase.caseId()));
        GeneratorContextMetadata generatorMetadata = new GeneratorContextMetadata(
                cohortCase.caseId(),
                generator.issueUrl(),
                BenchmarkArtifacts.issueContentSha256(
                        generator.issueTitle(), generator.issueBody()),
                generator.buggyRevision(),
                context.selected().stream()
                        .map(source -> new SourceReference(
                                source.snapshot().relativePath(),
                                source.blobId(),
                                source.contentSha256(),
                                source.reason().name()))
                        .toList(),
                context.excluded().stream()
                        .map(source -> new ExcludedSource(
                                source.relativePath(), source.reason().name()))
                        .toList());
        OracleMetadata oracle = new OracleMetadata(
                cohortCase.caseId(),
                prepared.metadata().oracleData().fixedRevision(),
                prepared.trigger().targetTest().className(),
                prepared.trigger().targetTest().methodName(),
                prepared.trigger().patchSha256());
        artifacts.write(caseDirectory.resolve("generator-context.json"), generatorMetadata);
        artifacts.write(caseDirectory.resolve("oracle.json"), oracle);
    }

    private static StaticPort productionStaticPort(
            BenchmarkGitWorkspace git,
            RepositoryStaticInspector repositoryInspector,
            KnownTriggerResolver triggerResolver) {
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(repositoryInspector, "repositoryInspector");
        Objects.requireNonNull(triggerResolver, "triggerResolver");
        return metadata -> {
            var generator = metadata.generatorData();
            var source = metadata.staticMetadata();
            Optional<ResolvedKnownTrigger> trigger = triggerResolver.resolve(
                    metadata.oracleData().knownTriggerPatch(),
                    metadata.oracleData().targetCandidates());
            boolean preliminary = source.metadataValid()
                    && source.mavenBuild()
                    && source.issueAvailable()
                    && source.javaTestChangePresent()
                    && trigger.isPresent();
            if (!preliminary) {
                return new StaticAssessment(
                        new CandidateFacts(
                                generator.caseId(),
                                source.metadataValid(),
                                source.mavenBuild(),
                                Set.of(17, 21),
                                false,
                                source.issueAvailable(),
                                source.javaTestChangePresent(),
                                true,
                                trigger.isPresent()),
                        Optional.empty());
            }

            BenchmarkGitWorkspace.CheckoutResult checkout = git.checkout(
                    generator.repositoryUrl(),
                    generator.buggyRevision(),
                    generator.caseId() + "-static");
            if (!(checkout instanceof BenchmarkGitWorkspace.CheckoutResult.Success success)) {
                return new StaticAssessment(
                        new CandidateFacts(
                                generator.caseId(),
                                false,
                                true,
                                Set.of(),
                                false,
                                true,
                                true,
                                false,
                                true),
                        Optional.empty());
            }
            RepositoryStaticInspector.RepositoryFacts repoFacts =
                    repositoryInspector.inspect(success.workspace());
            boolean complete = repoFacts.inspectionComplete();
            CandidateFacts candidateFacts = new CandidateFacts(
                    generator.caseId(),
                    complete,
                    true,
                    repoFacts.supportedJavaVersions(),
                    repoFacts.snapshotDependencyPresent(),
                    true,
                    true,
                    repoFacts.licenseSpdx().isPresent(),
                    true);
            Optional<PreparedCase> prepared = complete
                            && !repoFacts.snapshotDependencyPresent()
                            && repoFacts.licenseSpdx().isPresent()
                            && (!repoFacts.supportedJavaVersions().isEmpty())
                    ? Optional.of(new PreparedCase(
                            metadata,
                            trigger.orElseThrow(),
                            repoFacts.licenseSpdx().orElseThrow(),
                            preferredJava(repoFacts.supportedJavaVersions()),
                            success.workspace()))
                    : Optional.empty();
            return new StaticAssessment(candidateFacts, prepared);
        };
    }

    private static Selection buildContext(
            PreparedCase prepared,
            BuggyRepositoryReader repositoryReader,
            BuggyOnlyGeneratorContextBuilder contextBuilder) throws IOException {
        var generator = prepared.metadata().generatorData();
        CaseManifest.GeneratorContext context = new CaseManifest.GeneratorContext(
                generator.caseId(),
                generator.repositoryUrl(),
                prepared.license(),
                generator.issueUrl(),
                generator.buggyRevision(),
                prepared.trigger().modulePath(),
                prepared.javaVersion());
        return contextBuilder.build(
                context,
                generator.issueTitle(),
                generator.issueBody(),
                repositoryReader.readJavaFiles(
                        prepared.pristineBuggyWorkspace(), generator.buggyRevision()));
    }

    private static DynamicCaseQualifier.Input dynamicInput(PreparedCase prepared) {
        var generator = prepared.metadata().generatorData();
        var oracle = prepared.metadata().oracleData();
        return new DynamicCaseQualifier.Input(
                generator.caseId(),
                generator.repositoryUrl(),
                generator.buggyRevision(),
                oracle.fixedRevision(),
                prepared.trigger().modulePath(),
                prepared.trigger().targetTest(),
                prepared.trigger().patchText(),
                prepared.javaVersion());
    }

    private static String preferredJava(Set<Integer> supported) {
        if (supported.contains(17)) {
            return "17";
        }
        if (supported.contains(21)) {
            return "21";
        }
        throw new IllegalArgumentException("no supported Java version");
    }

    private static void ensureUniqueCaseIds(List<CaseMetadata> metadata) {
        Set<String> ids = new HashSet<>();
        for (CaseMetadata item : metadata) {
            Objects.requireNonNull(item, "caseMetadata");
            if (!ids.add(item.generatorData().caseId())) {
                throw new IllegalArgumentException(
                        "duplicate caseId: " + item.generatorData().caseId());
            }
        }
    }

}

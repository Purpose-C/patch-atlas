package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.GeneratorConfiguration;
import io.github.patchatlas.agent.GeneratorIdentity;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.agent.SpringAiTestGenerator;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.run.CandidateGenerationCoordinator;
import io.github.patchatlas.run.EngineRunReplayer;
import io.github.patchatlas.run.FormalReplayCoordinator;
import io.github.patchatlas.run.GitCloneWorkspaceFetcher;
import io.github.patchatlas.run.Issue2TestWorker;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.TempCandidateWorkspaceFactory;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Explicit {@code -Dgroups=model} entry for calibrate / agent-N / verify.
 * Missing shared prerequisites fail the test; they are not skipped.
 */
@Tag("model")
class FormalBenchmarkHarnessTest {

    private static final String FROZEN_MODEL = "agnes-2.5-flash";
    private static final String OWNER = "benchmark-harness";

    @Test
    void runClosedFormalAction() throws Exception {
        String action = requiredEnv("PATCHATLAS_BENCHMARK_ACTION");
        String parsed = BenchmarkActions.parseAction(action);
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path artifactsRoot = projectRoot.resolve("benchmark-cases/task018");
        Path workspaceRoot = Path.of(requiredEnv("PATCHATLAS_WORKER_WORKSPACE_ROOT"))
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("workspace root must be an existing directory");
        }
        Path gitbugRoot = Path.of(requiredEnv("PATCHATLAS_GITBUG_JAVA_ROOT")).toRealPath();
        Path bugsDirectory = gitbugRoot.resolve("data/bugs");
        if (!Files.isDirectory(bugsDirectory)) {
            throw new IllegalStateException("GitBug-Java data/bugs directory is missing");
        }

        DataSource dataSource = dataSource();
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        Cohort cohort = artifacts.readCohort(artifactsRoot.resolve("cohort.json"));
        BenchmarkPreflight preflight = new BenchmarkPreflight(dataSource, workspaceRoot);
        PostgresRunStore runStore = new PostgresRunStore(dataSource);
        PatchGate patchGate = new PatchGate(workspaceRoot);
        DockerSandboxRunner sandbox = new DockerSandboxRunner(DockerSandboxConfig.defaults(
                workspaceRoot, workspaceRoot.resolve(".patch-atlas-cache/maven")));
        SideReplayRunner sideReplay = new SideReplayRunner(sandbox, workspaceRoot);
        DependencyWarmupRunner warmup = new DependencyWarmupRunner(sandbox, workspaceRoot);
        TempCandidateWorkspaceFactory workspaces = new TempCandidateWorkspaceFactory(
                workspaceRoot, new GitCloneWorkspaceFetcher());
        TestGenerator generator = parsed.startsWith("agent-")
                ? openAiGenerator()
                : FakeTestGenerator.of(new GenerationResult.GenerationCallFailure(
                        CallFailureCategory.MODEL_CONFIGURATION_ERROR, "model not required"));
        CandidateGenerationCoordinator generation = new CandidateGenerationCoordinator(
                generator, patchGate, workspaces, warmup, sideReplay);
        FormalReplayCoordinator replay = new FormalReplayCoordinator(
                runStore,
                patchGate,
                workspaces,
                warmup,
                new EngineRunReplayer(sideReplay),
                Issue2TestWorker.DEFAULT_LEASE,
                Issue2TestWorker.DEFAULT_HEARTBEAT);
        Issue2TestWorker worker = new Issue2TestWorker(
                runStore,
                generation,
                replay,
                Issue2TestWorker.DEFAULT_LEASE,
                Issue2TestWorker.DEFAULT_HEARTBEAT);
        FormalBenchmarkRunner.Store store = new PostgresBenchmarkStore(runStore);
        FrozenBenchmarkOperations operations = new FrozenBenchmarkOperations(
                artifactsRoot,
                artifacts,
                new CalibrationOracleReader(),
                new GeneratorContextMaterializer(
                        new BenchmarkGitWorkspace(projectRoot.resolve(".patch-atlas-cache/task018")),
                        new BuggyRepositoryReader()),
                new KnownTriggerResolver(),
                runStore,
                replay,
                new BenchmarkEvidenceExporter(),
                new GitBugJavaMetadataReader().read(bugsDirectory),
                OWNER,
                Issue2TestWorker.DEFAULT_LEASE);
        FormalBenchmarkRunner runner = new FormalBenchmarkRunner(
                preflight,
                store,
                operations,
                new WorkerBackedWaiter(store, worker, OWNER, Duration.ofSeconds(2)));

        FormalBenchmarkRunner.Outcome outcome = runner.execute(action, cohort);
        switch (outcome) {
            case FormalBenchmarkRunner.Outcome.PreflightFailed failed ->
                    throw new IllegalStateException("preflight failed: " + failed.reasons());
            case FormalBenchmarkRunner.Outcome.TimedOut timedOut ->
                    throw new IllegalStateException(
                            timedOut.message() + " runId=" + timedOut.runId());
            case FormalBenchmarkRunner.Outcome.Finished finished -> {
                if (finished.details().isEmpty()) {
                    throw new IllegalStateException("formal run finished without details");
                }
            }
            case FormalBenchmarkRunner.Outcome.Verified verified -> {
                if (!Files.isRegularFile(verified.output())) {
                    throw new IllegalStateException("results.json was not written");
                }
            }
        }
    }

    private static TestGenerator openAiGenerator() {
        String key = requiredEnv("OPENAI_API_KEY");
        String model = envOrDefault("PATCHATLAS_OPENAI_MODEL", FROZEN_MODEL);
        String baseUrl = envOrDefault("PATCHATLAS_OPENAI_BASE_URL", OpenAiChatModelFactory.DEFAULT_BASE_URL);
        String vendor = envOrDefault("PATCHATLAS_OPENAI_VENDOR", "openai");
        return new SpringAiTestGenerator(
                GeneratorConfiguration.identityForVendor(vendor, model),
                OpenAiChatModelFactory.create(key, model, baseUrl));
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(requiredEnv("SPRING_DATASOURCE_URL"));
        dataSource.setUser(requiredEnv("SPRING_DATASOURCE_USERNAME"));
        dataSource.setPassword(requiredEnv("SPRING_DATASOURCE_PASSWORD"));
        return dataSource;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

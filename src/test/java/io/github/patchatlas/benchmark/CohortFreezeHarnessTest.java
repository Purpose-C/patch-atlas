package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 显式 benchmark profile 入口；缺少前提时失败，不在默认测试或 CI 中执行。 */
@Tag("benchmark")
class CohortFreezeHarnessTest {

    @Test
    void freezeCohort() throws Exception {
        String configuredRoot = System.getenv("PATCHATLAS_GITBUG_JAVA_ROOT");
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException("PATCHATLAS_GITBUG_JAVA_ROOT is required");
        }
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path output = projectRoot.resolve("benchmark-cases/task018");
        if (Files.exists(output.resolve("cohort.json"))) {
            throw new IllegalStateException("frozen cohort already exists");
        }

        Path datasetRoot = Path.of(configuredRoot).toRealPath();
        assertDatasetRevision(datasetRoot);
        Path bugsDirectory = datasetRoot.resolve("data/bugs");
        if (!Files.isDirectory(bugsDirectory)) {
            throw new IllegalStateException("GitBug-Java data/bugs directory is missing");
        }

        Path cacheRoot = projectRoot.resolve(".patch-atlas-cache/task018");
        Path workspaceRoot = Files.createDirectories(cacheRoot.resolve("workspaces"));
        Path mavenCache = cacheRoot.resolve("maven");
        BenchmarkGitWorkspace git = new BenchmarkGitWorkspace(cacheRoot);
        DockerSandboxRunner warmupDocker = new DockerSandboxRunner(new DockerSandboxConfig(
                Duration.ofMinutes(10),
                64 * 1024,
                workspaceRoot,
                mavenCache,
                SandboxLimits.defaults()));
        DockerSandboxRunner replayDocker = new DockerSandboxRunner(new DockerSandboxConfig(
                Duration.ofMinutes(5),
                64 * 1024,
                workspaceRoot,
                mavenCache,
                SandboxLimits.defaults()));
        DynamicCaseQualifier qualifier = new DynamicCaseQualifier(
                git,
                new PatchGate(workspaceRoot),
                new DependencyWarmupRunner(warmupDocker, workspaceRoot),
                new SideReplayRunner(replayDocker, workspaceRoot));
        CohortFreezeService service = new CohortFreezeService(
                git,
                new RepositoryStaticInspector(),
                new KnownTriggerResolver(),
                qualifier,
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder());

        CohortFreezeService.FreezeResult result =
                service.freeze(new GitBugJavaMetadataReader().read(bugsDirectory), output);

        assertThat(result.cohort().cases()).hasSize(6);
        assertThat(output.resolve("cohort.json")).isRegularFile();
        assertThat(output.resolve("selection-audit.json")).isRegularFile();
    }

    private static void assertDatasetRevision(Path datasetRoot) throws Exception {
        try (Git git = Git.open(datasetRoot.toFile())) {
            String head = git.getRepository().resolve(Constants.HEAD).name();
            if (!BenchmarkArtifacts.DATASET_REVISION.equals(head)) {
                throw new IllegalStateException(
                        "GitBug-Java revision mismatch: expected "
                                + BenchmarkArtifacts.DATASET_REVISION
                                + ", got "
                                + head);
            }
        }
    }
}

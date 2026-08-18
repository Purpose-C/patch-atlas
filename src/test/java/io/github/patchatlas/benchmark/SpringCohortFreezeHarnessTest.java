package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Explicit benchmark-profile entry that freezes the Spring-usage cohort. */
@Tag("benchmark")
class SpringCohortFreezeHarnessTest {

    @Test
    void freezeSpringCohort() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path output = projectRoot.resolve("benchmark-cases/spring-v1");
        if (Files.exists(output.resolve("cohort.json"))
                || Files.exists(output.resolve("selection-audit.json"))) {
            throw new IllegalStateException("spring-v1 freeze output already exists");
        }

        Path gitbugRoot = gitbugRoot();
        Path bugsDirectory = gitbugRoot.resolve("data/bugs");
        if (!Files.isDirectory(bugsDirectory)) {
            throw new IllegalStateException("GitBug-Java data/bugs directory is missing");
        }

        Path cacheRoot = projectRoot.resolve(".patch-atlas-cache/spring-v1");
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
        CohortFreezeService service = CohortFreezeService.spring(
                git,
                new RepositoryStaticInspector(),
                new KnownTriggerResolver(),
                qualifier,
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder());

        var metadata = new SpringSourceMetadataLoader().load(
                projectRoot.resolve(SpringCohortFreezeRules.SCAN_PATH),
                bugsDirectory,
                projectRoot.resolve(".patch-atlas-cache/spring-source-gate/multiswe-java"),
                projectRoot.resolve(".patch-atlas-cache/spring-source-gate/polybench/spring.jsonl"));

        assertThat(metadata).hasSizeGreaterThanOrEqualTo(12);
        try {
            CohortFreezeService.FreezeResult result = service.freeze(metadata, output);
            assertThat(result.cohort().cases()).hasSizeBetween(
                    SpringCohortFreezeRules.MIN_SIZE, SpringCohortFreezeRules.TARGET_SIZE);
            assertThat(output.resolve("cohort.json")).isRegularFile();
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageContaining("eligible cases");
            assertThat(output.resolve("cohort.json")).doesNotExist();
        }
        assertThat(output.resolve("selection-audit.json")).isRegularFile();
    }

    private static Path gitbugRoot() throws Exception {
        String configured = System.getenv("PATCHATLAS_GITBUG_JAVA_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toRealPath();
        }
        Path local = Path.of(".patch-atlas-cache/GitBug-Java");
        if (Files.isDirectory(local.resolve("data/bugs"))) {
            return local.toRealPath();
        }
        throw new IllegalStateException("PATCHATLAS_GITBUG_JAVA_ROOT is required");
    }
}

package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("benchmark")
class Scof1326CacheBenchmarkTest {

    private static final String FIXED_REVISION = "a91d8f565ed3682b9bc363f9f36745d30957c09d";
    private static final String MODULE = "spring-cloud-openfeign-core";
    private static final String TEST =
            "SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn";
    private static final long COLD_FIXED_BASELINE_MILLIS = Duration.ofMinutes(9)
            .plusSeconds(41)
            .toMillis();

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void warmCacheBeatsRecordedColdBaselineWhileNetworkUseStaysExplicit() throws Exception {
        Path workspace = Path.of("samples/spring-cloud-openfeign").toRealPath();
        try (Git git = Git.open(workspace.toFile())) {
            assertThat(git.getRepository().resolve("HEAD").getName()).isEqualTo(FIXED_REVISION);
        }

        Path cache = Path.of(".patch-atlas-cache/scof-1326").toAbsolutePath();
        DockerSandboxConfig config = new DockerSandboxConfig(
                "maven:3.9-eclipse-temurin-17",
                Duration.ofMinutes(10),
                128 * 1024,
                Path.of("samples").toRealPath(),
                cache,
                new SandboxLimits(2.0, 2_147_483_648L, 512));
        DockerSandboxRunner runner = new DockerSandboxRunner(config);

        SandboxExecution warmup =
                runner.execute(workspace, new MavenDependencyWarmupCommand(MODULE, TEST));
        assertSuccessful("warmup", warmup);

        SandboxExecution warmRun = runner.execute(
                workspace, new MavenTestCommand(MODULE, TEST, MavenNetworkMode.ONLINE));
        assertSuccessful("warm execution", warmRun);

        assertThat(warmRun.networkMode()).isEqualTo(MavenNetworkMode.ONLINE);
        assertThat(warmRun.command()).doesNotContain("-o");
        assertThat(warmRun.elapsed()).isLessThanOrEqualTo(Duration.ofMinutes(6));
        assertThat(warmRun.elapsed().toMillis())
                .isLessThanOrEqualTo((long) (COLD_FIXED_BASELINE_MILLIS * 0.60));

        System.out.printf(
                "scof-1326 cache evidence: coldBaseline=%dms warmup=%dms warmRun=%dms network=%s%n",
                COLD_FIXED_BASELINE_MILLIS,
                warmup.elapsed().toMillis(),
                warmRun.elapsed().toMillis(),
                warmRun.networkMode());
    }

    private static void assertSuccessful(String phase, SandboxExecution execution) {
        assertThat(execution.status())
                .withFailMessage("%s status failed:%n%s", phase, execution.logSummary())
                .isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(execution.exitCode())
                .withFailMessage("%s command failed:%n%s", phase, execution.logSummary())
                .isZero();
    }
}

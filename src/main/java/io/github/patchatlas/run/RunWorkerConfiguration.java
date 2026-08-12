package io.github.patchatlas.run;

import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/**
 * 可选 Worker 装配与启动恢复。
 *
 * <pre>
 * patchatlas.worker.enabled=true
 * patchatlas.worker.workspace-root=/path/to/root
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(RunWorkerProperties.class)
@ConditionalOnProperty(prefix = "patchatlas.worker", name = "enabled", havingValue = "true")
public class RunWorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RunWorkerConfiguration.class);

    @Bean
    CandidateWorkspaceFactory candidateWorkspaceFactory(
            RunWorkerProperties properties, ObjectProvider<RepositoryWorkspaceFetcher> fetcher) {
        Path root = properties.getWorkspaceRoot();
        if (root == null) {
            throw new IllegalStateException(
                    "patchatlas.worker.workspace-root is required when worker is enabled");
        }
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalStateException(
                    "patchatlas.worker.workspace-root must be an existing directory: " + absolute);
        }
        RepositoryWorkspaceFetcher materializer =
                fetcher.getIfAvailable(GitCloneWorkspaceFetcher::new);
        return new TempCandidateWorkspaceFactory(absolute, materializer);
    }

    @Bean
    PatchGate patchGate(RunWorkerProperties properties) {
        Path root = Objects.requireNonNull(properties.getWorkspaceRoot(), "workspace-root")
                .toAbsolutePath()
                .normalize();
        return new PatchGate(root);
    }

    @Bean
    @Fallback
    SandboxRunner defaultSandboxRunner(RunWorkerProperties properties) {
        Path root = requireWorkspaceRoot(properties);
        return new DockerSandboxRunner(
                DockerSandboxConfig.defaults(root, root.resolve(".patch-atlas-cache/maven")));
    }

    @Bean
    SideReplayRunner sideReplayRunner(SandboxRunner sandboxRunner, RunWorkerProperties properties) {
        return new SideReplayRunner(sandboxRunner, requireWorkspaceRoot(properties));
    }

    @Bean
    DependencyWarmupRunner dependencyWarmupRunner(
            SandboxRunner sandboxRunner, RunWorkerProperties properties) {
        return new DependencyWarmupRunner(sandboxRunner, requireWorkspaceRoot(properties));
    }

    @Bean
    @Fallback
    RunReplayer defaultRunReplayer(SideReplayRunner sideReplayRunner) {
        return new EngineRunReplayer(sideReplayRunner);
    }

    @Bean
    CandidateGenerationCoordinator candidateGenerationCoordinator(
            TestGenerator generator,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            DependencyWarmupRunner dependencyWarmupRunner,
            SideReplayRunner sideReplayRunner) {
        return new CandidateGenerationCoordinator(
                generator, patchGate, workspaceFactory, dependencyWarmupRunner, sideReplayRunner);
    }

    @Bean
    FormalReplayCoordinator formalReplayCoordinator(
            PostgresRunStore store,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            DependencyWarmupRunner dependencyWarmupRunner,
            RunReplayer replayer,
            RunWorkerProperties properties) {
        return new FormalReplayCoordinator(
                store,
                patchGate,
                workspaceFactory,
                dependencyWarmupRunner,
                replayer,
                properties.getLeaseDuration(),
                properties.getHeartbeatInterval());
    }

    @Bean
    Issue2TestWorker issue2TestWorker(
            PostgresRunStore store,
            CandidateGenerationCoordinator generationCoordinator,
            FormalReplayCoordinator replayCoordinator,
            RunWorkerProperties properties) {
        return new Issue2TestWorker(
                store,
                generationCoordinator,
                replayCoordinator,
                properties.getLeaseDuration(),
                properties.getHeartbeatInterval());
    }

    @Bean
    ApplicationRunner unfinishedRunRecoveryRunner(Issue2TestWorker worker, RunWorkerProperties properties) {
        return args -> {
            String owner = properties.getOwner();
            int max = Math.max(1, properties.getStartupMaxRuns());
            int processed = 0;
            for (int i = 0; i < max; i++) {
                var details = worker.processNext(owner);
                if (details.isEmpty()) {
                    break;
                }
                processed++;
                log.info(
                        "recovered/processed run {} -> {}",
                        details.get().runId(),
                        details.get().state());
            }
            if (processed > 0) {
                log.info("startup recovery processed {} run(s) as owner={}", processed, owner);
            } else {
                log.info("startup recovery: no claimable runs (owner={})", owner);
            }
        };
    }

    private static Path requireWorkspaceRoot(RunWorkerProperties properties) {
        Path root = Objects.requireNonNull(properties.getWorkspaceRoot(), "workspace-root")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "patchatlas.worker.workspace-root must be an existing directory: " + root);
        }
        return root;
    }
}

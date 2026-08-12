package io.github.patchatlas.run;

import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.TestGenerator;
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

/**
 * 可选 Worker 装配与启动恢复。
 *
 * <pre>
 * patchatlas.worker.enabled=true
 * patchatlas.worker.workspace-root=/path/to/root
 * spring.datasource.url=...
 * # 必须提供 TestGenerator 与 RunReplayer Bean
 * </pre>
 *
 * <p>不用 {@code @ConditionalOnBean} 作类级条件——与用户配置 Bean 同时注册时会误判。
 * enabled=true 时若缺少依赖，启动直接失败并给出明确错误。
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
    Issue2TestWorker issue2TestWorker(
            PostgresRunStore store,
            TestGenerator generator,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            RunReplayer replayer,
            RunWorkerProperties properties) {
        return new Issue2TestWorker(
                store,
                generator,
                patchGate,
                workspaceFactory,
                replayer,
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
}

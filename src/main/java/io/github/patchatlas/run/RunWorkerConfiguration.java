package io.github.patchatlas.run;

import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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
@EnableScheduling
@ConditionalOnProperty(prefix = "patchatlas.worker", name = "enabled", havingValue = "true")
public class RunWorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RunWorkerConfiguration.class);

    @Bean
    @Fallback
    SandboxRunner defaultSandboxRunner(RunWorkerProperties properties) {
        Path root = requireWorkspaceRoot(properties);
        return new DockerSandboxRunner(
                DockerSandboxConfig.defaults(root, root.resolve(".patch-atlas-cache/maven")));
    }

    @Bean
    Issue2TestRuntime issue2TestRuntime(
            TestGenerator generator,
            RunWorkerProperties properties,
            SandboxRunner sandboxRunner,
            ObjectProvider<RepositoryWorkspaceFetcher> fetcher,
            ObjectProvider<SandboxExecutionObserver> observer) {
        return Issue2TestRuntime.create(
                generator,
                requireWorkspaceRoot(properties),
                sandboxRunner,
                fetcher.getIfAvailable(GitCloneWorkspaceFetcher::new),
                observer.getIfAvailable(() -> SandboxExecutionObserver.NOOP));
    }

    @Bean
    Issue2TestWorker issue2TestWorker(
            Issue2TestRuntime runtime,
            PostgresRunStore store,
            RunWorkerProperties properties) {
        return runtime.worker(store, properties.getLeaseDuration(), properties.getHeartbeatInterval());
    }

    @Bean
    RunWorkerDrain runWorkerDrain(Issue2TestWorker worker, RunWorkerProperties properties) {
        return new RunWorkerDrain(worker, properties.getOwner());
    }

    @Bean
    ApplicationRunner unfinishedRunRecoveryRunner(RunWorkerDrain drain, RunWorkerProperties properties) {
        return args -> {
            int processed = drain.drain(properties.getStartupMaxRuns());
            if (processed > 0) {
                log.info(
                        "startup recovery processed {} run(s) as owner={}",
                        processed,
                        properties.getOwner());
            } else {
                log.info("startup recovery: no claimable runs (owner={})", properties.getOwner());
            }
        };
    }

    @Bean
    WorkerPollScheduler workerPollScheduler(RunWorkerDrain drain, RunWorkerProperties properties) {
        return new WorkerPollScheduler(drain, properties);
    }

    /** fixed-delay 常驻消费；与启动 drain 共用 seam，防重入。 */
    static final class WorkerPollScheduler {
        private final RunWorkerDrain drain;
        private final RunWorkerProperties properties;

        WorkerPollScheduler(RunWorkerDrain drain, RunWorkerProperties properties) {
            this.drain = drain;
            this.properties = properties;
        }

        @Scheduled(
                initialDelayString = "${patchatlas.worker.poll-interval:2s}",
                fixedDelayString = "${patchatlas.worker.poll-interval:2s}")
        public void poll() {
            // 连续消费当前队列；空则返回。tick 内部防重入。
            drain.drain(Math.max(1, properties.getStartupMaxRuns()));
        }

        /**
         * 有界优雅关闭：先停领取，再等待当前 tick（最多 lease-duration，
         * 避免容器销毁时仍在 claim/执行）。
         */
        @EventListener(ContextClosedEvent.class)
        public void onClose() {
            Duration wait = properties.getLeaseDuration();
            if (wait == null || wait.isNegative() || wait.isZero()) {
                wait = Duration.ofSeconds(30);
            }
            // 上限避免极长租约拖死关机
            if (wait.compareTo(Duration.ofMinutes(2)) > 0) {
                wait = Duration.ofMinutes(2);
            }
            boolean idle = drain.shutdown(wait);
            if (!idle) {
                log.warn(
                        "worker shutdown: tick still running after {} (owner={})",
                        wait,
                        properties.getOwner());
            }
        }
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

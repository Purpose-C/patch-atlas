package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDataSource;

class RunWorkerConfigurationTest {

    @Test
    void workerEnabledBuildsDefaultProductionBeanGraph(@TempDir Path workspaceRoot) {
        new ApplicationContextRunner()
                .withUserConfiguration(RunWorkerConfiguration.class, ContextTestBeans.class)
                .withPropertyValues(
                        "patchatlas.worker.enabled=true",
                        "patchatlas.worker.workspace-root=" + workspaceRoot,
                        "patchatlas.worker.lease-duration=PT5M",
                        "patchatlas.worker.heartbeat-interval=PT30S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Issue2TestWorker.class);
                    assertThat(context).hasSingleBean(DependencyWarmupRunner.class);
                    assertThat(context.getBean(SandboxRunner.class))
                            .isInstanceOf(DockerSandboxRunner.class);
                    assertThat(context.getBean(RunReplayer.class))
                            .isInstanceOf(EngineRunReplayer.class);
                });
    }

    @Test
    void missingWorkspaceRootFailsFast() {
        RunWorkerProperties properties = new RunWorkerProperties();
        RunWorkerConfiguration configuration = new RunWorkerConfiguration();

        assertThatThrownBy(() -> configuration.defaultSandboxRunner(properties))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("workspace-root");
    }

    @Configuration(proxyBeanMethods = false)
    static class ContextTestBeans {

        @Bean
        PostgresRunStore postgresRunStore() {
            return new PostgresRunStore(noConnectionDataSource());
        }

        @Bean
        TestGenerator testGenerator() {
            return new FakeTestGenerator(new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.MODEL_UNAVAILABLE, "unused"));
        }

        private static DataSource noConnectionDataSource() {
            return new AbstractDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw new SQLException("test context must not open a database connection");
                }

                @Override
                public Connection getConnection(String username, String password)
                        throws SQLException {
                    throw new SQLException("test context must not open a database connection");
                }
            };
        }
    }
}

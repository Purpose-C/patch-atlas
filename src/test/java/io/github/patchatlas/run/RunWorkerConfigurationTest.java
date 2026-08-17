package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.TestGenerator;
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
                    assertThat(context).hasSingleBean(Issue2TestRuntime.class);
                    assertThat(context.getBean(SandboxRunner.class))
                            .isInstanceOf(DockerSandboxRunner.class);
                    assertThat(context.getBean(Issue2TestRuntime.class).locatingCoordinator().hasTextTools())
                            .isFalse();
                });
    }

    @Test
    void chatModelBeanWiresTextToolsIntoRuntime(@TempDir Path workspaceRoot) {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        RunWorkerConfiguration.class, ContextTestBeans.class, ChatModelTestBean.class)
                .withPropertyValues(
                        "patchatlas.worker.enabled=true",
                        "patchatlas.worker.workspace-root=" + workspaceRoot,
                        "patchatlas.worker.lease-duration=PT5M",
                        "patchatlas.worker.heartbeat-interval=PT30S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Issue2TestRuntime.class).locatingCoordinator().hasTextTools())
                            .isTrue();
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

    @Configuration(proxyBeanMethods = false)
    static class ChatModelTestBean {
        @Bean
        org.springframework.ai.chat.model.ChatModel chatModel() {
            return io.github.patchatlas.agent.OpenAiChatModelFactory.create(
                    "sk-test", "gpt-test", "http://127.0.0.1:9");
        }
    }
}

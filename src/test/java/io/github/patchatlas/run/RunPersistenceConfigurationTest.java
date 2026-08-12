package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * persistence profile 下可装配 DataSource + Flyway + PostgresRunStore；
 * 默认 profile 不创建 Store。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class RunPersistenceConfigurationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @Test
    void defaultProfileDoesNotCreateRunStoreBean() {
        SpringApplication app = new SpringApplication(io.github.patchatlas.PatchAtlasApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run()) {
            assertThat(ctx.getEnvironment().getActiveProfiles()).doesNotContain("persistence");
            assertThat(ctx.getBeanNamesForType(PostgresRunStore.class)).isEmpty();
        }
    }

    @Test
    void persistenceProfileCreatesRunStoreAndMigrates() {
        SpringApplication app = new SpringApplication(io.github.patchatlas.PatchAtlasApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("persistence");
        try (ConfigurableApplicationContext ctx = app.run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())) {
            PostgresRunStore store = ctx.getBean(PostgresRunStore.class);
            assertThat(store).isNotNull();
            // submit 依赖已迁移的表
            var id = store.submit(new RunSubmission(
                    VerificationMode.LIVE,
                    "boot",
                    "https://github.com/ex/repo.git",
                    null,
                    null,
                    "t",
                    "b",
                    "a".repeat(40),
                    null,
                    "",
                    "17",
                    MavenNetworkMode.ONLINE,
                    java.util.List.of()));
            assertThat(store.findRun(id)).isPresent();
            ReplayWorkspaceProjection projection = store.loadReplayWorkspaceProjection(id);
            assertThat(projection.executionPolicy().javaVersion()).isEqualTo("17");
            assertThat(projection.executionPolicy().networkMode())
                    .isEqualTo(MavenNetworkMode.ONLINE);
        }
    }
}

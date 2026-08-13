package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.ReplayVerdict;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** /actuator/metrics 可按白名单 tag 读取确定值。 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class RunAggregateMetricsActuatorTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @Test
    void persistenceProfileExposesTaggedCompletedCount() throws Exception {
        SpringApplication app = new SpringApplication(io.github.patchatlas.PatchAtlasApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("persistence");
        try (ConfigurableApplicationContext ctx = app.run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())) {
            insertCompletedLive();
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.find("patchatlas.run.completed")
                            .tags("mode", "live", "verdict", "valid_reproduction")
                            .functionCounter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.find("patchatlas.model.cost.estimated").meters()).isEmpty();

            int port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
            String body = RestClient.create()
                    .get()
                    .uri(
                            "http://127.0.0.1:"
                                    + port
                                    + "/actuator/metrics/patchatlas.run.completed?tag=mode:live&tag=verdict:valid_reproduction")
                    .retrieve()
                    .body(String.class);
            assertThat(body).contains("\"name\":\"patchatlas.run.completed\"");
            assertThat(body).contains("\"value\":1.0");
        }
    }

    private static void insertCompletedLive() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO verification_run (
                      id, mode, repository_url, issue_title, issue_body,
                      buggy_revision, module_path, state, version,
                      verdict, primary_stable_evidence, final_replay_round, completed_at
                    ) VALUES (
                      '%s', 'LIVE', 'https://github.com/ex/repo.git', 't', 'b',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '', 'COMPLETED', 1,
                      '%s', 'TARGET_ASSERTION_FAILURE', 1, CURRENT_TIMESTAMP
                    )
                    """
                            .formatted(UUID.randomUUID(), ReplayVerdict.VALID_REPRODUCTION.name()));
        }
    }
}

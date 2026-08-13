package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** 没有 PostgreSQL reader 时不注册 Run Aggregate Metrics。 */
class RunAggregateMetricsWiringTest {

    @Test
    void defaultProfileDoesNotRegisterRunAggregateMeters() {
        SpringApplication app = new SpringApplication(io.github.patchatlas.PatchAtlasApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run()) {
            assertThat(ctx.getBeanNamesForType(RunAggregateReader.class)).isEmpty();
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.find("patchatlas.run.completed").meters()).isEmpty();
            assertThat(registry.find("patchatlas.model.cost.estimated").meters()).isEmpty();
            assertThat(ctx.getEnvironment().getProperty("logging.structured.format.console"))
                    .isEqualTo("logstash");
        }
    }
}

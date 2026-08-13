package io.github.patchatlas.observability;

import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ObservabilityPricingProperties.class)
public class ObservabilityConfiguration {

    @Bean
    PricingSettings pricingSettings(ObservabilityPricingProperties properties) {
        return PricingSettings.from(properties);
    }

    @Bean
    SandboxExecutionObserver sandboxExecutionObserver(MeterRegistry registry) {
        return new MicrometerSandboxExecutionObserver(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.datasource", name = "url")
    RunAggregateReader runAggregateReader(DataSource dataSource) {
        return new PostgresRunAggregateReader(dataSource);
    }

    @Bean
    MeterBinder runAggregateMeters(ObjectProvider<RunAggregateReader> reader, PricingSettings pricing) {
        return registry -> {
            RunAggregateReader aggregates = reader.getIfAvailable();
            if (aggregates != null) {
                RunAggregateMeters.bind(registry, aggregates, pricing.reference());
            }
        };
    }
}

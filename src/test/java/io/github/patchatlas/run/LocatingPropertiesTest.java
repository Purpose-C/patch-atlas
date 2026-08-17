package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.LocalizationBudget;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LocatingPropertiesTest {

    @Test
    void defaultsMatchLocalizationBudgetConstants() {
        LocatingProperties properties = new LocatingProperties();
        assertThat(properties.getMaxToolCalls()).isEqualTo(LocalizationBudget.MAX_TOOL_CALLS);
        assertThat(properties.getWallClock()).isEqualTo(LocalizationBudget.WALL_CLOCK);
        LocalizationBudget budget = properties.budget();
        assertThat(budget.maxCalls()).isEqualTo(35);
        assertThat(budget.wallClock()).isEqualTo(Duration.ofMinutes(9));
    }

    @Test
    void bindsRelaxedLimitsFromConfiguration() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("patchatlas.locating.max-tool-calls", "60");
        source.put("patchatlas.locating.wall-clock", "15m");
        LocatingProperties properties = new Binder(source)
                .bind("patchatlas.locating", LocatingProperties.class)
                .get();
        assertThat(properties.getMaxToolCalls()).isEqualTo(60);
        assertThat(properties.getWallClock()).isEqualTo(Duration.ofMinutes(15));
        LocalizationBudget budget = properties.budget();
        assertThat(budget.maxCalls()).isEqualTo(60);
        assertThat(budget.wallClock()).isEqualTo(Duration.ofMinutes(15));
    }
}

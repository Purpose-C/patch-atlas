package io.github.patchatlas.run;

import io.github.patchatlas.analysis.LocalizationBudget;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code patchatlas.locating.*}：定位工具循环预算。默认与 {@link LocalizationBudget} 常数一致。 */
@ConfigurationProperties(prefix = "patchatlas.locating")
public class LocatingProperties {

    private int maxToolCalls = LocalizationBudget.MAX_TOOL_CALLS;

    private Duration wallClock = LocalizationBudget.WALL_CLOCK;

    public LocalizationBudget budget() {
        return new LocalizationBudget(maxToolCalls, wallClock, Instant.now());
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public Duration getWallClock() {
        return wallClock;
    }

    public void setWallClock(Duration wallClock) {
        this.wallClock = wallClock;
    }
}

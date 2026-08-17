package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LocalizationBudgetTest {

    private static final Instant T0 = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void remainingIsFalseAfterMaxCallsOrDeadline() {
        LocalizationBudget calls = new LocalizationBudget(2, Duration.ofMinutes(5), T0);
        assertThat(calls.remaining(T0)).isTrue();
        calls.consume();
        calls.consume();
        assertThat(calls.remaining(T0)).isFalse();
        assertThat(calls.callsExhausted()).isTrue();
        assertThat(calls.clockExhausted(T0.plus(Duration.ofMinutes(4)))).isFalse();

        LocalizationBudget clock = new LocalizationBudget(25, Duration.ofMinutes(5), T0);
        assertThat(clock.remaining(T0.plus(Duration.ofMinutes(5)))).isFalse();
        assertThat(clock.clockExhausted(T0.plus(Duration.ofMinutes(5)))).isTrue();
    }

    @Test
    void defaultsRemainTwentyFiveCallsAndFiveMinutes() {
        assertThat(LocalizationBudget.MAX_TOOL_CALLS).isEqualTo(25);
        assertThat(LocalizationBudget.WALL_CLOCK).isEqualTo(Duration.ofMinutes(5));
        LocalizationBudget budget = new LocalizationBudget();
        assertThat(budget.maxCalls()).isEqualTo(25);
        assertThat(budget.wallClock()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void configuredBudgetUsesSuppliedLimits() {
        LocalizationBudget budget = new LocalizationBudget(60, Duration.ofMinutes(15), T0);
        assertThat(budget.maxCalls()).isEqualTo(60);
        assertThat(budget.wallClock()).isEqualTo(Duration.ofMinutes(15));
        assertThat(budget.remaining(T0.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(budget.remaining(T0.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void rejectsNonPositiveMaxCalls() {
        assertThatThrownBy(() -> new LocalizationBudget(0, Duration.ofMinutes(1), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCalls");
    }
}

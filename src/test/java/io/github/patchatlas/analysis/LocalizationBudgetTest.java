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
    void rejectsNonPositiveMaxCalls() {
        assertThatThrownBy(() -> new LocalizationBudget(0, Duration.ofMinutes(1), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCalls");
    }
}

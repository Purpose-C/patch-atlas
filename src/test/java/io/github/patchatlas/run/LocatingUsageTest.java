package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.ModelUsage;
import org.junit.jupiter.api.Test;

class LocatingUsageTest {

    @Test
    void missingCallReportsUnknownNotZero() {
        LocatingUsage usage = new LocatingUsage(3, 2, 10, 20, 30);
        assertThat(usage.unknown()).isTrue();
        assertThat(usage.reportedTokens()).isEmpty();
        assertThat(usage.reportLabel()).isEqualTo("unknown");
        assertThat(usage.reportLabel()).doesNotContain("0/0/0");
        assertThat(usage.reportLabel()).doesNotContain("10/20/30");
    }

    @Test
    void completeCallsReportTokenTotals() {
        LocatingUsage usage = new LocatingUsage(2, 2, 4, 6, 10);
        assertThat(usage.unknown()).isFalse();
        assertThat(usage.reportedTokens()).contains(new ModelUsage(4, 6, 10));
        assertThat(usage.reportLabel()).isEqualTo("4/6/10");
    }

    @Test
    void noCallsAreNoneNotUnknown() {
        assertThat(LocatingUsage.none().unknown()).isFalse();
        assertThat(LocatingUsage.none().reportedTokens()).isEmpty();
        assertThat(LocatingUsage.none().reportLabel()).isEqualTo("none");
    }
}

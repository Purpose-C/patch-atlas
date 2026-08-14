package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Closed action set and position mapping for the formal benchmark entry. */
class BenchmarkActionsTest {

    @Test
    void acceptsClosedActions() {
        assertThat(BenchmarkActions.parseAction("freeze")).isEqualTo("freeze");
        assertThat(BenchmarkActions.parseAction("calibrate")).isEqualTo("calibrate");
        assertThat(BenchmarkActions.parseAction("calibrate-1")).isEqualTo("calibrate-1");
        assertThat(BenchmarkActions.parseAction("calibrate-2")).isEqualTo("calibrate-2");
        assertThat(BenchmarkActions.parseAction("calibrate-3")).isEqualTo("calibrate-3");
        assertThat(BenchmarkActions.parseAction("agent-4")).isEqualTo("agent-4");
        assertThat(BenchmarkActions.parseAction("agent-5")).isEqualTo("agent-5");
        assertThat(BenchmarkActions.parseAction("agent-6")).isEqualTo("agent-6");
        assertThat(BenchmarkActions.parseAction("verify")).isEqualTo("verify");
        assertThat(BenchmarkActions.parseAction("dry-run")).isEqualTo("dry-run");
    }

    @Test
    void normalizesWhitespaceAndCase() {
        assertThat(BenchmarkActions.parseAction("  FREEZE  ")).isEqualTo("freeze");
        assertThat(BenchmarkActions.parseAction("Agent-5")).isEqualTo("agent-5");
    }

    @Test
    void rejectsUnknownAction() {
        assertThatThrownBy(() -> BenchmarkActions.parseAction("agent-7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown benchmark action");
    }

    @Test
    void rejectsArbitraryShell() {
        assertThatThrownBy(() -> BenchmarkActions.parseAction("rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentPositionMapsCorrectly() {
        assertThat(BenchmarkActions.agentPosition("agent-4")).isEqualTo(4);
        assertThat(BenchmarkActions.agentPosition("agent-5")).isEqualTo(5);
        assertThat(BenchmarkActions.agentPosition("agent-6")).isEqualTo(6);
    }

    @Test
    void agentPositionRejectsNonAgentAction() {
        assertThatThrownBy(() -> BenchmarkActions.agentPosition("calibrate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an agent action");
    }

    @Test
    void calibratePositionMapsCorrectly() {
        assertThat(BenchmarkActions.calibratePosition("calibrate-1")).isEqualTo(1);
        assertThat(BenchmarkActions.calibratePosition("calibrate-2")).isEqualTo(2);
        assertThat(BenchmarkActions.calibratePosition("calibrate-3")).isEqualTo(3);
    }

    @Test
    void calibratePositionRejectsBatchAndUnknown() {
        assertThatThrownBy(() -> BenchmarkActions.calibratePosition("calibrate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a single-case calibration action");
        assertThatThrownBy(() -> BenchmarkActions.calibratePosition("agent-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a single-case calibration action");
        assertThatThrownBy(() -> BenchmarkActions.calibratePosition("calibrate-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown benchmark action");
    }

    @Test
    void isFormalRunIdentifiesCalibrateAndAgent() {
        assertThat(BenchmarkActions.isFormalRun("calibrate")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("calibrate-1")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("calibrate-2")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("calibrate-3")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("agent-4")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("agent-5")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("agent-6")).isTrue();
    }

    @Test
    void isFormalRunExcludesFreezeAndVerify() {
        assertThat(BenchmarkActions.isFormalRun("freeze")).isFalse();
        assertThat(BenchmarkActions.isFormalRun("verify")).isFalse();
    }

    @Test
    void isFormalRunExcludesDryRun() {
        assertThat(BenchmarkActions.isFormalRun("dry-run")).isFalse();
    }
}

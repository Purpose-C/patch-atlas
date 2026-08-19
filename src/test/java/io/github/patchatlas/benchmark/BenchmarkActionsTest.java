package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.run.ContextOrigin;
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
        assertThat(BenchmarkActions.parseAction("verify-three-arm")).isEqualTo("verify-three-arm");
        assertThat(BenchmarkActions.parseAction("verify-three-arm-036")).isEqualTo("verify-three-arm-036");
        assertThat(BenchmarkActions.parseAction("dry-run")).isEqualTo("dry-run");
        assertThat(BenchmarkActions.parseAction("dry-run-text")).isEqualTo("dry-run-text");
        assertThat(BenchmarkActions.parseAction("dry-run-graph")).isEqualTo("dry-run-graph");
        assertThat(BenchmarkActions.parseAction("arm-heuristic")).isEqualTo("arm-heuristic");
        assertThat(BenchmarkActions.parseAction("arm-text")).isEqualTo("arm-text");
        assertThat(BenchmarkActions.parseAction("arm-graph")).isEqualTo("arm-graph");
        assertThat(BenchmarkActions.parseAction("case-study-heuristic")).isEqualTo("case-study-heuristic");
        assertThat(BenchmarkActions.parseAction("case-study-text")).isEqualTo("case-study-text");
        assertThat(BenchmarkActions.parseAction("case-study-graph")).isEqualTo("case-study-graph");
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
        assertThat(BenchmarkActions.isFormalRun("verify-three-arm")).isFalse();
        assertThat(BenchmarkActions.isFormalRun("verify-three-arm-036")).isFalse();
    }

    @Test
    void isFormalRunExcludesDryRun() {
        assertThat(BenchmarkActions.isFormalRun("dry-run")).isFalse();
        assertThat(BenchmarkActions.isFormalRun("dry-run-text")).isFalse();
        assertThat(BenchmarkActions.isFormalRun("dry-run-graph")).isFalse();
    }

    @Test
    void isFormalRunIncludesThreeArmActions() {
        assertThat(BenchmarkActions.isFormalRun("arm-heuristic")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("arm-text")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("arm-graph")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("case-study-heuristic")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("case-study-text")).isTrue();
        assertThat(BenchmarkActions.isFormalRun("case-study-graph")).isTrue();
    }

    @Test
    void locatingOriginMapsDryRunAndArmActions() {
        assertThat(BenchmarkActions.locatingOrigin("dry-run")).isEqualTo(ContextOrigin.HEURISTIC);
        assertThat(BenchmarkActions.locatingOrigin("dry-run-text")).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(BenchmarkActions.locatingOrigin("dry-run-graph")).isEqualTo(ContextOrigin.GRAPH_TOOLS);
        assertThat(BenchmarkActions.locatingOrigin("arm-heuristic")).isEqualTo(ContextOrigin.HEURISTIC);
        assertThat(BenchmarkActions.locatingOrigin("arm-text")).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(BenchmarkActions.locatingOrigin("arm-graph")).isEqualTo(ContextOrigin.GRAPH_TOOLS);
        assertThat(BenchmarkActions.locatingOrigin("case-study-heuristic")).isEqualTo(ContextOrigin.HEURISTIC);
        assertThat(BenchmarkActions.locatingOrigin("case-study-text")).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(BenchmarkActions.locatingOrigin("case-study-graph")).isEqualTo(ContextOrigin.GRAPH_TOOLS);
    }

    @Test
    void locatingOriginRejectsActionsWithoutLocatingFactor() {
        assertThatThrownBy(() -> BenchmarkActions.locatingOrigin("calibrate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no locating origin");
        assertThatThrownBy(() -> BenchmarkActions.locatingOrigin("verify-three-arm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no locating origin");
    }

    @Test
    void threeArmEvaluationIdMapsVerifyAndArmActions() {
        assertThat(BenchmarkActions.threeArmEvaluationId("verify-three-arm"))
                .isEqualTo(EvaluationIds.BATCH5B_THREE_ARM);
        assertThat(BenchmarkActions.threeArmEvaluationId("verify-three-arm-036"))
                .isEqualTo(EvaluationIds.BATCH5_THREE_ARM);
        assertThat(BenchmarkActions.threeArmEvaluationId("arm-heuristic"))
                .isEqualTo(EvaluationIds.BATCH5B_THREE_ARM);
        assertThat(BenchmarkActions.threeArmEvaluationId("arm-text"))
                .isEqualTo(EvaluationIds.BATCH5B_THREE_ARM);
        assertThat(BenchmarkActions.threeArmEvaluationId("arm-graph"))
                .isEqualTo(EvaluationIds.BATCH5B_THREE_ARM);
        assertThat(BenchmarkActions.threeArmEvaluationId("case-study-heuristic"))
                .isEqualTo(EvaluationIds.SPRING_CASE_STUDY);
        assertThat(BenchmarkActions.threeArmEvaluationId("case-study-text"))
                .isEqualTo(EvaluationIds.SPRING_CASE_STUDY);
        assertThat(BenchmarkActions.threeArmEvaluationId("case-study-graph"))
                .isEqualTo(EvaluationIds.SPRING_CASE_STUDY);
    }

    @Test
    void threeArmEvaluationIdRejectsActionsWithoutBatch() {
        assertThatThrownBy(() -> BenchmarkActions.threeArmEvaluationId("calibrate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no three-arm evaluation");
    }

    @Test
    void isCaseStudyIdentifiesSingleCaseArmActions() {
        assertThat(BenchmarkActions.isCaseStudy("case-study-heuristic")).isTrue();
        assertThat(BenchmarkActions.isCaseStudy("case-study-text")).isTrue();
        assertThat(BenchmarkActions.isCaseStudy("case-study-graph")).isTrue();
        assertThat(BenchmarkActions.isCaseStudy("arm-heuristic")).isFalse();
        assertThat(BenchmarkActions.isCaseStudy("verify-three-arm")).isFalse();
    }
}

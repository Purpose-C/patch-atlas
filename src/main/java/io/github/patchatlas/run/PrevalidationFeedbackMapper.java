package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.GenerationFeedbackCategory;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.AttemptPhase;
import io.github.patchatlas.replay.RunOutcome;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SingleAttemptEvidence;
import io.github.patchatlas.replay.StableSideEvidence;
import java.util.Objects;
import java.util.Optional;

/** Buggy 预验证聚合事实 → 可修正反馈（或成功信号）。 */
final class PrevalidationFeedbackMapper {

    public sealed interface Outcome permits Outcome.Success, Outcome.Correctable, Outcome.Terminal {
        record Success() implements Outcome {}

        record Correctable(GenerationFeedback feedback) implements Outcome {}

        record Terminal(RunFailure failure) implements Outcome {}
    }

    private PrevalidationFeedbackMapper() {}

    public static Outcome map(SideExecutionResult side) {
        Objects.requireNonNull(side, "side");
        if (side.stableEvidence() == StableSideEvidence.TARGET_ASSERTION_FAILURE) {
            return new Outcome.Success();
        }
        if (side.stableEvidence() == StableSideEvidence.TARGET_PASSED) {
            return new Outcome.Correctable(new GenerationFeedback(
                    GenerationFeedbackCategory.TARGET_TEST_PASSED, "target test stable passed on buggy"));
        }

        if (side.attempts().stream()
                .allMatch(attempt -> attempt.phase() == AttemptPhase.PRE_EXECUTION_FAILURE)) {
            String detail = side.attempts().getFirst().diagnostic().orElse("pre-execution failure");
            return new Outcome.Terminal(new RunFailure(
                    FailureStage.REPLAY,
                    FailureCategory.REPLAY_SYSTEM_ERROR,
                    detail));
        }

        Optional<RunOutcome> agg = side.aggregatedOutcome();
        if (agg.isPresent()) {
            return switch (agg.get()) {
                case COMPILE_FAILURE -> new Outcome.Correctable(new GenerationFeedback(
                        GenerationFeedbackCategory.COMPILATION_FAILED, "compilation failed on buggy"));
                case FLAKY_FAILURE -> new Outcome.Correctable(new GenerationFeedback(
                        GenerationFeedbackCategory.TARGET_ASSERTION_NOT_STABLE,
                        "buggy attempts not stable"));
                case ASSERTION_FAILURE -> new Outcome.Correctable(new GenerationFeedback(
                        GenerationFeedbackCategory.NON_TARGET_FAILURE,
                        "assertion failure not attributed to target"));
                case TEST_ERROR -> new Outcome.Correctable(new GenerationFeedback(
                        GenerationFeedbackCategory.NON_TARGET_FAILURE, "test error on buggy"));
                case TIMEOUT, ENVIRONMENT_FAILURE -> new Outcome.Correctable(new GenerationFeedback(
                        GenerationFeedbackCategory.EXECUTION_INCONCLUSIVE,
                        "execution inconclusive: " + agg.get().name()));
                case PASS -> {
                    // 总体 PASS 但非 TARGET_PASSED：目标缺失/无法匹配，不得报 TARGET_TEST_PASSED
                    if (side.stableEvidence() == StableSideEvidence.OTHER_OR_INVALID
                            || allInvalidEvidence(side)) {
                        yield new Outcome.Correctable(new GenerationFeedback(
                                GenerationFeedbackCategory.TARGET_TEST_MISSING,
                                "target test missing or not uniquely matched"));
                    }
                    yield new Outcome.Correctable(new GenerationFeedback(
                            GenerationFeedbackCategory.EXECUTION_INCONCLUSIVE,
                            "aggregated pass without stable target evidence"));
                }
            };
        }
        return new Outcome.Correctable(new GenerationFeedback(
                GenerationFeedbackCategory.TARGET_TEST_MISSING,
                "target evidence missing or invalid on buggy"));
    }

    private static boolean allInvalidEvidence(SideExecutionResult side) {
        return side.attempts().stream()
                .map(AttemptRecord::targetEvidence)
                .allMatch(e -> e == SingleAttemptEvidence.INVALID);
    }
}

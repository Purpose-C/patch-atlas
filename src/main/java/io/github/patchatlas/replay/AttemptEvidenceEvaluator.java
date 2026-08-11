package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.SandboxExecution;
import java.util.Objects;

/**
 * 将一次沙箱执行事实、Surefire 报告与 Target Test 归约为单次目标证据。
 *
 * <p>自行调用 {@link ExecutionClassifier}，不信任外部传入的 {@link RunOutcome}。
 */
public final class AttemptEvidenceEvaluator {

    private final ExecutionClassifier classifier = new ExecutionClassifier();
    private final TargetTestMatcher matcher = new TargetTestMatcher();

    public SingleAttemptEvidence evaluate(
            SandboxExecution execution, TestReport report, TargetTest target) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(target, "target");

        RunOutcome outcome = classifier.classify(execution, report);
        if (outcome != RunOutcome.PASS && outcome != RunOutcome.ASSERTION_FAILURE) {
            return SingleAttemptEvidence.INVALID;
        }

        TargetTestMatch match = matcher.match(report, target);
        if (match == TargetTestMatch.MATCHED_PASSED && outcome == RunOutcome.PASS) {
            return SingleAttemptEvidence.TARGET_PASSED;
        }
        if (match == TargetTestMatch.MATCHED_FAILED && outcome == RunOutcome.ASSERTION_FAILURE) {
            return SingleAttemptEvidence.TARGET_ASSERTION_FAILURE;
        }
        return SingleAttemptEvidence.INVALID;
    }
}

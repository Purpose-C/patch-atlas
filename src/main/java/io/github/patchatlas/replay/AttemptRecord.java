package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.SandboxExecution;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次尝试的可追溯证据。
 *
 * <p>非公开 record：canonical 构造私有。{@link #executed} / {@link #reportFailure} 由
 * {@link ExecutionClassifier} 与 {@link AttemptEvidenceEvaluator} 从原始事实计算
 * outcome/evidence，调用方不得传入自相矛盾的成功证据。
 */
public final class AttemptRecord {

    public static final int MAX_DIAGNOSTIC_CHARS = 512;

    private static final ExecutionClassifier CLASSIFIER = new ExecutionClassifier();
    private static final AttemptEvidenceEvaluator EVALUATOR = new AttemptEvidenceEvaluator();

    private final AttemptPhase phase;
    private final Optional<SandboxExecution> execution;
    private final TestReport report;
    private final Optional<RunOutcome> outcome;
    private final SingleAttemptEvidence targetEvidence;
    private final Optional<String> diagnostic;

    private AttemptRecord(
            AttemptPhase phase,
            Optional<SandboxExecution> execution,
            TestReport report,
            Optional<RunOutcome> outcome,
            SingleAttemptEvidence targetEvidence,
            Optional<String> diagnostic) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.report = Objects.requireNonNull(report, "report");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.targetEvidence = Objects.requireNonNull(targetEvidence, "targetEvidence");
        this.diagnostic = bound(Objects.requireNonNull(diagnostic, "diagnostic"));
        validateShape();
    }

    public static AttemptRecord preExecutionFailure(String diagnostic) {
        return new AttemptRecord(
                AttemptPhase.PRE_EXECUTION_FAILURE,
                Optional.empty(),
                TestReport.empty(),
                Optional.empty(),
                SingleAttemptEvidence.INVALID,
                Optional.of(diagnostic));
    }

    /**
     * 从真实执行 + 报告 + Target Test 计算 outcome 与目标证据。
     */
    public static AttemptRecord executed(
            SandboxExecution execution, TestReport report, TargetTest target) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(target, "target");
        RunOutcome outcome = CLASSIFIER.classify(execution, report);
        SingleAttemptEvidence evidence = EVALUATOR.evaluate(execution, report, target);
        return new AttemptRecord(
                AttemptPhase.EXECUTED,
                Optional.of(execution),
                report,
                Optional.of(outcome),
                evidence,
                Optional.empty());
    }

    /**
     * 沙箱已执行但报告不可读/路径不安全：outcome 仅由 execution + 空报告分类得出。
     */
    public static AttemptRecord reportFailure(SandboxExecution execution, String diagnostic) {
        Objects.requireNonNull(execution, "execution");
        RunOutcome outcome = CLASSIFIER.classify(execution, TestReport.empty());
        return new AttemptRecord(
                AttemptPhase.REPORT_FAILURE,
                Optional.of(execution),
                TestReport.empty(),
                Optional.of(outcome),
                SingleAttemptEvidence.INVALID,
                Optional.of(diagnostic));
    }

    public AttemptPhase phase() {
        return phase;
    }

    public Optional<SandboxExecution> execution() {
        return execution;
    }

    public TestReport report() {
        return report;
    }

    public Optional<RunOutcome> outcome() {
        return outcome;
    }

    public SingleAttemptEvidence targetEvidence() {
        return targetEvidence;
    }

    public Optional<String> diagnostic() {
        return diagnostic;
    }

    private void validateShape() {
        switch (phase) {
            case PRE_EXECUTION_FAILURE -> {
                if (execution.isPresent() || outcome.isPresent()) {
                    throw new IllegalArgumentException(
                            "PRE_EXECUTION_FAILURE must not carry execution or outcome");
                }
                if (targetEvidence != SingleAttemptEvidence.INVALID) {
                    throw new IllegalArgumentException("PRE_EXECUTION_FAILURE requires INVALID evidence");
                }
                if (diagnostic.isEmpty()) {
                    throw new IllegalArgumentException("PRE_EXECUTION_FAILURE requires diagnostic");
                }
            }
            case EXECUTED -> {
                if (execution.isEmpty() || outcome.isEmpty()) {
                    throw new IllegalArgumentException("EXECUTED requires execution and outcome");
                }
                if (diagnostic.isPresent()) {
                    throw new IllegalArgumentException("EXECUTED must not carry diagnostic");
                }
            }
            case REPORT_FAILURE -> {
                if (execution.isEmpty() || outcome.isEmpty()) {
                    throw new IllegalArgumentException("REPORT_FAILURE requires execution and outcome");
                }
                if (targetEvidence != SingleAttemptEvidence.INVALID) {
                    throw new IllegalArgumentException("REPORT_FAILURE requires INVALID evidence");
                }
                if (diagnostic.isEmpty()) {
                    throw new IllegalArgumentException("REPORT_FAILURE requires diagnostic");
                }
            }
        }
    }

    private static Optional<String> bound(Optional<String> diagnostic) {
        if (diagnostic.isEmpty()) {
            return diagnostic;
        }
        String value = diagnostic.get();
        if (value.isBlank()) {
            throw new IllegalArgumentException("diagnostic must not be blank when present");
        }
        if (value.length() <= MAX_DIAGNOSTIC_CHARS) {
            return diagnostic;
        }
        return Optional.of(value.substring(0, MAX_DIAGNOSTIC_CHARS));
    }
}

package io.github.patchatlas.run;

import io.github.patchatlas.replay.AttemptPhase;
import io.github.patchatlas.replay.RunOutcome;
import io.github.patchatlas.replay.SingleAttemptEvidence;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import java.util.Objects;
import java.util.Optional;

public record RunAttemptView(
        int replayRound,
        ReplaySide side,
        int attemptOrdinal,
        AttemptPhase phase,
        Optional<RunOutcome> outcome,
        SingleAttemptEvidence targetEvidence,
        Optional<String> diagnostic,
        Optional<SandboxExecutionStatus> sandboxStatus,
        Optional<Integer> exitCode,
        Optional<Long> elapsedMs,
        Optional<Boolean> timedOut,
        Optional<String> commandJson,
        Optional<String> image,
        Optional<String> limitsJson,
        Optional<String> networkMode,
        Optional<String> logSummary,
        Optional<TargetTestCaseView> targetTestCase,
        int evidenceSchemaVersion) {

    public RunAttemptView {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(targetEvidence, "targetEvidence");
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(sandboxStatus, "sandboxStatus");
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(elapsedMs, "elapsedMs");
        Objects.requireNonNull(timedOut, "timedOut");
        Objects.requireNonNull(commandJson, "commandJson");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(limitsJson, "limitsJson");
        Objects.requireNonNull(networkMode, "networkMode");
        Objects.requireNonNull(logSummary, "logSummary");
        Objects.requireNonNull(targetTestCase, "targetTestCase");
    }

    public record TargetTestCaseView(
            String className,
            String methodName,
            String status,
            Optional<String> message,
            Optional<Long> elapsedMs,
            Optional<String> exceptionType) {
        public TargetTestCaseView {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(elapsedMs, "elapsedMs");
            Objects.requireNonNull(exceptionType, "exceptionType");
        }
    }
}

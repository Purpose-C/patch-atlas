package io.github.patchatlas.observability;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenSandboxCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** 固定领域事件：只写白名单 key，不回显不可信正文。 */
public final class RunEvents {

    private static final Logger log = LoggerFactory.getLogger(RunEvents.class);

    private RunEvents() {}

    public static void runSubmitted(UUID runId, VerificationMode mode, RunState state, boolean created) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("run.submitted")
                    .addKeyValue("mode", mode.name())
                    .addKeyValue("state", state.name())
                    .addKeyValue("submission_outcome", created ? "created" : "reused")
                    .log("run submitted");
        }
    }

    public static void submissionConflict(UUID runId) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            warn("run.submission.conflict").log("run submission conflict");
        }
    }

    public static void runClaimed(UUID runId, VerificationMode mode, int recoveryCount) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("run.claimed")
                    .addKeyValue("mode", mode.name())
                    .addKeyValue("recovery_count", recoveryCount)
                    .log("run claimed");
        }
    }

    public static void runRecovered(UUID runId, VerificationMode mode, int recoveryCount) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("run.recovered")
                    .addKeyValue("mode", mode.name())
                    .addKeyValue("recovery_count", recoveryCount)
                    .log("run recovered");
        }
    }

    public static void generationAttemptReserved(
            UUID runId, int attemptOrdinal, String provider, String modelName) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("generation.attempt.reserved")
                    .addKeyValue("attempt_ordinal", attemptOrdinal)
                    .addKeyValue("provider", provider)
                    .addKeyValue("model_name", modelName)
                    .log("generation attempt reserved");
        }
    }

    public static void generationUsageRecorded(
            UUID runId, long inputTokens, long outputTokens, long totalTokens, Integer usageRecordCount) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            LoggingEventBuilder builder = info("generation.usage.recorded")
                    .addKeyValue("input_tokens", inputTokens)
                    .addKeyValue("output_tokens", outputTokens)
                    .addKeyValue("total_tokens", totalTokens);
            if (usageRecordCount != null) {
                builder = builder.addKeyValue("usage_record_count", usageRecordCount);
            }
            builder.log("generation usage recorded");
        }
    }

    public static void candidateCommitted(UUID runId) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("candidate.committed").log("candidate committed");
        }
    }

    public static void replayStarted(UUID runId, int replayRound) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("replay.started").addKeyValue("replay_round", replayRound).log("replay started");
        }
    }

    public static void runCompleted(UUID runId, VerificationMode mode, ReplayVerdict verdict) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("run.completed")
                    .addKeyValue("mode", mode.name())
                    .addKeyValue("state", "COMPLETED")
                    .addKeyValue("verdict", verdict.name())
                    .log("run completed");
        }
    }

    public static void runFailed(UUID runId, VerificationMode mode, RunFailure failure) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            info("run.failed")
                    .addKeyValue("mode", mode.name())
                    .addKeyValue("state", "FAILED")
                    .addKeyValue("failure_stage", failure.stage().name())
                    .addKeyValue("failure_category", failure.category().name())
                    .log("run failed");
        }
    }

    public static void claimStale(UUID runId) {
        try (var ignored = RunCorrelation.openIfAbsent(runId)) {
            warn("claim.stale").log("claim stale");
        }
    }

    public static void workerTickFailed(RuntimeException error) {
        warn("worker.tick.failed")
                .addKeyValue("error_type", error.getClass().getSimpleName())
                .log("worker tick failed");
    }

    public static void sandboxExecuted(MavenSandboxCommand command, SandboxExecution execution) {
        LoggingEventBuilder builder =
                execution.status() == SandboxExecutionStatus.COMPLETED
                        ? info("sandbox.executed")
                        : warn("sandbox.executed");
        builder.addKeyValue(
                        "command_type",
                        command instanceof MavenDependencyWarmupCommand ? "dependency_warmup" : "test")
                .addKeyValue("network_mode", execution.networkMode().name().toLowerCase())
                .addKeyValue("sandbox_status", execution.status().name())
                .addKeyValue("duration_ms", execution.elapsed().toMillis())
                .addKeyValue("timed_out", execution.timedOut())
                .log("sandbox executed");
    }

    public static void observabilityRecordingFailed(RuntimeException error) {
        warn("observability.recording.failed")
                .addKeyValue("component", "sandbox")
                .addKeyValue("error_type", error.getClass().getSimpleName())
                .log("sandbox observation failed");
    }

    private static LoggingEventBuilder info(String event) {
        return log.atInfo().addKeyValue("event", event);
    }

    private static LoggingEventBuilder warn(String event) {
        return log.atWarn().addKeyValue("event", event);
    }

}

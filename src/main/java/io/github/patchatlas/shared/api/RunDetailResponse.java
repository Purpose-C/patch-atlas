package io.github.patchatlas.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunDetailResponse(
        UUID runId,
        String mode,
        String state,
        String caseId,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Input input,
        ExecutionPolicy executionPolicy,
        Generation generation,
        Candidate candidate,
        Result result,
        List<Attempt> attempts) {

    public record Input(
            String repositoryUrl,
            String issueUrl,
            String issueTitle,
            String issueBody,
            String buggyRevision,
            String fixedRevision,
            String modulePath) {}

    public record ExecutionPolicy(String javaVersion, String networkMode) {}

    public record Generation(
            int attemptCount,
            String modelProvider,
            String modelName,
            long inputTokens,
            long outputTokens,
            long totalTokens) {}

    public record Candidate(
            String patchText, String patchSha256, String targetClass, String targetMethod) {}

    public record Result(
            String verdict, String failureStage, String failureCategory, String failureSummary) {}

    public record Attempt(
            int replayRound,
            String side,
            int attemptOrdinal,
            String phase,
            String outcome,
            String targetEvidence,
            String diagnostic,
            String sandboxStatus,
            Integer exitCode,
            Long elapsedMs,
            Boolean timedOut,
            String commandJson,
            String image,
            String limitsJson,
            String networkMode,
            String logSummary,
            TargetTestCase targetTestCase,
            int evidenceSchemaVersion) {}

    public record TargetTestCase(
            String className,
            String methodName,
            String status,
            String message,
            Long elapsedMs,
            String exceptionType) {}
}

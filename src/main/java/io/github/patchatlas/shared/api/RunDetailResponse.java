package io.github.patchatlas.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RunDetailResponse(
        UUID runId,
        String mode,
        String runPurpose,
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
        List<Attempt> attempts,
        Locating locating,
        List<String> generatorSourcePaths) {

    public RunDetailResponse {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        locating = Objects.requireNonNull(locating, "locating");
        generatorSourcePaths = List.copyOf(generatorSourcePaths);
    }

    @Override
    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    @Override
    public List<String> generatorSourcePaths() {
        return List.copyOf(generatorSourcePaths);
    }

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
            long totalTokens,
            Integer usageRecordCount,
            String usageStatus,
            EstimatedCost estimatedCost) {}

    public record EstimatedCost(
            String amount, String currency, String pricingEffectiveDate, String pricingSource) {}

    public record Candidate(
            String patchText,
            String patchSha256,
            String targetClass,
            String targetMethod,
            String patchProvenance) {}

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

    public record Locating(
            String contextOrigin,
            boolean recorded,
            boolean toolCallsApplicable,
            Integer toolCallCount,
            Map<String, Integer> stepKindCounts,
            int errorCount,
            String usageStatus,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            List<BudgetEvent> budgetEvents,
            GraphBuild graphBuild,
            List<LocatingStep> steps,
            List<String> selectedPaths,
            boolean truncated,
            int stepLimit) {

        public Locating {
            stepKindCounts = Map.copyOf(Objects.requireNonNull(stepKindCounts, "stepKindCounts"));
            budgetEvents = List.copyOf(Objects.requireNonNull(budgetEvents, "budgetEvents"));
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            selectedPaths = List.copyOf(Objects.requireNonNull(selectedPaths, "selectedPaths"));
        }
    }

    public record LocatingStep(
            int seq, String kind, String subject, String reason, String outcome, Map<String, Object> detail) {

        public LocatingStep {
            detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        }
    }

    public record BudgetEvent(int seq, String kind, String limit, Integer used, Integer maxCalls) {}

    public record GraphBuild(Long durationMs, Boolean cacheHit) {}
}

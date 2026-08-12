package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RunDetailView(
        UUID runId,
        VerificationMode mode,
        RunState state,
        String caseId,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        InputSummary input,
        MavenExecutionPolicy executionPolicy,
        GenerationMeta generation,
        Optional<CandidateView> candidate,
        Optional<ReplayVerdict> verdict,
        Optional<RunFailure> failure,
        List<RunAttemptView> attempts) {

    public RunDetailView {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(failure, "failure");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
    }

    public record InputSummary(
            String repositoryUrl,
            String issueUrl,
            String issueTitle,
            String issueBody,
            String buggyRevision,
            String fixedRevision,
            String modulePath) {
        public InputSummary {
            Objects.requireNonNull(repositoryUrl, "repositoryUrl");
            Objects.requireNonNull(issueTitle, "issueTitle");
            Objects.requireNonNull(issueBody, "issueBody");
            Objects.requireNonNull(buggyRevision, "buggyRevision");
            Objects.requireNonNull(modulePath, "modulePath");
        }
    }

    public record GenerationMeta(
            int attemptCount,
            String modelProvider,
            String modelName,
            long inputTokens,
            long outputTokens,
            long totalTokens) {
        public GenerationMeta {
            if (attemptCount < 0) {
                throw new IllegalArgumentException("attemptCount must not be negative");
            }
        }
    }

    public record CandidateView(String patchText, String patchSha256, TargetTest targetTest) {
        public CandidateView {
            Objects.requireNonNull(patchText, "patchText");
            Objects.requireNonNull(patchSha256, "patchSha256");
            Objects.requireNonNull(targetTest, "targetTest");
        }
    }
}

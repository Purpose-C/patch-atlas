package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RunSummary(
        UUID runId,
        VerificationMode mode,
        RunState state,
        String issueTitle,
        String repositoryUrl,
        Optional<ReplayVerdict> verdict,
        Optional<FailureCategory> failureCategory,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public RunSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(issueTitle, "issueTitle");
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}

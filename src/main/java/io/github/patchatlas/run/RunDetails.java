package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code findRun} 返回的只读详情（读取 seam；无分页/REST DTO）。
 */
public record RunDetails(
        UUID runId,
        VerificationMode mode,
        RunState state,
        long version,
        String caseId,
        String repositoryUrl,
        String issueTitle,
        String buggyRevision,
        String fixedRevision,
        Optional<ReplayVerdict> verdict,
        Optional<RunFailure> failure,
        Optional<PersistedCandidatePatch> candidate,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public RunDetails {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(issueTitle, "issueTitle");
        Objects.requireNonNull(buggyRevision, "buggyRevision");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (state == RunState.COMPLETED) {
            TerminalRunRules.requireCompleted(verdict.orElse(null), failure.orElse(null));
        } else if (state == RunState.FAILED) {
            TerminalRunRules.requireFailed(verdict.orElse(null), failure.orElse(null));
        } else {
            if (verdict.isPresent() || failure.isPresent()) {
                throw new IllegalArgumentException("non-terminal run must not carry verdict/failure");
            }
        }
        if (mode == VerificationMode.LIVE && fixedRevision != null) {
            throw new IllegalArgumentException("LIVE details must not carry fixedRevision");
        }
        if (mode == VerificationMode.HISTORICAL && fixedRevision == null) {
            throw new IllegalArgumentException("HISTORICAL details require fixedRevision");
        }
    }
}

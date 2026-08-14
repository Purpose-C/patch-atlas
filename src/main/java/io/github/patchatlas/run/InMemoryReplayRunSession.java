package io.github.patchatlas.run;

import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.VerificationMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 默认测试用内存 ReplayRunSession（无 PostgreSQL）。 */
public final class InMemoryReplayRunSession implements ReplayRunSession {

    private ClaimedRun claim;
    private final ReplayWorkspaceProjection projection;
    private final RunPurpose purpose;
    private RunDetails terminal;
    private int openRoundCount;
    private final AtomicLong version = new AtomicLong();

    public InMemoryReplayRunSession(ClaimedRun initial, ReplayWorkspaceProjection projection) {
        this(initial, projection, RunPurpose.STANDARD);
    }

    public InMemoryReplayRunSession(
            ClaimedRun initial, ReplayWorkspaceProjection projection, RunPurpose purpose) {
        this.claim = Objects.requireNonNull(initial, "initial");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        if (initial.state() != RunState.REPLAYING) {
            throw new IllegalArgumentException("session requires REPLAYING claim");
        }
        this.version.set(initial.version());
    }

    @Override
    public synchronized Opened openRound() {
        requireLive();
        int nextRound = claim.replayRound() + 1;
        claim = new ClaimedRun(
                claim.runId(),
                claim.mode(),
                claim.state(),
                version.incrementAndGet(),
                claim.lease(),
                claim.recoveryCount(),
                nextRound,
                claim.candidate());
        openRoundCount++;
        RunEvents.replayStarted(claim.runId(), claim.replayRound());
        return new Opened(claim, projection, purpose);
    }

    @Override
    public synchronized RunDetails complete(ReplayResult result) {
        Objects.requireNonNull(result, "result");
        requireLive();
        if (claim.state() != RunState.REPLAYING || claim.candidate().isEmpty()) {
            throw new IllegalStateException("complete requires a REPLAYING candidate");
        }
        long v = version.incrementAndGet();
        Instant now = Instant.now();
        terminal = new RunDetails(
                claim.runId(),
                claim.mode(),
                RunState.COMPLETED,
                v,
                null,
                "https://github.com/ex/repo.git",
                "t",
                claim.mode() == VerificationMode.LIVE
                        ? "a".repeat(40)
                        : projection.buggyRevision(),
                claim.mode() == VerificationMode.HISTORICAL
                        ? ((ReplayWorkspaceProjection.Historical) projection).fixedRevision()
                        : null,
                Optional.of(result.verdict()),
                Optional.empty(),
                claim.candidate(),
                now,
                now,
                now);
        RunEvents.runCompleted(terminal.runId(), terminal.mode(), result.verdict());
        return terminal;
    }

    @Override
    public synchronized RunDetails fail(RunFailure failure) {
        Objects.requireNonNull(failure, "failure");
        requireLive();
        long v = version.incrementAndGet();
        Instant now = Instant.now();
        terminal = new RunDetails(
                claim.runId(),
                claim.mode(),
                RunState.FAILED,
                v,
                null,
                "https://github.com/ex/repo.git",
                "t",
                claim.mode() == VerificationMode.LIVE
                        ? "a".repeat(40)
                        : projection.buggyRevision(),
                claim.mode() == VerificationMode.HISTORICAL
                        ? ((ReplayWorkspaceProjection.Historical) projection).fixedRevision()
                        : null,
                Optional.empty(),
                Optional.of(failure),
                Optional.empty(),
                now,
                now,
                now);
        RunEvents.runFailed(terminal.runId(), terminal.mode(), failure);
        return terminal;
    }

    public int openRoundCount() {
        return openRoundCount;
    }

    public Optional<RunDetails> terminal() {
        return Optional.ofNullable(terminal);
    }

    private void requireLive() {
        if (terminal != null) {
            throw new StaleClaimException(claim.runId(), "session already terminal");
        }
    }
}

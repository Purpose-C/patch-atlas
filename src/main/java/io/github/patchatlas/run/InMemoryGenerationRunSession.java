package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 默认测试用内存 GenerationRunSession（无 PostgreSQL）。 */
public final class InMemoryGenerationRunSession implements GenerationRunSession {

    private ClaimedRun claim;
    private int generationAttemptCount;
    private String modelProvider;
    private String modelName;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private RunDetails terminal;
    private final AtomicLong version = new AtomicLong();

    public InMemoryGenerationRunSession(ClaimedRun initial) {
        this.claim = Objects.requireNonNull(initial, "initial");
        if (initial.state() != RunState.GENERATING) {
            throw new IllegalArgumentException("session requires GENERATING claim");
        }
        this.version.set(initial.version());
    }

    @Override
    public synchronized ReserveResult reserveGenerationAttempt(String provider, String modelName) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
        if (terminal != null) {
            return new ReserveResult.Stale(new StaleClaimException(claim.runId(), "already terminal"));
        }
        if (generationAttemptCount >= GenerationRequest.MAX_ATTEMPTS) {
            RunDetails failed = failInternal(new RunFailure(
                    FailureStage.GENERATION,
                    FailureCategory.GENERATION_EXHAUSTED,
                    "generation attempts exhausted"));
            return new ReserveResult.Exhausted(failed);
        }
        if (generationAttemptCount == 0) {
            this.modelProvider = provider;
            this.modelName = modelName;
        } else if (!provider.equals(modelProvider) || !modelName.equals(this.modelName)) {
            throw new IllegalArgumentException("provider/model must not change within a run");
        }
        generationAttemptCount++;
        claim = bump(claim);
        return new ReserveResult.Reserved(claim, generationAttemptCount);
    }

    @Override
    public synchronized ClaimedRun recordModelUsage(ModelUsage usage) {
        Objects.requireNonNull(usage, "usage");
        requireLive();
        inputTokens = safeAdd(inputTokens, usage.inputTokens());
        outputTokens = safeAdd(outputTokens, usage.outputTokens());
        totalTokens = safeAdd(totalTokens, usage.totalTokens());
        claim = bump(claim);
        return claim;
    }

    @Override
    public synchronized ClaimedRun commitCandidate(GatedCandidate gated) {
        Objects.requireNonNull(gated, "gated");
        requireLive();
        claim = new ClaimedRun(
                claim.runId(),
                claim.mode(),
                RunState.REPLAYING,
                version.incrementAndGet(),
                claim.lease(),
                claim.recoveryCount(),
                claim.replayRound(),
                Optional.of(gated.patch()));
        return claim;
    }

    @Override
    public synchronized RunDetails fail(RunFailure failure) {
        return failInternal(failure);
    }

    public int generationAttemptCount() {
        return generationAttemptCount;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    /** 有界 token 汇总（仅数字，不含供应商原文）。 */
    public String tokenSummary() {
        return "in=" + inputTokens + ",out=" + outputTokens + ",total=" + totalTokens;
    }

    public Optional<RunDetails> terminal() {
        return Optional.ofNullable(terminal);
    }

    private RunDetails failInternal(RunFailure failure) {
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
                "a".repeat(40),
                claim.mode() == VerificationMode.HISTORICAL ? "b".repeat(40) : null,
                Optional.empty(),
                Optional.of(failure),
                Optional.empty(),
                now,
                now,
                now);
        claim = new ClaimedRun(
                claim.runId(),
                claim.mode(),
                RunState.GENERATING,
                v,
                claim.lease(),
                claim.recoveryCount(),
                claim.replayRound(),
                Optional.empty());
        // mark terminal by nulling lease shape - store FAIL clears lease; keep claim unusable
        return terminal;
    }

    private void requireLive() {
        if (terminal != null) {
            throw new StaleClaimException(claim.runId(), "session already failed");
        }
    }

    private ClaimedRun bump(ClaimedRun c) {
        long v = version.incrementAndGet();
        return new ClaimedRun(
                c.runId(),
                c.mode(),
                c.state(),
                v,
                c.lease(),
                c.recoveryCount(),
                c.replayRound(),
                c.candidate());
    }

    private static long safeAdd(long a, long b) {
        long r = a + b;
        if (r < 0) {
            throw new IllegalArgumentException("token overflow");
        }
        return r;
    }
}

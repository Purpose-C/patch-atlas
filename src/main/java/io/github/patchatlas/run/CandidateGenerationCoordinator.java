package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.GeneratorIdentity;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.ResponseTruncationGuard;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.util.Objects;
import java.util.Optional;

/**
 * 生成阶段编排：预占 → 模型调用 → Gate → Buggy 两次预验证 → 提交或修正/失败。
 *
 * <p>不持有 DataSource / Run Store；持久化只经 {@link GenerationRunSession}。
 * workspace 打开使用预占返回的 {@link ClaimedRun}，不再二次 currentClaim 查询。
 */
public final class CandidateGenerationCoordinator {

    public sealed interface Result permits Result.CandidateCommitted, Result.RunFailed {
        record CandidateCommitted(ClaimedRun claim) implements Result {}

        record RunFailed(RunDetails details) implements Result {}
    }

    /**
     * 单次 attempt 的统一编排决策：提交候选 / 带反馈重试 / 终态失败。
     *
     * <p>三个 mapper 的 Outcome 经 {@code toDecision(...)} 适配为此类型，让 {@link #run}
     * 的控制流保持扁平。Success → Commit，Correctable → Retry，Terminal → Fail。
     */
    public sealed interface AttemptDecision
            permits AttemptDecision.Commit, AttemptDecision.Retry, AttemptDecision.Fail {
        record Commit(GatedCandidate gated) implements AttemptDecision {}

        record Retry(Optional<CandidateDraft> draft, GenerationFeedback feedback)
                implements AttemptDecision {}

        record Fail(RunFailure failure) implements AttemptDecision {}
    }

    private final TestGenerator generator;
    private final PatchGate patchGate;
    private final CandidateWorkspaceFactory workspaceFactory;
    private final DependencyWarmupRunner dependencyWarmupRunner;
    private final SideReplayRunner sideReplayRunner;

    public CandidateGenerationCoordinator(
            TestGenerator generator,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            DependencyWarmupRunner dependencyWarmupRunner,
            SideReplayRunner sideReplayRunner) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.patchGate = Objects.requireNonNull(patchGate, "patchGate");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.dependencyWarmupRunner =
                Objects.requireNonNull(dependencyWarmupRunner, "dependencyWarmupRunner");
        this.sideReplayRunner = Objects.requireNonNull(sideReplayRunner, "sideReplayRunner");
    }

    public Result run(GenerationInput generationInput, GenerationRunSession session) {
        String javaVersion = generationInput.generatorContext().javaVersion() == null
                ? MavenExecutionPolicy.DEFAULT_JAVA_VERSION
                : generationInput.generatorContext().javaVersion();
        return run(
                generationInput,
                new MavenExecutionPolicy(javaVersion, MavenNetworkMode.OFFLINE),
                session);
    }

    public Result run(
            GenerationInput generationInput,
            MavenExecutionPolicy executionPolicy,
            GenerationRunSession session) {
        Objects.requireNonNull(generationInput, "generationInput");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        Objects.requireNonNull(session, "session");

        Optional<CandidateDraft> previousDraft = Optional.empty();
        Optional<GenerationFeedback> feedback = Optional.empty();

        for (int guard = 0; guard < GenerationRequest.MAX_ATTEMPTS; guard++) {
            GeneratorIdentity identity = generator.identity();
            GenerationRunSession.ReserveResult reserveResult =
                    session.reserveGenerationAttempt(identity.provider(), identity.modelName());
            if (reserveResult instanceof GenerationRunSession.ReserveResult.Exhausted exhausted) {
                return new Result.RunFailed(exhausted.failedRun());
            }
            if (reserveResult instanceof GenerationRunSession.ReserveResult.Stale stale) {
                throw stale.toException();
            }
            GenerationRunSession.ReserveResult.Reserved slot =
                    (GenerationRunSession.ReserveResult.Reserved) reserveResult;
            int ordinal = slot.attemptOrdinal();
            ClaimedRun claimAfterReserve = slot.claim();

            GenerationRequest request = buildRequest(generationInput, ordinal, previousDraft, feedback);
            GenerationResult call = generator.generate(request);
            recordUsageIfPresent(session, call);

            AttemptDecision decision = decideAttempt(
                    call, ordinal, claimAfterReserve, generationInput, executionPolicy, session);
            switch (decision) {
                case AttemptDecision.Commit commit -> {
                    // commitCandidate 移出 workspace try 范围：
                    // 提交期的任何数据库错误都应向上传播，让 Run 经租约过期恢复，
                    // 而不是被 workspace catch 吞掉、错误地终结为 WORKSPACE_ERROR。
                    ClaimedRun replaying = session.commitCandidate(commit.gated());
                    return new Result.CandidateCommitted(replaying);
                }
                case AttemptDecision.Retry retry -> {
                    RunEvents.generationAttemptRejected(
                            claimAfterReserve.runId(),
                            ordinal,
                            retry.feedback().category(),
                            retry.feedback().summary());
                    previousDraft = retry.draft();
                    feedback = Optional.of(retry.feedback());
                }
                case AttemptDecision.Fail fail -> {
                    return new Result.RunFailed(session.fail(fail.failure()));
                }
            }
        }
        return new Result.RunFailed(session.fail(new RunFailure(
                FailureStage.GENERATION,
                FailureCategory.GENERATION_EXHAUSTED,
                "generation attempts exhausted")));
    }

    /** 根据模型调用结果分派到调用失败映射或 workspace 预验证编排，返回统一决策。 */
    private AttemptDecision decideAttempt(
            GenerationResult call,
            int ordinal,
            ClaimedRun claimAfterReserve,
            GenerationInput generationInput,
            MavenExecutionPolicy executionPolicy,
            GenerationRunSession session) {
        if (call instanceof GenerationResult.GenerationCallFailure failure) {
            boolean remaining = ordinal < GenerationRequest.MAX_ATTEMPTS;
            return CallFailureMapper.toDecision(
                    CallFailureMapper.map(failure.category(), failure.summary(), remaining));
        }
        CandidateDraft draft = ((GenerationResult.GeneratedDraft) call).draft();
        CompletionDiagnostics diagnostics =
                call.completionDiagnostics().orElseGet(CompletionDiagnostics::unknown);
        if (ResponseTruncationGuard.truncated(diagnostics)) {
            PatchPreparationResult.RejectedCandidate rejected = ResponseTruncationGuard.rejection();
            return PatchGateOutcomeMapper.toDecision(
                    PatchGateOutcomeMapper.map(rejected.category(), rejected.reason()), draft);
        }
        return attemptInWorkspace(
                draft, diagnostics, claimAfterReserve, generationInput, executionPolicy, session);
    }

    /**
     * 打开 workspace → 预热 → Gate → 预验证，返回统一决策。
     *
     * <p>catch 仅覆盖 workspace 操作（open/prepare/runSide）；{@code commitCandidate}
     * 不在此范围内调用，故 commit 期的 StaleClaimException 不会被误判为 WORKSPACE_ERROR。
     * workspace 期 StaleClaimException 直接抛出（lease fencing 不得被吞）。
     * 兜底异常是 WORKSPACE_ERROR；只有 Patch Gate 显式判定的越界写/symlink 才是
     * WORKSPACE_UNSAFE。
     */
    private AttemptDecision attemptInWorkspace(
            CandidateDraft draft,
            CompletionDiagnostics diagnostics,
            ClaimedRun claimAfterReserve,
            GenerationInput generationInput,
            MavenExecutionPolicy executionPolicy,
            GenerationRunSession session) {
        try (CandidateWorkspaceFactory.WorkspaceSession workspace =
                workspaceFactory.open(claimAfterReserve, generationInput, executionPolicy)) {
            MavenTestCommand command = commandForDraft(workspace, draft);
            Optional<String> warmupFailure = dependencyWarmupRunner.warm(workspace.workspace(), command);
            if (warmupFailure.isPresent()) {
                return new AttemptDecision.Fail(new RunFailure(
                        FailureStage.REPLAY,
                        FailureCategory.REPLAY_SYSTEM_ERROR,
                        warmupFailure.orElseThrow()));
            }
            PatchPreparationResult prepared = patchGate.prepare(
                    workspace.workspace(),
                    workspace.modulePath(),
                    draft,
                    workspace.executionPolicy(),
                    diagnostics);
            if (prepared instanceof PatchPreparationResult.RejectedCandidate rejected) {
                return PatchGateOutcomeMapper.toDecision(
                        PatchGateOutcomeMapper.map(rejected.category(), rejected.reason()), draft);
            }
            PatchPreparationResult.PreparedCandidate preparedOk =
                    (PatchPreparationResult.PreparedCandidate) prepared;
            SideExecutionResult side = sideReplayRunner.runSide(
                    preparedOk.workspace(), preparedOk.command(), preparedOk.targetTest());
            return PrevalidationFeedbackMapper.toDecision(
                    PrevalidationFeedbackMapper.map(side), draft, preparedOk);
        } catch (StaleClaimException stale) {
            throw stale;
        } catch (Exception ex) {
            return new AttemptDecision.Fail(WorkspaceFailureSummarizer.failure(ex, session.purpose()));
        }
    }

    private static GenerationRequest buildRequest(
            GenerationInput generationInput,
            int ordinal,
            Optional<CandidateDraft> previousDraft,
            Optional<GenerationFeedback> feedback) {
        if (previousDraft.isPresent()) {
            return GenerationRequest.correction(
                    generationInput, ordinal, previousDraft.orElseThrow(), feedback.orElseThrow());
        }
        if (feedback.isPresent()) {
            return GenerationRequest.feedbackOnly(generationInput, ordinal, feedback.orElseThrow());
        }
        return GenerationRequest.first(generationInput, ordinal);
    }

    private static void recordUsageIfPresent(GenerationRunSession session, GenerationResult call) {
        call.usage()
                .ifPresent(usage -> session.recordModelUsage(
                        usage, call.completionDiagnostics().orElseGet(CompletionDiagnostics::unknown)));
    }

    private static MavenTestCommand commandForDraft(
            CandidateWorkspaceFactory.WorkspaceSession workspace, CandidateDraft draft) {
        return new MavenTestCommand(
                workspace.modulePath(),
                draft.targetTest().className() + "#" + draft.targetTest().methodName(),
                workspace.executionPolicy().networkMode(),
                workspace.executionPolicy().javaVersion());
    }
}
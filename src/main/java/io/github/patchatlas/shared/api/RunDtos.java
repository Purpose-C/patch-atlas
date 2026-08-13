package io.github.patchatlas.shared.api;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.observability.EstimatedModelCost;
import io.github.patchatlas.observability.EstimatedModelCostCalculator;
import io.github.patchatlas.observability.PricingReference;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.RunAttemptView;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunListPage;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.RunSubmission;
import io.github.patchatlas.run.RunSummary;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class RunDtos {

    private RunDtos() {}

    static RunSubmission toSubmission(RunCreateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("body is required");
        }
        if (req.mode() == null || req.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        if (req.javaVersion() == null || req.javaVersion().isBlank()) {
            throw new IllegalArgumentException("javaVersion is required");
        }
        if (req.networkMode() == null || req.networkMode().isBlank()) {
            throw new IllegalArgumentException("networkMode is required");
        }
        if (req.sourceSnapshots() == null) {
            throw new IllegalArgumentException("sourceSnapshots is required");
        }
        // 字段必须出现：null = 缺失；显式 "" = 根模块
        if (req.modulePath() == null) {
            throw new IllegalArgumentException("modulePath is required (use empty string for root module)");
        }
        VerificationMode mode = VerificationMode.valueOf(req.mode().trim().toUpperCase());
        MavenNetworkMode network = MavenNetworkMode.valueOf(req.networkMode().trim().toUpperCase());
        List<SourceSnapshot> snaps = new ArrayList<>(req.sourceSnapshots().size());
        for (RunCreateRequest.SourceSnapshotDto dto : req.sourceSnapshots()) {
            if (dto == null) {
                throw new IllegalArgumentException("sourceSnapshots entry must not be null");
            }
            snaps.add(new SourceSnapshot(dto.relativePath(), dto.content()));
        }
        String modulePath = req.modulePath();
        return new RunSubmission(
                mode,
                req.caseId(),
                req.repositoryUrl(),
                req.license(),
                req.issueUrl(),
                req.issueTitle(),
                req.issueBody(),
                req.buggyRevision(),
                req.fixedRevision(),
                modulePath,
                req.javaVersion().trim(),
                network,
                snaps);
    }

    static RunListResponse toListResponse(RunListPage page) {
        return new RunListResponse(
                page.items().stream().map(RunDtos::toItem).toList(), page.nextCursor().orElse(null));
    }

    private static RunListResponse.Item toItem(RunSummary s) {
        return new RunListResponse.Item(
                s.runId(),
                s.mode().name(),
                s.state().name(),
                s.issueTitle(),
                s.repositoryUrl(),
                s.verdict().map(Enum::name).orElse(null),
                s.failureCategory().map(Enum::name).orElse(null),
                s.createdAt(),
                s.updatedAt(),
                s.completedAt());
    }

    static RunDetailResponse toDetailResponse(RunDetailView d) {
        return toDetailResponse(d, Optional.empty());
    }

    static RunDetailResponse toDetailResponse(RunDetailView d, Optional<PricingReference> pricing) {
        Optional<RunDetailView.CandidateView> candidate = d.candidate();
        RunDetailResponse.Result result = null;
        if (d.state() == RunState.COMPLETED) {
            result = new RunDetailResponse.Result(
                    d.verdict().map(Enum::name).orElse(null), null, null, null);
        } else if (d.state() == RunState.FAILED) {
            RunFailure f = d.failure().orElseThrow();
            result = new RunDetailResponse.Result(
                    null, f.stage().name(), f.category().name(), f.summary());
        }
        return new RunDetailResponse(
                d.runId(),
                d.mode().name(),
                d.purpose().name(),
                d.state().name(),
                d.caseId(),
                d.createdAt(),
                d.updatedAt(),
                d.completedAt(),
                new RunDetailResponse.Input(
                        d.input().repositoryUrl(),
                        d.input().issueUrl(),
                        d.input().issueTitle(),
                        d.input().issueBody(),
                        d.input().buggyRevision(),
                        d.input().fixedRevision(),
                        d.input().modulePath()),
                new RunDetailResponse.ExecutionPolicy(
                        d.executionPolicy().javaVersion(),
                        d.executionPolicy().networkMode().name()),
                new RunDetailResponse.Generation(
                        d.generation().attemptCount(),
                        d.generation().modelProvider(),
                        d.generation().modelName(),
                        d.generation().inputTokens(),
                        d.generation().outputTokens(),
                        d.generation().totalTokens(),
                        d.generation().usageRecordCount(),
                        d.generation().usageStatus().name(),
                        estimatedCost(d.generation(), pricing)),
                candidate
                        .map(c -> new RunDetailResponse.Candidate(
                                c.patchText(),
                                c.patchSha256(),
                                c.targetTest().className(),
                                c.targetTest().methodName(),
                                c.provenance().name()))
                        .orElse(null),
                result,
                d.attempts().stream().map(RunDtos::toAttempt).toList());
    }

    private static RunDetailResponse.EstimatedCost estimatedCost(
            RunDetailView.GenerationMeta generation, Optional<PricingReference> pricing) {
        var status = generation.usageStatus();
        if (status == io.github.patchatlas.run.RecordedUsageStatus.TRACKING_UNAVAILABLE) {
            return null;
        }
        if (status == io.github.patchatlas.run.RecordedUsageStatus.NONE_RECORDED
                && generation.attemptCount() > 0) {
            return null;
        }
        return EstimatedModelCostCalculator.estimate(
                        pricing.orElse(null),
                        generation.modelProvider(),
                        generation.modelName(),
                        generation.inputTokens(),
                        generation.outputTokens(),
                        generation.totalTokens())
                .map(RunDtos::toEstimatedCost)
                .orElse(null);
    }

    private static RunDetailResponse.EstimatedCost toEstimatedCost(EstimatedModelCost cost) {
        return new RunDetailResponse.EstimatedCost(
                cost.amount().toPlainString(),
                cost.currency(),
                cost.pricingEffectiveDate().toString(),
                cost.pricingSource());
    }

    private static RunDetailResponse.Attempt toAttempt(RunAttemptView a) {
        return new RunDetailResponse.Attempt(
                a.replayRound(),
                a.side().name(),
                a.attemptOrdinal(),
                a.phase().name(),
                a.outcome().map(Enum::name).orElse(null),
                a.targetEvidence().name(),
                a.diagnostic().orElse(null),
                a.sandboxStatus().map(Enum::name).orElse(null),
                a.exitCode().orElse(null),
                a.elapsedMs().orElse(null),
                a.timedOut().orElse(null),
                a.commandJson().orElse(null),
                a.image().orElse(null),
                a.limitsJson().orElse(null),
                a.networkMode().orElse(null),
                a.logSummary().orElse(null),
                a.targetTestCase()
                        .map(t -> new RunDetailResponse.TargetTestCase(
                                t.className(),
                                t.methodName(),
                                t.status(),
                                t.message().orElse(null),
                                t.elapsedMs().orElse(null),
                                t.exceptionType().orElse(null)))
                        .orElse(null),
                a.evidenceSchemaVersion());
    }
}

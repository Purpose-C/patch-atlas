package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.PatchRejectionCategory;
import io.github.patchatlas.run.RunEvents;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Formal Replay 编排：恢复工作区、再次 Gate、执行并完成 Run。 */
public final class FormalReplayCoordinator {

    private final PostgresRunStore store;
    private final PatchGate patchGate;
    private final CandidateWorkspaceFactory workspaceFactory;
    private final DependencyWarmupRunner dependencyWarmupRunner;
    private final RunReplayer replayer;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    public FormalReplayCoordinator(
            PostgresRunStore store,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            DependencyWarmupRunner dependencyWarmupRunner,
            RunReplayer replayer,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.patchGate = Objects.requireNonNull(patchGate, "patchGate");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.dependencyWarmupRunner =
                Objects.requireNonNull(dependencyWarmupRunner, "dependencyWarmupRunner");
        this.replayer = Objects.requireNonNull(replayer, "replayer");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    }

    public RunDetails run(ClaimedRun claimed, String owner) {
        PersistedCandidatePatch candidate = claimed
                .candidate()
                .orElseThrow(() -> new IllegalStateException("REPLAYING without candidate"));

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval)) {
            ClaimedRun opened = beat.openReplayRound();
            RunEvents.replayStarted(opened.runId(), opened.replayRound());
            ReplayWorkspaceProjection projection =
                    store.loadReplayWorkspaceProjection(opened.runId());
            RunPurpose purpose = store.findRunDetail(opened.runId()).orElseThrow().purpose();
            List<CandidateWorkspaceFactory.WorkspaceSession> sessions = new ArrayList<>(2);
            try {
                final PreparedReplayWorkspace prepared;
                try {
                    prepared = prepareWorkspaces(opened, projection, candidate, sessions);
                } catch (PatchGateRejectedException gateEx) {
                    return failed(beat, gateEx.failure());
                } catch (DependencyWarmupFailedException warmupEx) {
                    return failed(
                            beat,
                            new RunFailure(
                                    FailureStage.REPLAY,
                                    FailureCategory.REPLAY_SYSTEM_ERROR,
                                    warmupEx.getMessage()));
                } catch (StaleClaimException stale) {
                    throw stale;
                } catch (Exception ex) {
                    return failed(beat, WorkspaceFailureSummarizer.failure(
                            ex, purpose, "replay workspace prepare failed: "));
                }

                try {
                    RunDetails completed = beat.complete(replayer.replay(opened, candidate, prepared));
                    completed.verdict()
                            .ifPresent(verdict -> RunEvents.runCompleted(
                                    completed.runId(), completed.mode(), verdict));
                    return completed;
                } catch (StaleClaimException stale) {
                    throw stale;
                } catch (Exception ex) {
                    return failed(
                            beat,
                            new RunFailure(
                                    FailureStage.REPLAY,
                                    FailureCategory.REPLAY_SYSTEM_ERROR,
                                    bound("replay failed: " + ex.getClass().getSimpleName())));
                }
            } finally {
                closeSessions(sessions);
            }
        }
    }

    private PreparedReplayWorkspace prepareWorkspaces(
            ClaimedRun claimed,
            ReplayWorkspaceProjection projection,
            PersistedCandidatePatch candidate,
            List<CandidateWorkspaceFactory.WorkspaceSession> sessions)
            throws Exception {
        CandidateDraft draft = new CandidateDraft(candidate.patchText(), candidate.targetTest());
        return switch (projection) {
            case ReplayWorkspaceProjection.Live live -> {
                CandidateWorkspaceFactory.WorkspaceSession session = workspaceFactory.openForRevision(
                        claimed,
                        live.repositoryUrl(),
                        live.buggyRevision(),
                        live.modulePath(),
                        live.executionPolicy());
                sessions.add(session);
                warmDependencies(session, draft);
                applyCandidate(session, draft);
                yield new PreparedReplayWorkspace.Live(
                        session.workspace(), session.modulePath(), session.executionPolicy());
            }
            case ReplayWorkspaceProjection.Historical historical -> {
                CandidateWorkspaceFactory.WorkspaceSession buggy = workspaceFactory.openForRevision(
                        claimed,
                        historical.repositoryUrl(),
                        historical.buggyRevision(),
                        historical.modulePath(),
                        historical.executionPolicy());
                sessions.add(buggy);
                CandidateWorkspaceFactory.WorkspaceSession fixed = workspaceFactory.openForRevision(
                        claimed,
                        historical.repositoryUrl(),
                        historical.fixedRevision(),
                        historical.modulePath(),
                        historical.executionPolicy());
                sessions.add(fixed);
                warmDependencies(buggy, draft);
                warmDependencies(fixed, draft);
                applyCandidate(buggy, draft);
                if (candidate.provenance() == TestPatchProvenance.KNOWN_TRIGGER) {
                    verifyCandidateAlreadyApplied(fixed, draft);
                } else {
                    applyCandidate(fixed, draft);
                }
                yield new PreparedReplayWorkspace.Historical(
                        buggy.workspace(),
                        fixed.workspace(),
                        historical.modulePath(),
                        buggy.executionPolicy());
            }
        };
    }

    private void applyCandidate(
            CandidateWorkspaceFactory.WorkspaceSession session, CandidateDraft draft) {
        PatchPreparationResult prepared = patchGate.prepare(
                session.workspace(),
                session.modulePath(),
                draft,
                session.executionPolicy());
        if (prepared instanceof PatchPreparationResult.RejectedCandidate rejected) {
            throw new PatchGateRejectedException(toPatchGateFailure(rejected));
        }
    }

    private void verifyCandidateAlreadyApplied(
            CandidateWorkspaceFactory.WorkspaceSession session, CandidateDraft draft) {
        PatchPreparationResult prepared = patchGate.verifyAlreadyApplied(
                session.workspace(),
                session.modulePath(),
                draft,
                session.executionPolicy());
        if (prepared instanceof PatchPreparationResult.RejectedCandidate rejected) {
            throw new PatchGateRejectedException(toPatchGateFailure(rejected));
        }
    }

    private void warmDependencies(
            CandidateWorkspaceFactory.WorkspaceSession session, CandidateDraft draft) {
        MavenTestCommand command = new MavenTestCommand(
                session.modulePath(),
                draft.targetTest().className() + "#" + draft.targetTest().methodName(),
                session.executionPolicy().networkMode(),
                session.executionPolicy().javaVersion());
        dependencyWarmupRunner
                .warm(session.workspace(), command)
                .ifPresent(reason -> {
                    throw new DependencyWarmupFailedException(reason);
                });
    }

    private static void closeSessions(List<CandidateWorkspaceFactory.WorkspaceSession> sessions) {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            try {
                sessions.get(i).close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static RunFailure toPatchGateFailure(
            PatchPreparationResult.RejectedCandidate rejected) {
        FailureCategory category =
                rejected.category() == PatchRejectionCategory.WORKSPACE_UNSAFE
                        ? FailureCategory.WORKSPACE_UNSAFE
                        : FailureCategory.PATCH_REJECTED;
        FailureStage stage = category == FailureCategory.WORKSPACE_UNSAFE
                ? FailureStage.WORKSPACE
                : FailureStage.PATCH_GATE;
        return new RunFailure(stage, category, rejected.reason());
    }

    private static RunDetails failed(LeaseHeartbeat beat, RunFailure failure) {
        RunDetails details = beat.fail(failure);
        RunEvents.runFailed(details.runId(), details.mode(), failure);
        return details;
    }

    private static String bound(String summary) {
        return summary.length() <= RunFailure.MAX_SUMMARY_CHARS
                ? summary
                : summary.substring(0, RunFailure.MAX_SUMMARY_CHARS);
    }

    private static final class PatchGateRejectedException extends RuntimeException {
        private final RunFailure failure;

        private PatchGateRejectedException(RunFailure failure) {
            super(failure.summary());
            this.failure = failure;
        }

        private RunFailure failure() {
            return failure;
        }
    }

    private static final class DependencyWarmupFailedException extends RuntimeException {
        private DependencyWarmupFailedException(String message) {
            super(message);
        }
    }
}

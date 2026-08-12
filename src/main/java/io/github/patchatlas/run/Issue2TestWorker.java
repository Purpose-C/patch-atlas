package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.CandidateGenerationCoordinator;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.PatchRejectionCategory;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.SideReplayRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Issue2Test 协调：GENERATING 委托 {@link CandidateGenerationCoordinator}；REPLAYING 自管。
 */
public final class Issue2TestWorker {

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(15);
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(2);

    private final PostgresRunStore store;
    private final CandidateGenerationCoordinator generationCoordinator;
    private final PatchGate patchGate;
    private final CandidateWorkspaceFactory workspaceFactory;
    private final RunReplayer replayer;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    public Issue2TestWorker(
            PostgresRunStore store,
            TestGenerator generator,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            SideReplayRunner sideReplayRunner,
            RunReplayer replayer) {
        this(
                store,
                new CandidateGenerationCoordinator(generator, patchGate, workspaceFactory, sideReplayRunner),
                patchGate,
                workspaceFactory,
                replayer,
                DEFAULT_LEASE,
                DEFAULT_HEARTBEAT);
    }

    public Issue2TestWorker(
            PostgresRunStore store,
            CandidateGenerationCoordinator generationCoordinator,
            PatchGate patchGate,
            CandidateWorkspaceFactory workspaceFactory,
            RunReplayer replayer,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.generationCoordinator =
                Objects.requireNonNull(generationCoordinator, "generationCoordinator");
        this.patchGate = Objects.requireNonNull(patchGate, "patchGate");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.replayer = Objects.requireNonNull(replayer, "replayer");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseDuration");
        }
    }

    public Optional<RunDetails> processNext(String owner) {
        Objects.requireNonNull(owner, "owner");
        Optional<ClaimedRun> claimed = store.claimNext(owner, leaseDuration);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(process(claimed.get(), owner));
    }

    private RunDetails process(ClaimedRun claimed, String owner) {
        ClaimedRun current = claimed;
        if (current.state() == RunState.GENERATING) {
            Optional<ClaimedRun> afterGenerate = generatePhase(current, owner);
            if (afterGenerate.isEmpty()) {
                return store.findRun(claimed.runId()).orElseThrow();
            }
            current = afterGenerate.get();
        }
        if (current.state() == RunState.REPLAYING) {
            return replayPhase(current, owner);
        }
        throw new IllegalStateException("unexpected claimed state " + current.state());
    }

    private Optional<ClaimedRun> generatePhase(ClaimedRun claimed, String owner) {
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval)) {
            GenerationInput input = store.loadGenerationInput(claimed.runId());
            GenerationRunSession session = new LeaseHeartbeatGenerationRunSession(store, beat);
            CandidateGenerationCoordinator.Result result = generationCoordinator.run(input, session);
            return switch (result) {
                case CandidateGenerationCoordinator.Result.CandidateCommitted committed ->
                        Optional.of(committed.claim());
                case CandidateGenerationCoordinator.Result.RunFailed ignored -> Optional.empty();
            };
        }
    }

    private RunDetails replayPhase(ClaimedRun claimed, String owner) {
        PersistedCandidatePatch candidate = claimed
                .candidate()
                .orElseThrow(() -> new IllegalStateException("REPLAYING without candidate"));

        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), owner, leaseDuration, heartbeatInterval)) {
            ClaimedRun opened = beat.openReplayRound();
            ReplayWorkspaceProjection projection =
                    store.loadReplayWorkspaceProjection(opened.runId());

            List<CandidateWorkspaceFactory.WorkspaceSession> sessions = new ArrayList<>(2);
            try {
                final PreparedReplayWorkspace preparedWs;
                try {
                    preparedWs = prepareReplayWorkspaces(opened, projection, candidate, sessions);
                } catch (PatchGateRejectedException gateEx) {
                    return beat.fail(gateEx.failure());
                } catch (StaleClaimException stale) {
                    throw stale;
                } catch (Exception ex) {
                    return beat.fail(new RunFailure(
                            FailureStage.WORKSPACE,
                            FailureCategory.WORKSPACE_UNSAFE,
                            bound("replay workspace prepare failed: "
                                    + ex.getClass().getSimpleName())));
                }

                try {
                    var result = replayer.replay(opened, candidate, preparedWs);
                    return beat.complete(result);
                } catch (StaleClaimException stale) {
                    throw stale;
                } catch (Exception ex) {
                    return beat.fail(new RunFailure(
                            FailureStage.REPLAY,
                            FailureCategory.REPLAY_SYSTEM_ERROR,
                            bound("replay failed: " + ex.getClass().getSimpleName())));
                }
            } finally {
                closeSessions(sessions);
            }
        }
    }

    private PreparedReplayWorkspace prepareReplayWorkspaces(
            ClaimedRun claimed,
            ReplayWorkspaceProjection projection,
            PersistedCandidatePatch candidate,
            List<CandidateWorkspaceFactory.WorkspaceSession> sessions)
            throws Exception {
        CandidateDraft draft = new CandidateDraft(candidate.patchText(), candidate.targetTest());

        return switch (projection) {
            case ReplayWorkspaceProjection.Live live -> {
                CandidateWorkspaceFactory.WorkspaceSession session = workspaceFactory.openForRevision(
                        claimed, live.repositoryUrl(), live.buggyRevision(), live.modulePath());
                sessions.add(session);
                applyCandidate(session, draft);
                yield new PreparedReplayWorkspace.Live(
                        session.workspace(), session.modulePath(), session.networkMode());
            }
            case ReplayWorkspaceProjection.Historical historical -> {
                CandidateWorkspaceFactory.WorkspaceSession buggy = workspaceFactory.openForRevision(
                        claimed,
                        historical.repositoryUrl(),
                        historical.buggyRevision(),
                        historical.modulePath());
                sessions.add(buggy);
                CandidateWorkspaceFactory.WorkspaceSession fixed = workspaceFactory.openForRevision(
                        claimed,
                        historical.repositoryUrl(),
                        historical.fixedRevision(),
                        historical.modulePath());
                sessions.add(fixed);
                applyCandidate(buggy, draft);
                applyCandidate(fixed, draft);
                yield new PreparedReplayWorkspace.Historical(
                        buggy.workspace(),
                        fixed.workspace(),
                        historical.modulePath(),
                        buggy.networkMode());
            }
        };
    }

    private void applyCandidate(
            CandidateWorkspaceFactory.WorkspaceSession session, CandidateDraft draft) {
        PatchPreparationResult prepared = patchGate.prepare(
                session.workspace(),
                session.modulePath(),
                draft,
                session.networkMode());
        if (prepared instanceof PatchPreparationResult.RejectedCandidate rejected) {
            throw new PatchGateRejectedException(toPatchGateFailure(rejected));
        }
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

    private static RunFailure toPatchGateFailure(PatchPreparationResult.RejectedCandidate rejected) {
        FailureCategory category =
                rejected.category() == PatchRejectionCategory.WORKSPACE_UNSAFE
                        ? FailureCategory.WORKSPACE_UNSAFE
                        : FailureCategory.PATCH_REJECTED;
        FailureStage stage =
                category == FailureCategory.WORKSPACE_UNSAFE
                        ? FailureStage.WORKSPACE
                        : FailureStage.PATCH_GATE;
        return new RunFailure(stage, category, rejected.reason());
    }

    private static String bound(String summary) {
        if (summary.length() <= RunFailure.MAX_SUMMARY_CHARS) {
            return summary;
        }
        return summary.substring(0, RunFailure.MAX_SUMMARY_CHARS);
    }

    static final class PatchGateRejectedException extends RuntimeException {
        private final RunFailure failure;

        PatchGateRejectedException(RunFailure failure) {
            super(failure.summary());
            this.failure = failure;
        }

        RunFailure failure() {
            return failure;
        }
    }
}

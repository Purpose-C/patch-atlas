package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.ExcludedSource;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.SelectedSource;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.Selection;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 定位阶段编排：PINNED 透传已有快照，HEURISTIC 现场跑启发式。
 *
 * <p>不持有 Store；写入只经 {@link LocatingRunSession}。
 */
public final class LocatingCoordinator {

    public sealed interface Result permits Result.ContextCommitted, Result.RunFailed {
        record ContextCommitted(ClaimedRun claim) implements Result {}

        record RunFailed(RunDetails details) implements Result {}
    }

    private final CandidateWorkspaceFactory workspaces;
    private final BuggyRepositoryReader repositoryReader;
    private final BuggyOnlyGeneratorContextBuilder contextBuilder;

    public LocatingCoordinator(
            CandidateWorkspaceFactory workspaces,
            BuggyRepositoryReader repositoryReader,
            BuggyOnlyGeneratorContextBuilder contextBuilder) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.repositoryReader = Objects.requireNonNull(repositoryReader, "repositoryReader");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    }

    public Result run(
            ClaimedRun claimed, GenerationInput input, LocatingRunSession session, RunPurpose purpose) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(purpose, "purpose");
        try {
            if (!input.sourceSnapshots().isEmpty()) {
                return pinned(input, session);
            }
            return heuristic(claimed, input, session);
        } catch (StaleClaimException stale) {
            throw stale;
        } catch (Exception ex) {
            return new Result.RunFailed(session.fail(WorkspaceFailureSummarizer.failure(ex, purpose)));
        }
    }

    private Result pinned(GenerationInput input, LocatingRunSession session) {
        session.replaceTrace(pinnedTraces(input.sourceSnapshots()));
        return new Result.ContextCommitted(
                session.commitContext(ContextOrigin.PINNED, input.sourceSnapshots()));
    }

    private Result heuristic(ClaimedRun claimed, GenerationInput input, LocatingRunSession session)
            throws Exception {
        try (CandidateWorkspaceFactory.WorkspaceSession workspace = workspaces.open(claimed, input)) {
            var files = repositoryReader.readJavaFiles(
                    workspace.workspace(), input.generatorContext().buggyRevision());
            Selection selection = contextBuilder.build(
                    input.generatorContext(), input.issueTitle(), input.issueBody(), files);
            session.replaceTrace(toTrace(selection));
            if (selection.snapshots().isEmpty()) {
                return new Result.RunFailed(session.fail(new RunFailure(
                        FailureStage.LOCATING,
                        FailureCategory.LOCATING_NO_CONTEXT,
                        "heuristic locating selected no source snapshots")));
            }
            return new Result.ContextCommitted(
                    session.commitContext(ContextOrigin.HEURISTIC, selection.snapshots()));
        }
    }

    private static List<LocatingTraceStep> pinnedTraces(List<SourceSnapshot> snapshots) {
        List<LocatingTraceStep> steps = new ArrayList<>(snapshots.size());
        int seq = 0;
        for (SourceSnapshot snapshot : snapshots) {
            steps.add(LocatingTraceStep.of(
                    seq++, LocatingStepKind.SELECTION, snapshot.relativePath(), "PINNED", "{}"));
        }
        return List.copyOf(steps);
    }

    static List<LocatingTraceStep> toTrace(Selection selection) {
        List<LocatingTraceStep> steps = new ArrayList<>();
        int seq = 0;
        for (SelectedSource selected : selection.selected()) {
            steps.add(LocatingTraceStep.of(
                    seq++,
                    LocatingStepKind.SELECTION,
                    selected.snapshot().relativePath(),
                    selected.reason().name(),
                    "{}"));
        }
        for (ExcludedSource excluded : selection.excluded()) {
            steps.add(LocatingTraceStep.of(
                    seq++,
                    LocatingStepKind.EXCLUSION,
                    excluded.relativePath(),
                    excluded.reason().name(),
                    "{}"));
        }
        return List.copyOf(steps);
    }
}

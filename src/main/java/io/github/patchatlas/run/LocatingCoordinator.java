package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.ExcludedSource;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.ExclusionReason;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.SelectedSource;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder.Selection;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 定位阶段编排：PINNED 透传、HEURISTIC 现场选择，或文本工具循环。
 *
 * <p>不持有 Store；写入只经 {@link LocatingRunSession}。
 */
public final class LocatingCoordinator {

    public sealed interface Result permits Result.ContextCommitted, Result.RunFailed {
        record ContextCommitted(ClaimedRun claim) implements Result {}

        record RunFailed(RunDetails details) implements Result {}
    }

    @FunctionalInterface
    public interface TextToolsLoop {
        Result run(ClaimedRun claimed, GenerationInput input, LocatingRunSession session, Path workspace);
    }

    private final CandidateWorkspaceFactory workspaces;
    private final BuggyRepositoryReader repositoryReader;
    private final BuggyOnlyGeneratorContextBuilder contextBuilder;
    private final TextToolsLoop textTools;

    public LocatingCoordinator(
            CandidateWorkspaceFactory workspaces,
            BuggyRepositoryReader repositoryReader,
            BuggyOnlyGeneratorContextBuilder contextBuilder) {
        this(workspaces, repositoryReader, contextBuilder, null);
    }

    public LocatingCoordinator(
            CandidateWorkspaceFactory workspaces,
            BuggyRepositoryReader repositoryReader,
            BuggyOnlyGeneratorContextBuilder contextBuilder,
            TextToolsLoop textTools) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.repositoryReader = Objects.requireNonNull(repositoryReader, "repositoryReader");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.textTools = textTools;
    }

    public Result run(
            ClaimedRun claimed, GenerationInput input, LocatingRunSession session, RunPurpose purpose) {
        return run(claimed, input, session, purpose, ContextOrigin.HEURISTIC);
    }

    public Result run(
            ClaimedRun claimed,
            GenerationInput input,
            LocatingRunSession session,
            RunPurpose purpose,
            ContextOrigin requestedOrigin) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(requestedOrigin, "requestedOrigin");
        try {
            if (!input.sourceSnapshots().isEmpty()) {
                return pinned(input, session);
            }
            if (requestedOrigin == ContextOrigin.TEXT_TOOLS) {
                return textTools(claimed, input, session);
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

    private Result textTools(ClaimedRun claimed, GenerationInput input, LocatingRunSession session)
            throws Exception {
        if (textTools == null) {
            return new Result.RunFailed(session.fail(new RunFailure(
                    FailureStage.LOCATING,
                    FailureCategory.LOCATING_NO_CONTEXT,
                    "text tools locating is not configured")));
        }
        try (CandidateWorkspaceFactory.WorkspaceSession workspace = workspaces.open(claimed, input)) {
            return textTools.run(claimed, input, session, workspace.workspace());
        }
    }

    private Result heuristic(ClaimedRun claimed, GenerationInput input, LocatingRunSession session)
            throws Exception {
        try (CandidateWorkspaceFactory.WorkspaceSession workspace = workspaces.open(claimed, input)) {
            BuggyRepositoryReader.JavaFiles read = repositoryReader.readJavaFiles(
                    workspace.workspace(),
                    input.generatorContext().buggyRevision(),
                    input.generatorContext().modulePath(),
                    BuggyRepositoryReader.MAX_JAVA_FILES);
            Selection selection = contextBuilder.build(
                    input.generatorContext(), input.issueTitle(), input.issueBody(), read.files());
            List<LocatingTraceStep> steps = new ArrayList<>(toTrace(selection));
            if (read.truncated()) {
                steps.add(LocatingTraceStep.of(
                        steps.size(),
                        LocatingStepKind.EXCLUSION,
                        ".",
                        ExclusionReason.READ_LIMIT.name(),
                        "{}"));
            }
            session.replaceTrace(steps);
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

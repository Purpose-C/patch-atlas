package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.VerificationMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocatingCoordinatorTest {

    @TempDir
    Path temp;

    @Test
    void pinnedPathKeepsSnapshotsAndWritesPinnedTrace() {
        SourceSnapshot snapshot = new SourceSnapshot("src/A.java", "class A {}");
        ClaimedRun claimed = locatingClaim();
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        LocatingCoordinator coordinator = new LocatingCoordinator(
                unusedWorkspaces(), new BuggyRepositoryReader(), new BuggyOnlyGeneratorContextBuilder());

        LocatingCoordinator.Result result = coordinator.run(claimed, input(List.of(snapshot)), session);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.origin()).isEqualTo(ContextOrigin.PINNED);
        assertThat(session.committedSnapshots()).containsExactly(snapshot);
        assertThat(session.traces()).extracting(LocatingTraceStep::reason).containsExactly("PINNED");
        assertThat(session.claim().state()).isEqualTo(RunState.GENERATING);
    }

    @Test
    void heuristicPathWritesSelectionTrace() throws Exception {
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("git"));
        Path root = java.nio.file.Files.createDirectories(temp.resolve("ws"));
        ClaimedRun claimed = locatingClaim();
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder());
        GenerationInput input = new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        fixture.buggySha(),
                        "",
                        "21"),
                "NPE in fixtures/OldTest.java",
                "class OldTest fails",
                List.of());

        LocatingCoordinator.Result result = coordinator.run(claimed, input, session);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.origin()).isEqualTo(ContextOrigin.HEURISTIC);
        assertThat(session.committedSnapshots()).isNotEmpty();
        assertThat(session.traces()).isNotEmpty();
        assertThat(session.traces())
                .anyMatch(step -> step.kind() == LocatingStepKind.SELECTION);
        assertThat(session.traces()).hasSize(
                session.committedSnapshots().size()
                        + (int) session.traces().stream()
                                .filter(step -> step.kind() == LocatingStepKind.EXCLUSION)
                                .count());
    }

    @Test
    void workspaceFailureFailsTheRunWithoutCommit() {
        ClaimedRun claimed = locatingClaim();
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        CandidateWorkspaceFactory failing = (run, url, revision, module, policy) -> {
            throw new IllegalStateException("clone failed");
        };
        LocatingCoordinator coordinator = new LocatingCoordinator(
                failing, new BuggyRepositoryReader(), new BuggyOnlyGeneratorContextBuilder());

        LocatingCoordinator.Result result = coordinator.run(claimed, input(List.of()), session);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(session.origin()).isNull();
        assertThat(session.claim().state()).isEqualTo(RunState.LOCATING);
    }

    private static GenerationInput input(List<SourceSnapshot> snapshots) {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "a".repeat(40),
                        "",
                        "21"),
                "t",
                "b",
                snapshots);
    }

    private static ClaimedRun locatingClaim() {
        return new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.LOCATING,
                1,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.empty());
    }

    private static CandidateWorkspaceFactory unusedWorkspaces() {
        return (run, url, revision, module, policy) -> {
            throw new AssertionError("PINNED must not open a workspace");
        };
    }
}

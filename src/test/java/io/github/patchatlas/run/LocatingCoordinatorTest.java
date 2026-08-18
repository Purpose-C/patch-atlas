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

        LocatingCoordinator.Result result =
                coordinator.run(claimed, input(List.of(snapshot)), session, RunPurpose.STANDARD);

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

        LocatingCoordinator.Result result =
                coordinator.run(claimed, input, session, RunPurpose.STANDARD);

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

        LocatingCoordinator.Result result =
                coordinator.run(claimed, input(List.of()), session, RunPurpose.STANDARD);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(session.origin()).isNull();
        assertThat(session.claim().state()).isEqualTo(RunState.LOCATING);
    }

    @Test
    void emptyHeuristicSelectionFailsAsLocatingNotGeneration() throws Exception {
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("empty"));
        Path root = java.nio.file.Files.createDirectories(temp.resolve("ws-empty"));
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
                "复现时偶发空指针",
                "没有路径也没有类名",
                List.of());

        LocatingCoordinator.Result result =
                coordinator.run(claimed, input, session, RunPurpose.STANDARD);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        RunDetails details = ((LocatingCoordinator.Result.RunFailed) result).details();
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.LOCATING);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.LOCATING_NO_CONTEXT);
        assertThat(session.origin()).isNull();
        assertThat(session.claim().state()).isEqualTo(RunState.LOCATING);
    }

    @Test
    void heuristicReadLimitIsRecordedWithoutChangingSmallRepoSelection() throws Exception {
        Path origin = java.nio.file.Files.createDirectories(temp.resolve("cap-origin"));
        String buggySha;
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init().setDirectory(origin.toFile()).call()) {
            Path src = java.nio.file.Files.createDirectories(origin.resolve("mod/src"));
            java.nio.file.Files.createDirectories(origin.resolve("other/src"));
            java.nio.file.Files.writeString(origin.resolve("other/src/Outside.java"), "class Outside {}");
            for (int i = 0; i < BuggyRepositoryReader.MAX_JAVA_FILES + 3; i++) {
                java.nio.file.Files.writeString(src.resolve("F" + i + ".java"), "class F" + i + " {}");
            }
            git.add().addFilepattern(".").call();
            var who = new org.eclipse.jgit.lib.PersonIdent("fixture", "fixture@example.com");
            buggySha = git.commit().setMessage("cap").setAuthor(who).setCommitter(who).call().getName();
        }
        Path root = java.nio.file.Files.createDirectories(temp.resolve("ws-cap"));
        ClaimedRun claimed = locatingClaim();
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(origin)),
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder());
        GenerationInput input = new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        buggySha,
                        "mod",
                        "21"),
                "NPE in F0.java",
                "class F0 fails",
                List.of());

        LocatingCoordinator.Result result =
                coordinator.run(claimed, input, session, RunPurpose.STANDARD);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.origin()).isEqualTo(ContextOrigin.HEURISTIC);
        assertThat(session.committedSnapshots())
                .noneMatch(snapshot -> snapshot.relativePath().contains("Outside"));
        assertThat(session.traces())
                .anyMatch(step -> step.kind() == LocatingStepKind.EXCLUSION
                        && "READ_LIMIT".equals(step.reason()));
    }

    @Test
    void textToolsWithoutLocatorFailsAsLocatingNotConfigured() {
        ClaimedRun claimed = locatingClaim();
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        LocatingCoordinator coordinator = new LocatingCoordinator(
                unusedWorkspaces(), new BuggyRepositoryReader(), new BuggyOnlyGeneratorContextBuilder());

        LocatingCoordinator.Result result = coordinator.run(
                claimed, input(List.of()), session, RunPurpose.STANDARD, ContextOrigin.TEXT_TOOLS);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(((LocatingCoordinator.Result.RunFailed) result).details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.LOCATING_NOT_CONFIGURED);
        assertThat(((LocatingCoordinator.Result.RunFailed) result).details().failure().orElseThrow().summary())
                .contains("not configured");
    }

    @Test
    void diagnosticPurposeEchoesWorkspaceErrorMessage() {
        ClaimedRun claimed = locatingClaim();
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        CandidateWorkspaceFactory failing = (run, url, revision, module, policy) -> {
            throw new IllegalStateException("clone failed");
        };
        LocatingCoordinator coordinator = new LocatingCoordinator(
                failing, new BuggyRepositoryReader(), new BuggyOnlyGeneratorContextBuilder());

        LocatingCoordinator.Result result =
                coordinator.run(claimed, input(List.of()), session, RunPurpose.DIAGNOSTIC);

        RunDetails details = ((LocatingCoordinator.Result.RunFailed) result).details();
        assertThat(details.failure().orElseThrow().summary()).contains("clone failed");
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

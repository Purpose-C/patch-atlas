package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 一次性 workspace：唯一目录 + Buggy checkout + 可对既有文件打 patch。 */
class TempCandidateWorkspaceFactoryTest {

    @TempDir
    Path temp;

    private LocalGitFixture.Fixture fixture;
    private TempCandidateWorkspaceFactory factory;
    private Path allowedRoot;

    @BeforeEach
    void setUp() throws Exception {
        fixture = LocalGitFixture.initWithExistingTest(temp.resolve("git"));
        allowedRoot = Files.createDirectories(temp.resolve("workspaces"));
        factory = new TempCandidateWorkspaceFactory(allowedRoot, LocalGitFixture.fetcher(fixture.originDir()));
    }

    @Test
    void openCreatesUniqueDirsAtExactBuggyRevision() throws Exception {
        ClaimedRun run = claimed(UUID.randomUUID());
        GenerationInput input = generationInput(fixture.buggySha());

        Path first;
        Path second;
        try (var s1 = factory.open(run, input)) {
            first = s1.workspace();
            assertThat(first).startsWith(allowedRoot);
            assertThat(first.getFileName().toString()).isNotEqualTo(run.runId().toString());
            LocalGitFixture.assertHead(first, fixture.buggySha());
            Path existing = first.resolve("src/test/java/fixtures/OldTest.java");
            assertThat(existing).exists();
            assertThat(Files.readString(existing)).contains("void already()");
            assertThat(Files.readString(existing)).doesNotContain("void added()");
        }
        // close 后应清理
        assertThat(first).doesNotExist();

        try (var s2 = factory.open(run, input)) {
            second = s2.workspace();
            assertThat(second).isNotEqualTo(first);
            assertThat(second).exists();
            LocalGitFixture.assertHead(second, fixture.buggySha());
            // 干净：无上次 patch
            assertThat(Files.readString(second.resolve("src/test/java/fixtures/OldTest.java")))
                    .doesNotContain("void added()");
        }
        assertThat(second).doesNotExist();
    }

    @Test
    void modifyExistingFilePatchAppliesOnMaterializedWorkspace() throws Exception {
        ClaimedRun run = claimed(UUID.randomUUID());
        GenerationInput input = generationInput(fixture.buggySha());
        PatchGate gate = new PatchGate(allowedRoot);

        try (var session = factory.open(run, input)) {
            var generated = new GenerationResult.GeneratedCandidate(
                    LocalGitFixture.MODIFY_EXISTING_PATCH,
                    new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD));
            PatchPreparationResult result = gate.prepare(
                    session.workspace(), "", generated, MavenNetworkMode.OFFLINE);
            assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
            String content = Files.readString(
                    session.workspace().resolve("src/test/java/fixtures/OldTest.java"),
                    StandardCharsets.UTF_8);
            assertThat(content).contains("void added()");
        }
    }

    @Test
    void rejectsWhenTargetAlreadyExists() throws Exception {
        // fetcher 要求目标不存在；预先创建同名目录应失败
        String name = TempCandidateWorkspaceFactory.uniqueDirectoryName(UUID.randomUUID());
        Files.createDirectories(allowedRoot.resolve(name));
        RepositoryWorkspaceFetcher bad = (url, sha, parent, directoryName) -> {
            // 强制使用已存在目录名
            return LocalGitFixture.fetcher(fixture.originDir())
                    .materialize(url, sha, parent, name);
        };
        TempCandidateWorkspaceFactory f = new TempCandidateWorkspaceFactory(allowedRoot, bad);
        assertThatThrownBy(() -> f.open(claimed(UUID.randomUUID()), generationInput(fixture.buggySha())))
                .isInstanceOf(Exception.class);
    }

    @Test
    void escapedWorkspaceIsRejectedWithoutDeletingHostPath() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside-host-dir"));
        Path marker = outside.resolve("do-not-delete.txt");
        Files.writeString(marker, "keep me", StandardCharsets.UTF_8);

        RepositoryWorkspaceFetcher evil = (url, sha, parent, directoryName) -> outside;
        TempCandidateWorkspaceFactory f = new TempCandidateWorkspaceFactory(allowedRoot, evil);

        assertThatThrownBy(() -> f.open(claimed(UUID.randomUUID()), generationInput(fixture.buggySha())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escaped");

        assertThat(outside).exists();
        assertThat(marker).exists().hasContent("keep me");
    }

    private static ClaimedRun claimed(UUID id) {
        return new ClaimedRun(
                id,
                VerificationMode.LIVE,
                RunState.GENERATING,
                1L,
                new RunLease(UUID.randomUUID(), "t", java.time.Instant.now().plusSeconds(60)),
                0,
                0,
                java.util.Optional.empty());
    }

    private static GenerationInput generationInput(String buggySha) {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        buggySha,
                        "",
                        "21"),
                "t",
                "b",
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }
}

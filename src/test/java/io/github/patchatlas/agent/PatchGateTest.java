package io.github.patchatlas.agent;


import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchGateTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private PatchGate gate;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        gate = new PatchGate(tempDir);
    }

    @Test
    void appliesCreateTestFileAndBindsTarget() throws Exception {
        CandidateDraft candidate = new CandidateDraft(
                FakeTestGeneratorTest.minimalCreatePatch(),
                new TargetTest("fixtures.NewTest", "works"));

        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        Path created = workspace.resolve("src/test/java/fixtures/NewTest.java");
        assertThat(Files.exists(created)).isTrue();
        assertThat(Files.readString(created)).contains("void works()");
        PatchPreparationResult.PreparedCandidate prepared =
                (PatchPreparationResult.PreparedCandidate) result;
        assertThat(prepared.command().arguments()).contains("-Dtest=fixtures.NewTest#works");
    }

    @Test
    void prepareHasNoFourArgumentOverload() {
        long fourArg = java.util.Arrays.stream(PatchGate.class.getDeclaredMethods())
                .filter(method -> "prepare".equals(method.getName()) && method.getParameterCount() == 4)
                .count();
        assertThat(fourArg).isZero();
    }

    @Test
    void verifyAlreadyAppliedHasNoFourArgumentOverload() {
        long fourArg = java.util.Arrays.stream(PatchGate.class.getDeclaredMethods())
                .filter(method -> "verifyAlreadyApplied".equals(method.getName())
                        && method.getParameterCount() == 4)
                .count();
        assertThat(fourArg).isZero();
    }

    @Test
    void inspectsCandidatePolicyWithoutWorkspaceIo() {
        CandidateDraft candidate = new CandidateDraft(
                FakeTestGeneratorTest.minimalCreatePatch(),
                new TargetTest("fixtures.NewTest", "works"));

        PatchPolicyInspection result =
                PatchGate.inspect("", candidate, MavenNetworkMode.OFFLINE);

        assertThat(result).isInstanceOf(PatchPolicyInspection.Accepted.class);
        var accepted = (PatchPolicyInspection.Accepted) result;
        assertThat(accepted.changedPaths())
                .containsExactly("src/test/java/fixtures/NewTest.java");
        assertThat(accepted.command().arguments()).contains("-Dtest=fixtures.NewTest#works");
        assertThat(Files.exists(workspace.resolve("src/test/java/fixtures/NewTest.java"))).isFalse();
    }

    @Test
    void verifiesKnownCreatePatchIsAlreadyPresentWithoutWriting() throws Exception {
        Path existing = workspace.resolve("src/test/java/fixtures/NewTest.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(
                existing,
                """
                package fixtures;

                import org.junit.jupiter.api.Test;

                class NewTest {
                  @Test
                  void works() {}
                }
                """,
                StandardCharsets.UTF_8);
        String before = Files.readString(existing);
        CandidateDraft candidate = new CandidateDraft(
                FakeTestGeneratorTest.minimalCreatePatch(),
                new TargetTest("fixtures.NewTest", "works"));

        PatchPreparationResult result = gate.verifyAlreadyApplied(
                workspace, "", candidate, MavenNetworkMode.OFFLINE, CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(Files.readString(existing)).isEqualTo(before);
    }

    @Test
    void rejectsKnownPatchThatIsNotPresentWithoutWriting() throws Exception {
        CandidateDraft candidate = new CandidateDraft(
                FakeTestGeneratorTest.minimalCreatePatch(),
                new TargetTest("fixtures.NewTest", "works"));

        PatchPreparationResult result = gate.verifyAlreadyApplied(
                workspace, "", candidate, MavenNetworkMode.OFFLINE, CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(((PatchPreparationResult.RejectedCandidate) result).category())
                .isEqualTo(PatchRejectionCategory.APPLICATION_FAILURE);
        assertThat(Files.exists(workspace.resolve("src/test/java/fixtures/NewTest.java"))).isFalse();
    }

    @Test
    void verifyAlreadyAppliedUsesCallerDiagnosticsNotInternalUnknown() throws Exception {
        Path existing = workspace.resolve("src/test/java/fixtures/OldTest.java");
        Files.createDirectories(existing.getParent());
        String alreadyApplied =
                """
                package fixtures;

                import org.junit.jupiter.api.Test;

                class OldTest {
                  @Test
                  void already() {}

                  @Test
                  void added() {}
                }
                """;
        Files.writeString(existing, alreadyApplied, StandardCharsets.UTF_8);
        CandidateDraft candidate = new CandidateDraft(
                WRONG_HEADER_COUNTS_PATCH, new TargetTest("fixtures.OldTest", "added"));

        PatchPreparationResult unknown = gate.verifyAlreadyApplied(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());
        assertThat(unknown).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(((PatchPreparationResult.RejectedCandidate) unknown).reason())
                .isEqualTo("hunk new count mismatch");
        assertThat(Files.readString(existing)).isEqualTo(alreadyApplied);

        PatchPreparationResult recounted = gate.verifyAlreadyApplied(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.of("stop", "0", "10"));
        assertThat(recounted).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(Files.readString(existing)).isEqualTo(alreadyApplied);
    }

    @Test
    void appendsToExistingTestFile() throws Exception {
        Path existing = workspace.resolve("src/test/java/fixtures/OldTest.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(
                existing,
                """
                package fixtures;

                import org.junit.jupiter.api.Test;

                class OldTest {
                  @Test
                  void already() {}
                }
                """,
                StandardCharsets.UTF_8);

        // @@ -6,2 +6,5 @@：newCount = context(2) + plus(3)
        String patch =
                """
                diff --git a/src/test/java/fixtures/OldTest.java b/src/test/java/fixtures/OldTest.java
                --- a/src/test/java/fixtures/OldTest.java
                +++ b/src/test/java/fixtures/OldTest.java
                @@ -6,2 +6,5 @@
                   @Test
                   void already() {}
                +
                +  @Test
                +  void added() {}
                """;

        CandidateDraft candidate = new CandidateDraft(
                patch, new TargetTest("fixtures.OldTest", "added"));

        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(Files.readString(existing)).contains("void added()");
        assertThat(Files.readString(existing)).contains("void already()");
    }

    @Test
    void rejectsMainSourceModification() {
        String patch =
                """
                diff --git a/src/main/java/fixtures/Evil.java b/src/main/java/fixtures/Evil.java
                new file mode 100644
                --- /dev/null
                +++ b/src/main/java/fixtures/Evil.java
                @@ -0,0 +1,1 @@
                +class Evil {}
                """;
        CandidateDraft candidate = new CandidateDraft(
                patch, new TargetTest("fixtures.Evil", "x"));

        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(((PatchPreparationResult.RejectedCandidate) result).category())
                .isEqualTo(PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH);
        assertThat(Files.exists(workspace.resolve("src/main/java/fixtures/Evil.java"))).isFalse();
    }

    @Test
    void rejectsTargetNotInPatch() {
        CandidateDraft candidate = new CandidateDraft(
                FakeTestGeneratorTest.minimalCreatePatch(),
                new TargetTest("fixtures.OtherTest", "works"));

        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(((PatchPreparationResult.RejectedCandidate) result).category())
                .isEqualTo(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH);
        assertThat(Files.exists(workspace.resolve("src/test/java/fixtures/NewTest.java"))).isFalse();
    }

    @Test
    void rejectsSymlinkInTargetPath() throws Exception {
        Path outside = tempDir.resolve("outside");
        Files.createDirectory(outside);
        Path testJava = workspace.resolve("src/test");
        Files.createDirectories(testJava);
        Files.createSymbolicLink(testJava.resolve("java"), outside);

        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                new CandidateDraft(
                        FakeTestGeneratorTest.minimalCreatePatch(),
                        new TargetTest("fixtures.NewTest", "works")),
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(((PatchPreparationResult.RejectedCandidate) result).category())
                .isEqualTo(PatchRejectionCategory.WORKSPACE_UNSAFE);
    }

    @Test
    void rejectsWorkspaceOutsideAllowedRoot() throws Exception {
        Path outsideRoot = Files.createTempDirectory("outside-gate-");
        Path outsideWs = outsideRoot.resolve("ws");
        Files.createDirectories(outsideWs);
        try {
            PatchPreparationResult result = gate.prepare(
                    outsideWs,
                    "",
                    new CandidateDraft(
                            FakeTestGeneratorTest.minimalCreatePatch(),
                            new TargetTest("fixtures.NewTest", "works")),
                    MavenNetworkMode.OFFLINE,
                    CompletionDiagnostics.unknown());
            assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
            assertThat(((PatchPreparationResult.RejectedCandidate) result).category())
                    .isEqualTo(PatchRejectionCategory.WORKSPACE_UNSAFE);
        } finally {
            try (var walk = Files.walk(outsideRoot)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void verifyAlreadyAppliedRejectsWorkspaceOutsideAllowedRoot() throws Exception {
        Path outsideRoot = Files.createTempDirectory("outside-gate-verify-");
        Path outsideWs = outsideRoot.resolve("ws");
        Files.createDirectories(outsideWs);
        try {
            PatchPreparationResult result = gate.verifyAlreadyApplied(
                    outsideWs,
                    "",
                    new CandidateDraft(
                            FakeTestGeneratorTest.minimalCreatePatch(),
                            new TargetTest("fixtures.NewTest", "works")),
                    MavenNetworkMode.OFFLINE,
                    CompletionDiagnostics.unknown());
            assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
            assertThat(((PatchPreparationResult.RejectedCandidate) result).category())
                    .isEqualTo(PatchRejectionCategory.WORKSPACE_UNSAFE);
        } finally {
            try (var walk = Files.walk(outsideRoot)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void rejectsOversizedSelectorBeforeWritingWorkspace() {
        String longClass = "a." + "B".repeat(250);
        String patch =
                """
                diff --git a/src/test/java/%s.java b/src/test/java/%s.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/%s.java
                @@ -0,0 +1,1 @@
                +class X {}
                """
                        .formatted(
                                longClass.replace('.', '/'),
                                longClass.replace('.', '/'),
                                longClass.replace('.', '/'));
        // 类名路径过长且 selector 超 256
        CandidateDraft candidate = new CandidateDraft(
                FakeTestGeneratorTest.minimalCreatePatch(),
                new TargetTest("fixtures." + "N".repeat(250), "works"));

        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(Files.exists(workspace.resolve("src/test/java/fixtures/NewTest.java"))).isFalse();
    }

    @Test
    void rejectsOutOfRangeInsertWithoutClamping() throws Exception {
        Path existing = workspace.resolve("src/test/java/fixtures/Short.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "package fixtures;\n", StandardCharsets.UTF_8);
        String before = Files.readString(existing);

        String patch =
                """
                diff --git a/src/test/java/fixtures/Short.java b/src/test/java/fixtures/Short.java
                --- a/src/test/java/fixtures/Short.java
                +++ b/src/test/java/fixtures/Short.java
                @@ -999,0 +1000,1 @@
                +// rogue
                """;
        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                new CandidateDraft(
                        patch, new TargetTest("fixtures.Short", "x")),
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(Files.readString(existing)).isEqualTo(before);
    }

    @Test
    void rejectsOversizedPatchTextAtDraftBoundary() {
        String huge = "x".repeat(65 * 1024);
        String patch = "diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java\n"
                + "new file mode 100644\n--- /dev/null\n+++ b/src/test/java/fixtures/A.java\n"
                + "@@ -0,0 +1,1 @@\n+"
                + huge
                + "\n";
        // CandidateDraft 领域边界先于 Gate 拒绝超限 patch
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CandidateDraft(patch, new TargetTest("fixtures.A", "m")));
    }

    @Test
    void preservesCrlfAndMissingTrailingNewlineOnModify() throws Exception {
        Path existing = workspace.resolve("src/test/java/fixtures/Crlf.java");
        Files.createDirectories(existing.getParent());
        // 无末尾换行的 CRLF 文件
        Files.writeString(existing, "package fixtures;\r\nclass Crlf {\r\n}", StandardCharsets.UTF_8);
        String before = Files.readString(existing);

        String patch =
                """
                diff --git a/src/test/java/fixtures/Crlf.java b/src/test/java/fixtures/Crlf.java
                --- a/src/test/java/fixtures/Crlf.java
                +++ b/src/test/java/fixtures/Crlf.java
                @@ -2,2 +2,3 @@
                 class Crlf {
                +  // note
                 }
                """;
        PatchPreparationResult result = gate.prepare(
                workspace,
                "",
                new CandidateDraft(patch, new TargetTest("fixtures.Crlf", "x")),
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());

        assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        String after = Files.readString(existing);
        assertThat(after).contains("\r\n");
        assertThat(after).doesNotEndWith("\n"); // 原文件无末尾换行
        assertThat(after).contains("// note");
        assertThat(before).doesNotContain("// note");
    }

    @Test
    void modulePathScopedToModuleTestSources() throws Exception {
        String patch =
                """
                diff --git a/core/src/test/java/com/ex/T.java b/core/src/test/java/com/ex/T.java
                new file mode 100644
                --- /dev/null
                +++ b/core/src/test/java/com/ex/T.java
                @@ -0,0 +1,6 @@
                +package com.ex;
                +import org.junit.jupiter.api.Test;
                +class T {
                +  @Test
                +  void m() {}
                +}
                """;
        PatchPreparationResult result = gate.prepare(
                workspace,
                "core",
                new CandidateDraft(patch, new TargetTest("com.ex.T", "m")),
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());
        assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(Files.exists(workspace.resolve("core/src/test/java/com/ex/T.java"))).isTrue();
    }

    @Test
    void stopFinishReasonAppliesUsingBodyCountsNotHeader() throws Exception {
        Path existing = workspace.resolve("src/test/java/fixtures/OldTest.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, EXISTING_TEST, StandardCharsets.UTF_8);
        String expected =
                """
                package fixtures;

                import org.junit.jupiter.api.Test;

                class OldTest {
                  @Test
                  void already() {}

                  @Test
                  void added() {}
                }
                """;

        CandidateDraft candidate = new CandidateDraft(
                WRONG_HEADER_COUNTS_PATCH, new TargetTest("fixtures.OldTest", "added"));

        PatchPreparationResult unknown = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.unknown());
        assertThat(unknown).isInstanceOf(PatchPreparationResult.RejectedCandidate.class);
        assertThat(((PatchPreparationResult.RejectedCandidate) unknown).reason())
                .isEqualTo("hunk new count mismatch");
        assertThat(Files.readString(existing)).isEqualTo(EXISTING_TEST);

        PatchPreparationResult accepted = gate.prepare(
                workspace,
                "",
                candidate,
                MavenNetworkMode.OFFLINE,
                CompletionDiagnostics.of("stop", "0", "10"));
        assertThat(accepted).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(Files.readString(existing)).isEqualTo(expected);
    }

    private static final String EXISTING_TEST =
            """
            package fixtures;

            import org.junit.jupiter.api.Test;

            class OldTest {
              @Test
              void already() {}
            }
            """;

    private static final String WRONG_HEADER_COUNTS_PATCH =
            """
            diff --git a/src/test/java/fixtures/OldTest.java b/src/test/java/fixtures/OldTest.java
            --- a/src/test/java/fixtures/OldTest.java
            +++ b/src/test/java/fixtures/OldTest.java
            @@ -6,99 +6,99 @@
               @Test
               void already() {}
            +
            +  @Test
            +  void added() {}
            """;
}

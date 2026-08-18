package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnifiedDiffParserTest {

    @Test
    void parsesSingleCreateFile() {
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(
                FakeTestGeneratorTest.minimalCreatePatch(), CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.files()).hasSize(1);
        assertThat(outcome.files().getFirst().kind()).isEqualTo(ParsedFileDiff.Kind.CREATE);
        assertThat(outcome.files().getFirst().path()).isEqualTo("src/test/java/fixtures/NewTest.java");
    }

    @Test
    void rejectsTrailingExplanationText() {
        String patch = FakeTestGeneratorTest.minimalCreatePatch() + "\nThis is an explanation\n";
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void rejectsDeletionLines() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,1 +1,1 @@
                -old
                +new
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE);
    }

    @Test
    void rejectsMoreThanTwoFiles() {
        String patch = FakeTestGeneratorTest.minimalCreatePatch()
                + """
                diff --git a/src/test/java/fixtures/B.java b/src/test/java/fixtures/B.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/B.java
                @@ -0,0 +1,1 @@
                +class B {}
                diff --git a/src/test/java/fixtures/C.java b/src/test/java/fixtures/C.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/C.java
                @@ -0,0 +1,1 @@
                +class C {}
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.FILE_OR_LINE_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsEmptyPatch() {
        assertThat(UnifiedDiffParser.parse("", CompletionDiagnostics.unknown()).isOk()).isFalse();
    }

    @Test
    void acceptsModifyHunkWhereNewCountIsContextPlusPlus() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,2 +1,3 @@
                 package fixtures;
                +
                 class A {}
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.files().getFirst().kind()).isEqualTo(ParsedFileDiff.Kind.MODIFY);
    }

    @Test
    void rejectsOverflowHunkLineNumbersAsMalformed() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,99999999999999999999 +1,1 @@
                +x
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void rejectsCreatePatchWithOldFileContext() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -1,1 +1,1 @@
                 package fixtures;
                +class A {}
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void acceptsIndexMetadataFromRealGitDiff() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/NewTest.java b/src/test/java/fixtures/NewTest.java
                new file mode 100644
                index 0000000..e69de29
                --- /dev/null
                +++ b/src/test/java/fixtures/NewTest.java
                @@ -0,0 +1,3 @@
                +package fixtures;
                +
                +class NewTest {}
                """;
        assertThat(UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown()).isOk()).isTrue();
    }

    @Test
    void rejectsDisguisedRenameViaMismatchedPaths() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/Old.java b/src/test/java/fixtures/New.java
                --- a/src/test/java/fixtures/Old.java
                +++ b/src/test/java/fixtures/New.java
                @@ -1,1 +1,2 @@
                 package fixtures;
                +// x
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
    }

    @Test
    void rejectsInconsistentNewStart() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,1 +999,2 @@
                 package fixtures;
                +// x
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.reason()).contains("newStart");
    }

    @Test
    void rejectsSymlinkNewFileMode() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 120000
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -0,0 +1,1 @@
                +link-target
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE);
    }

    @Test
    void rejectsArbitraryBackslashLines() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -0,0 +1,1 @@
                +class A {}
                \\ evil marker
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void acceptsExactNoNewlineMarker() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -0,0 +1,1 @@
                +class A {}
                \\ No newline at end of file
                """;
        assertThat(UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown()).isOk()).isTrue();
    }

    @Test
    void rejectsDuplicateNewlineMarker() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -0,0 +1,1 @@
                +class A {}
                \\ No newline at end of file
                \\ No newline at end of file
                """;
        assertThat(UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown()).isOk()).isFalse();
    }

    @Test
    void rejectsContentAfterNewlineMarker() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,2 +1,2 @@
                 package fixtures;
                \\ No newline at end of file
                 class A {}
                """;
        assertThat(UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown()).isOk()).isFalse();
    }

    @Test
    void rejectsCoordinateOverflowInHunkMath() {
        // oldStart 接近 Integer.MAX_VALUE，oldCount=2 → apply end 超出 int
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -2147483647,2 +2147483647,2 @@
                 a
                 b
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void rejectsLongMaxCoordinateWithoutThrowing() {
        // 内层 oldStart + 1 在 Long.MAX_VALUE 上溢出；必须 structured reject，不得 NPE
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -9223372036854775807,0 +1,1 @@
                +x
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void stopFinishReasonRecountsHunkCountsFromBody() {
        UnifiedDiffParser.ParseOutcome outcome =
                UnifiedDiffParser.parse(wrongHeaderCountsPatch(), complete("stop"));
        assertThat(outcome.isOk()).isTrue();
        ParsedFileDiff.Hunk hunk = outcome.files().getFirst().hunks().getFirst();
        assertThat(hunk.oldCount()).isEqualTo(2);
        assertThat(hunk.newCount()).isEqualTo(5);
    }

    @Test
    void toolCallsFinishReasonRecountsHunkCountsFromBody() {
        UnifiedDiffParser.ParseOutcome outcome =
                UnifiedDiffParser.parse(wrongHeaderCountsPatch(), complete("tool_calls"));
        assertThat(outcome.isOk()).isTrue();
        ParsedFileDiff.Hunk hunk = outcome.files().getFirst().hunks().getFirst();
        assertThat(hunk.oldCount()).isEqualTo(2);
        assertThat(hunk.newCount()).isEqualTo(5);
    }

    @Test
    void unknownFinishReasonStillRejectsCountMismatch() {
        UnifiedDiffParser.ParseOutcome outcome =
                UnifiedDiffParser.parse(wrongHeaderCountsPatch(), CompletionDiagnostics.unknown());
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
        assertThat(outcome.reason()).isEqualTo("hunk new count mismatch");
    }

    @Test
    void lengthFinishReasonDoesNotEnableRecount() {
        UnifiedDiffParser.ParseOutcome outcome =
                UnifiedDiffParser.parse(wrongHeaderCountsPatch(), complete("length"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.reason()).isEqualTo("hunk new count mismatch");
    }

    @Test
    void stopStillRejectsDeletionLines() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,1 +1,1 @@
                -old
                +new
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, complete("stop"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE);
    }

    @Test
    void stopStillRejectsCreatePatchWithOldFileContext() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -1,1 +1,2 @@
                 package fixtures;
                +class A {}
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, complete("stop"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.reason()).contains("create patch must not carry old-file context");
    }

    @Test
    void stopStillRejectsOverlappingHunksUsingRecountedCounts() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                --- a/src/test/java/fixtures/A.java
                +++ b/src/test/java/fixtures/A.java
                @@ -1,2 +1,3 @@
                 a
                 b
                +x
                @@ -2,1 +3,2 @@
                 b
                +y
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, complete("stop"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.reason()).contains("overlapping");
    }

    @Test
    void stopStillRejectsMoreThanTwoFiles() {
        String patch = FakeTestGeneratorTest.minimalCreatePatch()
                + """
                diff --git a/src/test/java/fixtures/B.java b/src/test/java/fixtures/B.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/B.java
                @@ -0,0 +1,1 @@
                +class B {}
                diff --git a/src/test/java/fixtures/C.java b/src/test/java/fixtures/C.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/C.java
                @@ -0,0 +1,1 @@
                +class C {}
                """;
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(patch, complete("stop"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.FILE_OR_LINE_LIMIT_EXCEEDED);
    }

    @Test
    void stopStillRejectsMoreThanTwoHundredChangedLines() {
        StringBuilder patch = new StringBuilder(
                """
                diff --git a/src/test/java/fixtures/A.java b/src/test/java/fixtures/A.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/A.java
                @@ -0,0 +1,201 @@
                """);
        for (int i = 0; i < 201; i++) {
            patch.append("+line").append(i).append('\n');
        }
        UnifiedDiffParser.ParseOutcome outcome =
                UnifiedDiffParser.parse(patch.toString(), complete("stop"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.category()).isEqualTo(PatchRejectionCategory.FILE_OR_LINE_LIMIT_EXCEEDED);
    }

    @Test
    void stopStillRejectsOversizedPatch() {
        String huge = "x".repeat(UnifiedDiffParser.MAX_PATCH_BYTES + 1);
        UnifiedDiffParser.ParseOutcome outcome = UnifiedDiffParser.parse(huge, complete("stop"));
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.reason()).isEqualTo("patch size out of bounds");
    }

    private static String wrongHeaderCountsPatch() {
        return """
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

    private static CompletionDiagnostics complete(String finishReason) {
        return CompletionDiagnostics.of(finishReason, "0", "10");
    }
}

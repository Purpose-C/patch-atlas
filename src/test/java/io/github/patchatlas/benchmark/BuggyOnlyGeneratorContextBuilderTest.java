package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.benchmark.BuggyOnlyGeneratorContextBuilder.BuggyFile;
import io.github.patchatlas.benchmark.BuggyOnlyGeneratorContextBuilder.ExclusionReason;
import io.github.patchatlas.benchmark.BuggyOnlyGeneratorContextBuilder.SelectionReason;
import io.github.patchatlas.repository.CaseManifest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuggyOnlyGeneratorContextBuilderTest {

    private static final String BLOB = "d".repeat(40);

    private final BuggyOnlyGeneratorContextBuilder builder =
            new BuggyOnlyGeneratorContextBuilder();

    @Test
    void selectsExactPathsThenClassBasenamesThenReferencingTestsDeterministically() {
        List<BuggyFile> files = new ArrayList<>(List.of(
                file("src/test/java/z/ZedStyleTest.java", "class ZedStyleTest { Zed z; }"),
                file("src/main/java/b/Foo.java", "package b; class Foo {}"),
                file("src/test/java/a/FooStyleTest.java", "class FooStyleTest { Foo f; }"),
                file("src/main/java/z/Zed.java", "package z; class Zed {}"),
                file("src/main/java/a/Foo.java", "package a; class Foo {}"),
                file("src/main/java/a/Unmentioned.java", "class Unmentioned {}")));
        Collections.reverse(files);

        var selection = builder.build(
                generatorContext(),
                "Foo fails",
                "See `src/main/java/z/Zed.java`; Foo also regressed.",
                files);

        assertThat(selection.selected())
                .extracting(selected -> selected.snapshot().relativePath())
                .containsExactly(
                        "src/main/java/z/Zed.java",
                        "src/main/java/a/Foo.java",
                        "src/main/java/b/Foo.java",
                        "src/test/java/a/FooStyleTest.java",
                        "src/test/java/z/ZedStyleTest.java");
        assertThat(selection.selected())
                .extracting(BuggyOnlyGeneratorContextBuilder.SelectedSource::reason)
                .containsExactly(
                        SelectionReason.ISSUE_EXACT_PATH,
                        SelectionReason.ISSUE_CLASS_NAME,
                        SelectionReason.ISSUE_CLASS_NAME,
                        SelectionReason.REFERENCING_TEST,
                        SelectionReason.REFERENCING_TEST);
        assertThat(selection.snapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactlyElementsOf(selection.selected().stream()
                        .map(selected -> selected.snapshot().relativePath())
                        .toList());
    }

    @Test
    void returnsEmptyContextWhenIssueLocatesNothing() {
        var selection = builder.build(
                generatorContext(),
                "lowercase words only",
                "no source hints here",
                List.of(file("src/main/java/a/Widget.java", "class Widget {}")));

        assertThat(selection.selected()).isEmpty();
        assertThat(selection.excluded()).isEmpty();
    }

    @Test
    void recordsStableReasonsForPerFileAndSerializedRequestLimits() {
        List<BuggyFile> files = new ArrayList<>();
        StringBuilder issue = new StringBuilder();
        files.add(file("src/main/java/a/Oversized.java", "x".repeat(SourceSnapshot.MAX_CONTENT_BYTES + 1)));
        issue.append("src/main/java/a/Oversized.java ");
        for (int i = 0; i < 14; i++) {
            String path = "src/main/java/b/C" + String.format("%02d", i) + ".java";
            files.add(file(path, "x".repeat(SourceSnapshot.MAX_CONTENT_BYTES)));
            issue.append(path).append(' ');
        }

        var selection = builder.build(generatorContext(), "limits", issue.toString(), files);

        assertThat(selection.selected()).isNotEmpty();
        assertThat(selection.excluded())
                .extracting(BuggyOnlyGeneratorContextBuilder.ExcludedSource::reason)
                .contains(ExclusionReason.FILE_TOO_LARGE, ExclusionReason.REQUEST_BUDGET);
    }

    @Test
    void enforcesTwelveFileLimitWhenContentBudgetStillFits() {
        List<BuggyFile> files = new ArrayList<>();
        StringBuilder issue = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            String path = "src/main/java/p/C" + String.format("%02d", i) + ".java";
            files.add(file(path, "class C" + i + " {}"));
            issue.append(path).append(' ');
        }

        var selection = builder.build(generatorContext(), "limit", issue.toString(), files);

        assertThat(selection.selected()).hasSize(GenerationInput.MAX_SNAPSHOTS);
        assertThat(selection.excluded()).singleElement()
                .extracting(BuggyOnlyGeneratorContextBuilder.ExcludedSource::reason)
                .isEqualTo(ExclusionReason.FILE_LIMIT);
    }

    private static BuggyFile file(String path, String content) {
        return new BuggyFile(path, BLOB, content);
    }

    private static CaseManifest.GeneratorContext generatorContext() {
        return new CaseManifest.GeneratorContext(
                "case-1",
                "https://github.com/ex/repo.git",
                null,
                "https://github.com/ex/repo/issues/1",
                "a".repeat(40),
                "",
                "21");
    }
}

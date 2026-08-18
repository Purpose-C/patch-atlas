package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.benchmark.RepairGroundTruthExtractor.Result;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairGroundTruthExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsProductionPathsAndExcludesTestSources() throws Exception {
        Repo repo = initRepo();
        write(repo.dir(), "src/main/java/Foo.java", "class Foo { int n; }");
        write(repo.dir(), "src/test/java/FooTest.java", "class FooTest { void extra() {} }");
        String fixed = commit(repo.git(), "fixed");

        Result result = new RepairGroundTruthExtractor()
                .extract(repo.dir(), repo.buggy(), fixed, "");

        assertThat(result).isInstanceOf(Result.Applicable.class);
        assertThat(((Result.Applicable) result).paths())
                .containsExactly("src/main/java/Foo.java")
                .doesNotContain("src/test/java/FooTest.java");
    }

    @Test
    void testOnlyRepairIsNotApplicableNotZero() throws Exception {
        Repo repo = initRepo();
        write(repo.dir(), "src/test/java/FooTest.java", "class FooTest { void extra() {} }");
        String fixed = commit(repo.git(), "tests-only");

        Result result = new RepairGroundTruthExtractor()
                .extract(repo.dir(), repo.buggy(), fixed, "");

        assertThat(result)
                .isInstanceOf(Result.NotApplicable.class)
                .isNotInstanceOf(Result.Applicable.class);
    }

    @Test
    void moduleTestRootMatchesPatchGateConvention() throws Exception {
        Repo repo = initRepo();
        write(repo.dir(), "core/src/main/java/p/Core.java", "class Core { int n; }");
        write(repo.dir(), "core/src/test/java/p/CoreTest.java", "class CoreTest {}");
        String fixed = commit(repo.git(), "module-fixed");

        Result result = new RepairGroundTruthExtractor()
                .extract(repo.dir(), repo.buggy(), fixed, "core");

        assertThat(result).isInstanceOf(Result.Applicable.class);
        assertThat(((Result.Applicable) result).paths())
                .containsExactly("core/src/main/java/p/Core.java");
    }

    @Test
    void javaAndChangelogRepairKeepsOnlyJava() throws Exception {
        Repo repo = initRepo();
        write(repo.dir(), "src/main/java/Foo.java", "class Foo { int n; }");
        write(repo.dir(), "CHANGES", "fixed lastChar off-by-one");
        String fixed = commit(repo.git(), "java-and-changes");

        Result result = new RepairGroundTruthExtractor()
                .extract(repo.dir(), repo.buggy(), fixed, "");

        assertThat(result).isInstanceOf(Result.Applicable.class);
        assertThat(((Result.Applicable) result).paths())
                .containsExactly("src/main/java/Foo.java")
                .doesNotContain("CHANGES");
    }

    @Test
    void emptyProductionSetCannotBeApplicable() {
        assertThatThrownBy(() -> new Result.Applicable(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NotApplicable");
    }

    @Test
    void invalidRevisionFormatIsRejected() {
        assertThatThrownBy(() -> new RepairGroundTruthExtractor()
                        .extract(tempDir, "not-a-sha", "0123456789abcdef0123456789abcdef01234567", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buggyRevision");
    }

    @Test
    void missingRevisionFailsClosed() throws Exception {
        Repo repo = initRepo();
        String missing = "0123456789abcdef0123456789abcdef01234567";
        assertThatThrownBy(() -> new RepairGroundTruthExtractor()
                        .extract(repo.dir(), repo.buggy(), missing, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot diff");
    }

    @Test
    void addedProductionFileIsIncludedAndDevNullIsIgnored() throws Exception {
        Repo repo = initRepo();
        write(repo.dir(), "src/main/java/Bar.java", "class Bar {}");
        String fixed = commit(repo.git(), "add-bar");

        Result result = new RepairGroundTruthExtractor()
                .extract(repo.dir(), repo.buggy(), fixed, "");

        assertThat(result).isInstanceOf(Result.Applicable.class);
        assertThat(((Result.Applicable) result).paths()).contains("src/main/java/Bar.java");
    }

    @Test
    void nonGitDirectoryFailsClosed() {
        assertThatThrownBy(() -> new RepairGroundTruthExtractor()
                        .extract(
                                tempDir,
                                "0123456789abcdef0123456789abcdef01234567",
                                "89abcdef0123456789abcdef0123456789abcdef",
                                ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot diff");
    }

    private Repo initRepo() throws Exception {
        Path dir = tempDir.resolve("repo");
        Files.createDirectories(dir);
        Git git = Git.init().setDirectory(dir.toFile()).call();
        write(dir, "src/main/java/Foo.java", "class Foo {}");
        write(dir, "src/test/java/FooTest.java", "class FooTest {}");
        write(dir, "core/src/main/java/p/Core.java", "class Core {}");
        write(dir, "core/src/test/java/p/CoreTest.java", "class CoreTest { void already() {} }");
        String buggy = commit(git, "buggy");
        return new Repo(dir, git, buggy);
    }

    private static void write(Path repo, String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        PersonIdent who = new PersonIdent("fixture", "fixture@example.com");
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).call().getName();
    }

    private record Repo(Path dir, Git git, String buggy) {}
}

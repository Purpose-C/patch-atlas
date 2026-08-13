package io.github.patchatlas.run;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** 测试用本地 git 仓库：含既有测试文件，返回完整 SHA。 */
public final class LocalGitFixture {

    public static final String EXISTING_TEST =
            """
            package fixtures;

            import org.junit.jupiter.api.Test;

            class OldTest {
              @Test
              void already() {}
            }
            """;

    public static final String MODIFY_EXISTING_PATCH =
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

    public static final String TARGET_CLASS = "fixtures.OldTest";
    public static final String TARGET_METHOD = "added";

    private LocalGitFixture() {}

    /**
     * @param fixedSha Historical 第二 commit；Live fixture 时为 null
     */
    public record Fixture(Path originDir, String buggySha, String fixedSha) {
        boolean historical() {
            return fixedSha != null;
        }
    }

    public static Fixture initWithExistingTest(Path root) throws Exception {
        Path origin = root.resolve("origin");
        Files.createDirectories(origin);
        try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
            PersonIdent author = new PersonIdent("fixture", "fixture@example.com");
            Path testFile = origin.resolve("src/test/java/fixtures/OldTest.java");
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, EXISTING_TEST, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("buggy").setAuthor(author).setCommitter(author).call();
            String buggy = git.getRepository().resolve("HEAD").getName();
            return new Fixture(origin, buggy, null);
        }
    }

    /** Buggy + Fixed 两个不同 commit，两侧均含可被 modify patch 应用的 OldTest。 */
    public static Fixture initHistoricalWithExistingTest(Path root) throws Exception {
        Path origin = root.resolve("origin");
        Files.createDirectories(origin);
        try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
            PersonIdent author = new PersonIdent("fixture", "fixture@example.com");
            Path testFile = origin.resolve("src/test/java/fixtures/OldTest.java");
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, EXISTING_TEST, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("buggy").setAuthor(author).setCommitter(author).call();
            String buggy = git.getRepository().resolve("HEAD").getName();

            // Fixed：同文件加注释，产生不同 SHA，仍可应用同一 modify patch
            Files.writeString(
                    testFile,
                    EXISTING_TEST.replace("class OldTest {", "class OldTest { // fixed"),
                    StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixed").setAuthor(author).setCommitter(author).call();
            String fixed = git.getRepository().resolve("HEAD").getName();
            return new Fixture(origin, buggy, fixed);
        }
    }

    /** Buggy 不含 known trigger；Fixed 已包含它，模拟 GitBug Java 的校准布局。 */
    public static Fixture initHistoricalWithKnownTrigger(Path root) throws Exception {
        Path origin = root.resolve("origin");
        Files.createDirectories(origin);
        try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
            PersonIdent author = new PersonIdent("fixture", "fixture@example.com");
            Path testFile = origin.resolve("src/test/java/fixtures/OldTest.java");
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, EXISTING_TEST, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("buggy").setAuthor(author).setCommitter(author).call();
            String buggy = git.getRepository().resolve("HEAD").getName();

            Files.writeString(
                    testFile,
                    EXISTING_TEST.replace(
                            "  void already() {}\n",
                            "  void already() {}\n\n  @Test\n  void added() {}\n"),
                    StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixed-with-trigger").setAuthor(author).setCommitter(author).call();
            String fixed = git.getRepository().resolve("HEAD").getName();
            return new Fixture(origin, buggy, fixed);
        }
    }

    public static RepositoryWorkspaceFetcher fetcher(Path originDir) {
        return (repositoryUrl, revision, parentDir, directoryName) -> {
            Path target = parentDir.resolve(directoryName).normalize();
            if (!target.startsWith(parentDir.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("escape parent");
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("target already exists: " + target);
            }
            try (Git ignored = Git.cloneRepository()
                    .setURI(originDir.toUri().toString())
                    .setDirectory(target.toFile())
                    .setCloneAllBranches(false)
                    .call()) {
                GitCloneWorkspaceFetcher.hardResetTo(target, revision);
                return target;
            }
        };
    }

    public static void assertHead(Path repo, String expectedSha) throws Exception {
        try (var repository = new FileRepositoryBuilder()
                .setGitDir(repo.resolve(".git").toFile())
                .build()) {
            String head = repository.resolve("HEAD").getName();
            if (!expectedSha.equals(head)) {
                throw new AssertionError("HEAD " + head + " != " + expectedSha);
            }
        }
    }
}

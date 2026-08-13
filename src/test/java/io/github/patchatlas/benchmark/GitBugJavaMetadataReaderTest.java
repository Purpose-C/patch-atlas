package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.TargetTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBugJavaMetadataReaderTest {

    @TempDir
    Path temp;

    @Test
    void readsConcatenatedMetadataAndSeparatesGeneratorFromOracleFacts() throws Exception {
        Path bugs = Files.createDirectories(temp.resolve("data/bugs"));
        Files.writeString(
                bugs.resolve("cases.json"),
                metadata("Org/RepoB", "b".repeat(40), "a".repeat(40), 2)
                        + "\n"
                        + metadata("Org/RepoA", "d".repeat(40), "c".repeat(40), 1));

        var cases = new GitBugJavaMetadataReader().read(bugs);

        assertThat(cases)
                .extracting(c -> c.generatorData().caseId())
                .containsExactly("Org-RepoA-" + "d".repeat(12), "Org-RepoB-" + "b".repeat(12));
        var first = cases.getFirst();
        assertThat(first.generatorData().repositoryUrl()).isEqualTo("https://github.com/Org/RepoA.git");
        assertThat(first.generatorData().issueUrl()).isEqualTo("https://github.com/Org/RepoA/issues/1");
        assertThat(first.generatorData().issueTitle()).isEqualTo("issue 1");
        assertThat(first.generatorData().buggyRevision()).isEqualTo("c".repeat(40));
        assertThat(first.oracleData().fixedRevision()).isEqualTo("d".repeat(40));
        assertThat(first.oracleData().knownTriggerPatch()).contains("src/test/java/p/T.java");
        assertThat(first.oracleData().targetCandidates())
                .containsExactly(new TargetTest("p.T", "reproduces"));
        assertThat(first.staticMetadata().mavenBuild()).isTrue();
        assertThat(first.staticMetadata().javaTestChangePresent()).isTrue();
    }

    private static String metadata(String repository, String fixed, String buggy, int issueId) {
        return """
                {
                  "repository":"%s",
                  "clone_url":"https://github.com/%s.git",
                  "commit_hash":"%s",
                  "previous_commit_hash":"%s",
                  "language":"java",
                  "issues":[{"id":%d,"title":"issue %d","body":"body","is_pull_request":false}],
                  "test_patch":"diff --git a/src/test/java/p/T.java b/src/test/java/p/T.java\\n--- a/src/test/java/p/T.java\\n+++ b/src/test/java/p/T.java\\n@@ -1,0 +2,1 @@\\n+void reproduces() {}\\n",
                  "test_patch_file_extensions":["java"],
                  "actions_runs":[
                    [{"build_tool":"maven","tests":[{"classname":"p.Old","name":"passes","results":[{"result":"Passed"}]}]}],
                    [{"build_tool":"maven","tests":[{"classname":"p.T","name":"reproduces","results":[{"result":"Failure"}]}]}],
                    [{"build_tool":"maven","tests":[{"classname":"p.T","name":"reproduces","results":[{"result":"Passed"}]}]}]
                  ]
                }
                """
                .formatted(repository, repository, fixed, buggy, issueId, issueId);
    }
}

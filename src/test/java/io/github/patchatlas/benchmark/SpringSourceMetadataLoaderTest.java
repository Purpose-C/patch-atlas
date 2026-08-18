package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringSourceMetadataLoaderTest {

    @TempDir
    Path temp;

    @Test
    void loadsUnionIdsAndMarksExternalRecordsInvalidWithoutAMergeSha() throws Exception {
        Path scan = temp.resolve("scan.json");
        Files.writeString(
                scan,
                """
                {
                  "unionSpringPresent": [
                    {"caseId":"ext-1","repository":"o/r","buggyRevision":"%s"},
                    {"caseId":"Org-RepoA-%s","repository":"Org/RepoA","buggyRevision":"%s"}
                  ]
                }
                """.formatted("a".repeat(40), "d".repeat(12), "c".repeat(40)));
        Path bugs = Files.createDirectories(temp.resolve("bugs"));
        Files.writeString(
                bugs.resolve("cases.json"),
                """
                {
                  "repository":"Org/RepoA",
                  "clone_url":"https://github.com/Org/RepoA.git",
                  "commit_hash":"%s",
                  "previous_commit_hash":"%s",
                  "issues":[{"id":1,"title":"issue 1","body":"body","is_pull_request":false}],
                  "test_patch":"diff --git a/src/test/java/p/T.java b/src/test/java/p/T.java\\n",
                  "test_patch_file_extensions":["java"],
                  "actions_runs":[
                    [{"build_tool":"maven","tests":[]}],
                    [{"build_tool":"maven","tests":[{"classname":"p.T","name":"reproduces","results":[{"result":"Failure"}]}]}],
                    [{"build_tool":"maven","tests":[{"classname":"p.T","name":"reproduces","results":[{"result":"Passed"}]}]}]
                  ]
                }
                """.formatted("d".repeat(40), "c".repeat(40)));
        Path multi = Files.createDirectories(temp.resolve("multi"));
        Files.writeString(
                multi.resolve("o__r_dataset.jsonl"),
                """
                {"org":"o","repo":"r","instance_id":"ext-1","base":{"sha":"%s"},"test_patch":"diff --git a/src/test/java/p/T.java b/src/test/java/p/T.java\\n","resolved_issues":[{"number":2,"title":"bug","body":"repro"}]}
                """.formatted("a".repeat(40)));

        List<CaseMetadata> loaded = new SpringSourceMetadataLoader().load(scan, bugs, multi, null);

        assertThat(loaded).extracting(item -> item.generatorData().caseId())
                .containsExactly("Org-RepoA-" + "d".repeat(12), "ext-1");
        CaseMetadata gitbug = loaded.get(0);
        assertThat(gitbug.staticMetadata().metadataValid()).isTrue();
        assertThat(gitbug.staticMetadata().issueAvailable()).isTrue();
        CaseMetadata external = loaded.get(1);
        assertThat(external.staticMetadata().metadataValid()).isFalse();
        assertThat(external.staticMetadata().issueAvailable()).isTrue();
        assertThat(external.staticMetadata().javaTestChangePresent()).isTrue();
        assertThat(external.oracleData().fixedRevision()).isNull();
    }

    @Test
    void readsPolybenchRowsAndPlaceholdersWithoutUsingFixPatches() throws Exception {
        Path scan = temp.resolve("scan.json");
        Files.writeString(
                scan,
                """
                {
                  "unionSpringPresent": [
                    {"caseId":"poly-1"},
                    {"caseId":"missing-1"}
                  ]
                }
                """);
        Path poly = temp.resolve("poly.jsonl");
        Files.writeString(
                poly,
                """
                {"instance_id":"poly-1","repo":"acme/lib","base_commit":"%s","problem_statement":"Title\\n\\nBody","test_patch":"diff --git a/src/test/java/p/T.java b/src/test/java/p/T.java\\n"}
                """.formatted("b".repeat(40)));

        List<CaseMetadata> loaded = new SpringSourceMetadataLoader().load(scan, null, null, poly);

        assertThat(loaded).extracting(item -> item.generatorData().caseId())
                .containsExactly("missing-1", "poly-1");
        CaseMetadata polybench = loaded.get(1);
        assertThat(polybench.staticMetadata().metadataValid()).isFalse();
        assertThat(polybench.staticMetadata().issueAvailable()).isTrue();
        assertThat(polybench.generatorData().issueTitle()).isEqualTo("Title");
        assertThat(polybench.oracleData().fixedRevision()).isNull();
        CaseMetadata missing = loaded.get(0);
        assertThat(missing.staticMetadata().metadataValid()).isFalse();
        assertThat(missing.staticMetadata().mavenBuild()).isFalse();
    }
}

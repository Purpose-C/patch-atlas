package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ��Run 提交输入 round-trip 到 GenerationInput，且 Historical 的 Fixed 不进入生成投影。
 */
class RunSubmissionAndGenerationMappingTest {

    private static final String BUG = "a".repeat(40);
    private static final String FIXED_SENTINEL = "f".repeat(40);

    @Test
    void liveSubmissionMapsToGenerationInputWithoutFixed() {
        RunSubmission submission = liveSubmission(List.of(new SourceSnapshot("p/A.java", "class A {}")));

        GenerationInput input = GenerationInputMapper.toGenerationInput(submission);

        assertThat(input.generatorContext().caseId()).isEqualTo("live-1");
        assertThat(input.generatorContext().repositoryUrl()).isEqualTo(submission.repositoryUrl());
        assertThat(input.generatorContext().buggyRevision()).isEqualTo(BUG);
        assertThat(input.issueTitle()).isEqualTo("title");
        assertThat(input.issueBody()).isEqualTo("body");
        assertThat(input.sourceSnapshots()).hasSize(1);

        String surface = input.toString();
        assertThat(surface).doesNotContain(FIXED_SENTINEL);
        assertThat(surface).doesNotContain("fixedRevision");
    }

    @Test
    void historicalSubmissionKeepsFixedOnSubmissionButNotOnGenerationInput() {
        RunSubmission submission = historicalSubmission();

        assertThat(submission.fixedRevision()).isEqualTo(FIXED_SENTINEL);

        GenerationInput input = GenerationInputMapper.toGenerationInput(submission);
        assertThat(input.toString()).doesNotContain(FIXED_SENTINEL);
        assertThat(input.generatorContext().buggyRevision()).isEqualTo(BUG);
        assertThat(input.generatorContext().caseId()).isEqualTo("hist-1");
    }

    @Test
    void submissionCarriesImmutableExecutionPolicy() {
        RunSubmission submission = new RunSubmission(
                VerificationMode.LIVE,
                "c1",
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                BUG,
                null,
                "",
                "17",
                MavenNetworkMode.ONLINE,
                List.of());

        assertThat(submission.executionPolicy().javaVersion()).isEqualTo("17");
        assertThat(submission.executionPolicy().networkMode()).isEqualTo(MavenNetworkMode.ONLINE);
    }

    @Test
    void rejectsUnsupportedJavaVersion() {
        assertThatThrownBy(() -> new RunSubmission(
                        VerificationMode.LIVE,
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "t",
                        "b",
                        BUG,
                        null,
                        "",
                        "22",
                        MavenNetworkMode.OFFLINE,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("javaVersion");
    }

    @Test
    void liveAllowsNullCaseIdAndUsesPlaceholderForGeneratorContext() {
        RunSubmission submission = new RunSubmission(
                VerificationMode.LIVE,
                null,
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                BUG,
                null,
                "",
                null,
                List.of());

        GenerationInput input = GenerationInputMapper.toGenerationInput(submission);
        assertThat(input.generatorContext().caseId()).isEqualTo(GenerationInputMapper.LIVE_CASE_PLACEHOLDER);
    }

    @Test
    void rejectsLiveWithFixedRevision() {
        assertThatThrownBy(() -> new RunSubmission(
                        VerificationMode.LIVE,
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "t",
                        "b",
                        BUG,
                        FIXED_SENTINEL,
                        "",
                        null,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIVE");
    }

    @Test
    void rejectsHistoricalWithoutFixedRevision() {
        assertThatThrownBy(() -> new RunSubmission(
                        VerificationMode.HISTORICAL,
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "t",
                        "b",
                        BUG,
                        null,
                        "",
                        null,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HISTORICAL");
    }

    @Test
    void rejectsInvalidRevisionFormat() {
        assertThatThrownBy(() -> liveSubmissionWithBuggy("not-a-sha"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha");
    }

    @Test
    void rejectsCredentialRepositoryUrl() {
        assertThatThrownBy(() -> new RunSubmission(
                        VerificationMode.LIVE,
                        "c1",
                        "https://user:token@github.com/ex/repo.git",
                        null,
                        null,
                        "t",
                        "b",
                        BUG,
                        null,
                        "",
                        null,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryUrl");
    }

    @Test
    void rejectsUnsafeModulePath() {
        assertThatThrownBy(() -> new RunSubmission(
                        VerificationMode.LIVE,
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "t",
                        "b",
                        BUG,
                        null,
                        "../evil",
                        null,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modulePath");
    }

    @Test
    void rejectsOversizedIssueText() {
        String big = "x".repeat(GenerationInput.MAX_ISSUE_CHARS / 2 + 1);
        assertThatThrownBy(() -> new RunSubmission(
                        VerificationMode.LIVE,
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        big,
                        big,
                        BUG,
                        null,
                        "",
                        null,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void sourceSnapshotsRoundTripThroughCodecPreservesGenerationInput() {
        List<SourceSnapshot> snapshots =
                List.of(new SourceSnapshot("src/A.java", "package p;\nclass A {}"));
        RunSubmission submission = liveSubmission(snapshots);

        SourceSnapshotsCodec codec = new SourceSnapshotsCodec();
        String json = codec.encode(submission.sourceSnapshots());
        List<SourceSnapshot> restored = codec.decode(json, 1);

        GenerationInput original = GenerationInputMapper.toGenerationInput(submission);
        GenerationInput after = GenerationInputMapper.toGenerationInput(
                new RunSubmission(
                        submission.mode(),
                        submission.caseId(),
                        submission.repositoryUrl(),
                        submission.license(),
                        submission.issueUrl(),
                        submission.issueTitle(),
                        submission.issueBody(),
                        submission.buggyRevision(),
                        submission.fixedRevision(),
                        submission.modulePath(),
                        submission.javaVersion(),
                        restored));

        assertThat(after).isEqualTo(original);
    }

    private static RunSubmission liveSubmission(List<SourceSnapshot> snapshots) {
        return new RunSubmission(
                VerificationMode.LIVE,
                "live-1",
                "https://github.com/ex/repo.git",
                "Apache-2.0",
                "https://github.com/ex/repo/issues/1",
                "title",
                "body",
                BUG,
                null,
                "",
                "21",
                snapshots);
    }

    private static RunSubmission liveSubmissionWithBuggy(String buggy) {
        return new RunSubmission(
                VerificationMode.LIVE,
                "live-1",
                "https://github.com/ex/repo.git",
                null,
                null,
                "t",
                "b",
                buggy,
                null,
                "",
                null,
                List.of());
    }

    private static RunSubmission historicalSubmission() {
        return new RunSubmission(
                VerificationMode.HISTORICAL,
                "hist-1",
                "https://github.com/ex/repo.git",
                "MIT",
                "https://github.com/ex/repo/issues/9",
                "title",
                "body",
                BUG,
                FIXED_SENTINEL,
                "module-a",
                "17",
                List.of(new SourceSnapshot("src/X.java", "class X {}")));
    }
}

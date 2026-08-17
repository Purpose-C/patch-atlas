package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubmissionFingerprintTest {

    @Test
    void sameSubmissionSameHash() {
        RunSubmission a = sample("title", "src/A.java", "class A {}");
        RunSubmission b = sample("title", "src/A.java", "class A {}");
        assertThat(SubmissionFingerprint.sha256Hex(a)).isEqualTo(SubmissionFingerprint.sha256Hex(b));
        assertThat(SubmissionFingerprint.sha256Hex(a)).matches("^[0-9a-f]{64}$");
    }

    @Test
    void contextOriginIsPartOfFingerprint() {
        RunSubmission heuristic = sample("title", "src/A.java", "class A {}");
        RunSubmission tools = new RunSubmission(
                VerificationMode.LIVE,
                "c1",
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                "a".repeat(40),
                null,
                "",
                "21",
                MavenNetworkMode.OFFLINE,
                List.of(new SourceSnapshot("src/A.java", "class A {}")),
                ContextOrigin.TEXT_TOOLS);
        assertThat(heuristic.contextOrigin()).isEqualTo(ContextOrigin.HEURISTIC);
        assertThat(SubmissionFingerprint.sha256Hex(heuristic))
                .isNotEqualTo(SubmissionFingerprint.sha256Hex(tools));
    }

    @Test
    void ambiguousDelimiterPayloadsDoNotCollide() {
        // 若用朴素拼接，path=a,content=b 与 path=a / content 含分隔符可碰撞
        RunSubmission left = sample(
                "t",
                "a,content=b",
                "c");
        RunSubmission right = sample(
                "t",
                "a",
                "b,content=c");
        assertThat(SubmissionFingerprint.sha256Hex(left))
                .isNotEqualTo(SubmissionFingerprint.sha256Hex(right));
    }

    private static RunSubmission sample(String title, String path, String content) {
        return new RunSubmission(
                VerificationMode.LIVE,
                "c1",
                "https://github.com/ex/repo.git",
                null,
                null,
                title,
                "body",
                "a".repeat(40),
                null,
                "",
                "21",
                MavenNetworkMode.OFFLINE,
                List.of(new SourceSnapshot(path, content)));
    }
}

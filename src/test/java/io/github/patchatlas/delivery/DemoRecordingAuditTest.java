package io.github.patchatlas.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DemoRecordingAuditTest {

    @Test
    void recordingKeepsTheFailureAndTheDenominator() throws Exception {
        String recording = Files.readString(Path.of("docs/demo-recording.md"));
        assertThat(recording).contains("4b77189");
        assertThat(recording).contains("missing: OPENAI_API_KEY");
        assertThat(recording).contains("already in use");
        assertThat(recording).contains("18 次里的 1 次");
        assertThat(recording).contains("1/6");
        assertThat(recording).contains("4/18");
        assertThat(recording).contains("3/3");
        assertThat(recording).contains("0 / 0 / 0");
        assertThat(recording).contains("未入镜凭据");
        assertThat(recording).contains("没有为了画面重跑");
        assertThat(recording).doesNotContain("/Users/");
        assertThat(recording).doesNotContain("/home/");
        assertThat(recording).doesNotContain("OPENAI_API_KEY=");
        assertThat(recording).doesNotContain("sk-");
        assertThat(recording).doesNotContain("语义图引导");
        assertThat(recording).doesNotContain("图更好");
    }
}

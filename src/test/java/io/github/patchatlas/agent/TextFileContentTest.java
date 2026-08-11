package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextFileContentTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsOversizedFileBeforeFullDecode() throws Exception {
        Path huge = tempDir.resolve("Huge.java");
        // 超过 64 KiB
        Files.writeString(huge, "x".repeat((int) TextFileContent.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TextFileContent.read(huge))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void rejectsInvalidUtf8() throws Exception {
        Path bad = tempDir.resolve("Bad.java");
        Files.write(bad, new byte[] {(byte) 0xC3, (byte) 0x28}); // invalid UTF-8 sequence

        assertThatThrownBy(() -> TextFileContent.read(bad))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsMixedCrlfAndLf() throws Exception {
        Path mixed = tempDir.resolve("Mixed.java");
        Files.writeString(mixed, "line1\r\nline2\nline3\r\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TextFileContent.read(mixed))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("mixed");
    }

    @Test
    void preservesLfAndMissingEofNewline() throws Exception {
        Path f = tempDir.resolve("Ok.java");
        Files.writeString(f, "a\nb", StandardCharsets.UTF_8); // no trailing newline

        TextFileContent content = TextFileContent.read(f);
        assertThat(content.lines()).containsExactly("a", "b");
        assertThat(content.serialize(content.lines())).isEqualTo("a\nb");
    }
}

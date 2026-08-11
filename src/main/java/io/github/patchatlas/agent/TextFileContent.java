package io.github.patchatlas.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 有界读取并保留换行风格。拒绝超大文件、非法 UTF-8、孤立 CR 与 CRLF/LF 混用。
 */
final class TextFileContent {

    /** 与 {@link SourceSnapshot#MAX_CONTENT_BYTES} 对齐。 */
    static final long MAX_FILE_BYTES = SourceSnapshot.MAX_CONTENT_BYTES;

    private final List<String> lines;
    private final String lineEnding;
    private final boolean endsWithNewline;

    private TextFileContent(List<String> lines, String lineEnding, boolean endsWithNewline) {
        this.lines = List.copyOf(lines);
        this.lineEnding = lineEnding;
        this.endsWithNewline = endsWithNewline;
    }

    static TextFileContent read(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("target must not be a symbolic link");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("target must be a regular file");
        }
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("target file exceeds size limit");
        }

        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("target file exceeds size limit");
        }

        String content = decodeUtf8Strict(bytes);
        if (content.indexOf('\0') >= 0) {
            throw new IOException("binary content not allowed");
        }

        boolean hasCrlf = content.contains("\r\n");
        boolean hasLf = content.indexOf('\n') >= 0;
        boolean hasBareCr = content.indexOf('\r') >= 0
                && content.replace("\r\n", "").indexOf('\r') >= 0;
        if (hasBareCr) {
            throw new IOException("unsupported bare CR line endings");
        }
        // 混用：同时存在 \r\n 与「去 CRLF 后仍有 \n」
        if (hasCrlf && content.replace("\r\n", "").indexOf('\n') >= 0) {
            throw new IOException("mixed CRLF and LF line endings");
        }
        // 仅 LF 或仅 CRLF（或无换行）
        String ending = hasCrlf ? "\r\n" : "\n";
        boolean endsWithNewline = content.endsWith("\r\n") || content.endsWith("\n");

        List<String> lines = new ArrayList<>();
        if (!content.isEmpty()) {
            String normalized = hasCrlf ? content.replace("\r\n", "\n") : content;
            if (endsWithNewline && normalized.endsWith("\n")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (!normalized.isEmpty()) {
                for (String line : normalized.split("\n", -1)) {
                    lines.add(line);
                }
            }
        }
        // 无换行的单段文件：hasLf false 时 ending 仍用 \n 供后续新增，但 endsWithNewline=false 保持原状
        if (!hasLf && !hasCrlf) {
            ending = "\n";
        }
        return new TextFileContent(lines, ending, endsWithNewline);
    }

    private static String decodeUtf8Strict(byte[] bytes) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("invalid UTF-8", ex);
        }
    }

    List<String> lines() {
        return lines;
    }

    String serialize(List<String> newLines) {
        if (newLines.isEmpty()) {
            return endsWithNewline ? lineEnding : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newLines.size(); i++) {
            if (i > 0) {
                sb.append(lineEnding);
            }
            sb.append(newLines.get(i));
        }
        if (endsWithNewline) {
            sb.append(lineEnding);
        }
        return sb.toString();
    }
}

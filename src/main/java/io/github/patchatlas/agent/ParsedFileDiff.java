package io.github.patchatlas.agent;

import java.util.List;
import java.util.Objects;

/** 解析后的单文件 unified diff（V1：仅新增行）。 */
final class ParsedFileDiff {

    enum Kind {
        CREATE,
        MODIFY
    }

    private final String path;
    private final Kind kind;
    private final List<String> addedLines;
    private final int changedLineCount;
    private final List<Hunk> hunks;

    ParsedFileDiff(String path, Kind kind, List<String> addedLines, int changedLineCount, List<Hunk> hunks) {
        this.path = Objects.requireNonNull(path);
        this.kind = Objects.requireNonNull(kind);
        this.addedLines = List.copyOf(addedLines);
        this.changedLineCount = changedLineCount;
        this.hunks = List.copyOf(hunks);
    }

    String path() {
        return path;
    }

    Kind kind() {
        return kind;
    }

    List<String> addedLines() {
        return addedLines;
    }

    int changedLineCount() {
        return changedLineCount;
    }

    List<Hunk> hunks() {
        return hunks;
    }

    record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {
        Hunk {
            lines = List.copyOf(lines);
        }
    }
}

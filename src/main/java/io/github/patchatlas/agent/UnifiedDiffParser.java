package io.github.patchatlas.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有界 unified diff 解析：完整消费输入；V1 只接受删除行数为 0 的补丁。
 */
final class UnifiedDiffParser {

    static final int MAX_PATCH_BYTES = 64 * 1024;
    static final int MAX_LINE_CHARS = 4 * 1024;
    static final int MAX_FILES = 2;
    static final int MAX_CHANGED_LINES = 200;

    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
    /** 仅允许普通文件 mode（100644 / 100755）；index 可选尾部 mode 同样受限。 */
    private static final Pattern INDEX_LINE =
            Pattern.compile("^index [0-9a-fA-F]+\\.\\.[0-9a-fA-F]+(?: (100644|100755))?$");
    private static final Pattern REGULAR_FILE_MODE = Pattern.compile("^100644$");
    private static final String NO_NEWLINE_MARKER = "\\ No newline at end of file";

    private UnifiedDiffParser() {}

    static ParseOutcome parse(String patchText, CompletionDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (patchText == null) {
            return reject("null patch");
        }
        int bytes = patchText.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > MAX_PATCH_BYTES) {
            return reject("patch size out of bounds");
        }
        if (patchText.indexOf('\0') >= 0) {
            return reject("NUL not allowed");
        }

        List<String> lines = splitLines(patchText);
        for (String line : lines) {
            if (line.length() > MAX_LINE_CHARS) {
                return reject("line too long");
            }
        }

        List<ParsedFileDiff> diffs = new ArrayList<>();
        int i = 0;
        int totalChanged = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                i++;
                continue;
            }
            if (!line.startsWith("diff --git ")) {
                return reject("trailing non-patch text");
            }
            FileParse file = parseFile(lines, i, diagnostics.indicatesComplete());
            if (!file.ok()) {
                return file.error;
            }
            for (ParsedFileDiff existing : diffs) {
                if (existing.path().equals(file.diff.path())) {
                    return reject("duplicate file path");
                }
            }
            if (diffs.size() >= MAX_FILES) {
                return ParseOutcome.reject(
                        PatchRejectionCategory.FILE_OR_LINE_LIMIT_EXCEEDED, "more than 2 files");
            }
            totalChanged += file.diff.changedLineCount();
            if (totalChanged > MAX_CHANGED_LINES) {
                return ParseOutcome.reject(
                        PatchRejectionCategory.FILE_OR_LINE_LIMIT_EXCEEDED, "more than 200 changed lines");
            }
            diffs.add(file.diff);
            i = file.nextIndex;
        }
        if (diffs.isEmpty()) {
            return reject("no file diffs");
        }
        return ParseOutcome.ok(diffs);
    }

    private static List<String> splitLines(String patchText) {
        String[] raw = patchText.split("\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (i == raw.length - 1 && raw[i].isEmpty() && patchText.endsWith("\n")) {
                continue;
            }
            // strip CR for CRLF patches (content lines keep structure; headers rarely need CR)
            String line = raw[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return lines;
    }

    private static FileParse parseFile(List<String> lines, int start, boolean recountFromBody) {
        int i = start;
        String header = lines.get(i);
        String[] parts = header.split(" ");
        if (parts.length < 4 || !parts[2].startsWith("a/") || !parts[3].startsWith("b/")) {
            return FileParse.err(reject("bad diff header"));
        }
        String headerOldPath = parts[2].substring(2);
        String headerNewPath = parts[3].substring(2);
        if (!isSafeRepoPath(headerOldPath) || !isSafeRepoPath(headerNewPath)) {
            return FileParse.err(ParseOutcome.reject(
                    PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "unsafe path in header"));
        }
        i++;

        ParsedFileDiff.Kind kind = ParsedFileDiff.Kind.MODIFY;
        String oldMode = null;
        String newMode = null;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.startsWith("new file mode ")) {
                kind = ParsedFileDiff.Kind.CREATE;
                newMode = line.substring("new file mode ".length()).trim();
                if (!REGULAR_FILE_MODE.matcher(newMode).matches()) {
                    return FileParse.err(ParseOutcome.reject(
                            PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE,
                            "new file mode must be 100644"));
                }
                i++;
            } else if (line.startsWith("deleted file mode ")) {
                return FileParse.err(ParseOutcome.reject(
                        PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "file deletion not allowed"));
            } else if (line.startsWith("old mode ")) {
                oldMode = line.substring("old mode ".length()).trim();
                if (!isAllowedRegularMode(oldMode)) {
                    return FileParse.err(ParseOutcome.reject(
                            PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "unsupported file mode"));
                }
                i++;
            } else if (line.startsWith("new mode ")) {
                newMode = line.substring("new mode ".length()).trim();
                if (!isAllowedRegularMode(newMode)) {
                    return FileParse.err(ParseOutcome.reject(
                            PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "unsupported file mode"));
                }
                i++;
            } else if (INDEX_LINE.matcher(line).matches()) {
                i++;
            } else if (line.startsWith("similarity index ")
                    || line.startsWith("rename from ")
                    || line.startsWith("rename to ")
                    || line.startsWith("copy from ")
                    || line.startsWith("copy to ")
                    || line.startsWith("Binary files ")
                    || line.startsWith("GIT binary patch")
                    || line.startsWith("index ")) {
                return FileParse.err(ParseOutcome.reject(
                        PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "unsupported git file header"));
            } else {
                break;
            }
        }
        if (oldMode != null && newMode != null && !oldMode.equals(newMode)) {
            return FileParse.err(ParseOutcome.reject(
                    PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "file mode change not allowed"));
        }
        if (kind == ParsedFileDiff.Kind.CREATE && newMode != null && !REGULAR_FILE_MODE.matcher(newMode).matches()) {
            return FileParse.err(ParseOutcome.reject(
                    PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "new file mode must be 100644"));
        }

        if (i >= lines.size() || !lines.get(i).startsWith("--- ")) {
            return FileParse.err(reject("missing --- line"));
        }
        String oldFile = stripPrefix(lines.get(i).substring(4).trim());
        i++;
        if (i >= lines.size() || !lines.get(i).startsWith("+++ ")) {
            return FileParse.err(reject("missing +++ line"));
        }
        String newFile = stripPrefix(lines.get(i).substring(4).trim());
        i++;

        if ("/dev/null".equals(newFile)) {
            return FileParse.err(ParseOutcome.reject(
                    PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "file deletion not allowed"));
        }
        if (!"/dev/null".equals(newFile) && !isSafeRepoPath(newFile)) {
            return FileParse.err(ParseOutcome.reject(
                    PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "unsafe path in +++"));
        }
        if ("/dev/null".equals(oldFile)) {
            kind = ParsedFileDiff.Kind.CREATE;
        } else if (!isSafeRepoPath(oldFile)) {
            return FileParse.err(ParseOutcome.reject(
                    PatchRejectionCategory.UNSAFE_OR_OUT_OF_SCOPE_PATH, "unsafe path in ---"));
        }

        // 路径不变量：禁止伪装 rename
        if (kind == ParsedFileDiff.Kind.CREATE) {
            if (!"/dev/null".equals(oldFile)) {
                return FileParse.err(reject("create patch must use --- /dev/null"));
            }
            if (!headerNewPath.equals(newFile) || !headerOldPath.equals(newFile)) {
                // git create: a/path and b/path are both the new path
                return FileParse.err(reject("create patch path headers must agree"));
            }
        } else {
            if ("/dev/null".equals(oldFile)) {
                return FileParse.err(reject("modify patch must not use --- /dev/null"));
            }
            if (!(headerOldPath.equals(headerNewPath)
                    && headerOldPath.equals(oldFile)
                    && headerNewPath.equals(newFile))) {
                return FileParse.err(reject("modify patch path headers must agree"));
            }
        }

        String path = newFile;

        List<ParsedFileDiff.Hunk> hunks = new ArrayList<>();
        List<String> allAdded = new ArrayList<>();
        int changed = 0;
        long lastOldCovered = 0; // 上一 hunk 旧侧已覆盖的最大 1-based 行号
        long insertedBefore = 0;

        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.startsWith("diff --git ")) {
                break;
            }
            if (line.isBlank()) {
                if (i + 1 < lines.size() && lines.get(i + 1).startsWith("diff --git ")) {
                    i++;
                    break;
                }
            }
            Matcher matcher = HUNK.matcher(line);
            if (!matcher.find()) {
                return FileParse.err(reject("expected hunk header"));
            }
            Long oldStart = parseHunkLong(matcher.group(1));
            Long oldCount =
                    matcher.group(2) == null ? Long.valueOf(1L) : parseHunkLong(matcher.group(2));
            Long newStart = parseHunkLong(matcher.group(3));
            Long newCount =
                    matcher.group(4) == null ? Long.valueOf(1L) : parseHunkLong(matcher.group(4));
            if (oldStart == null || oldCount == null || newStart == null || newCount == null) {
                return FileParse.err(reject("hunk line numbers out of range"));
            }
            if (oldStart < 0 || oldCount < 0 || newStart < 0 || newCount < 0) {
                return FileParse.err(reject("hunk line numbers must not be negative"));
            }
            i++;

            List<String> hunkLines = new ArrayList<>();
            int plus = 0;
            int minus = 0;
            int context = 0;
            boolean seenNoNewlineMarker = false;
            while (i < lines.size()) {
                String hl = lines.get(i);
                if (hl.startsWith("@@ ") || hl.startsWith("diff --git ")) {
                    break;
                }
                if (hl.startsWith("\\")) {
                    if (!NO_NEWLINE_MARKER.equals(hl)) {
                        return FileParse.err(reject("invalid backslash marker"));
                    }
                    if (hunkLines.isEmpty()) {
                        return FileParse.err(reject("newline marker without preceding hunk line"));
                    }
                    if (seenNoNewlineMarker) {
                        return FileParse.err(reject("duplicate newline marker"));
                    }
                    // marker 必须贴在该侧最后一行：其后不得再有内容行
                    seenNoNewlineMarker = true;
                    i++;
                    continue;
                }
                if (seenNoNewlineMarker) {
                    // marker 之后又出现 context/+/ - 行
                    return FileParse.err(reject("content after newline marker"));
                }
                if (hl.isEmpty()) {
                    return FileParse.err(reject("hunk line missing prefix"));
                }
                char prefix = hl.charAt(0);
                switch (prefix) {
                    case '+' -> {
                        plus++;
                        allAdded.add(hl.substring(1));
                        changed++;
                    }
                    case '-' -> {
                        minus++;
                        changed++;
                    }
                    case ' ' -> context++;
                    default -> {
                        return FileParse.err(reject("invalid hunk line prefix"));
                    }
                }
                hunkLines.add(hl);
                i++;
            }
            if (minus > 0) {
                return FileParse.err(ParseOutcome.reject(
                        PatchRejectionCategory.UNSUPPORTED_CHANGE_TYPE, "deletions not allowed"));
            }
            long bodyNew = (long) context + plus;
            long bodyOld = (long) context + minus;
            if (recountFromBody) {
                newCount = bodyNew;
                oldCount = bodyOld;
            } else if (bodyNew != newCount) {
                return FileParse.err(reject("hunk new count mismatch"));
            } else if (bodyOld != oldCount) {
                return FileParse.err(reject("hunk old count mismatch"));
            }
            if (kind == ParsedFileDiff.Kind.CREATE && (oldCount != 0 || context != 0)) {
                return FileParse.err(reject("create patch must not carry old-file context"));
            }

            // 顺序与重叠（全部用 long，拒绝相加溢出）
            if (oldCount == 0) {
                if (oldStart < lastOldCovered) {
                    return FileParse.err(reject("hunks out of order or overlapping"));
                }
            } else if (oldStart <= lastOldCovered) {
                return FileParse.err(reject("hunks out of order or overlapping"));
            }

            final long expectedNewStart;
            if (oldCount == 0) {
                Long step = addExact(oldStart, 1L);
                if (step == null) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
                Long expected = addExact(step, insertedBefore);
                if (expected == null) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
                expectedNewStart = expected;
            } else {
                Long expected = addExact(oldStart, insertedBefore);
                if (expected == null) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
                expectedNewStart = expected;
            }
            if (newStart.longValue() != expectedNewStart) {
                return FileParse.err(reject("hunk newStart inconsistent with prior insertions"));
            }

            final long nextCovered;
            if (oldCount == 0) {
                nextCovered = oldStart;
            } else {
                Long sum = addExact(oldStart, oldCount);
                if (sum == null) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
                Long covered = addExact(sum, -1L);
                if (covered == null) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
                nextCovered = covered;
            }
            Long nextInserted = addExact(insertedBefore, plus);
            if (nextInserted == null) {
                return FileParse.err(reject("hunk coordinate overflow"));
            }
            // 进入 Hunk 前：坐标与 end = (oldStart-1)+oldCount 必须可安全落入 int List 索引
            if (!fitsNonNegInt(oldStart)
                    || !fitsNonNegInt(oldCount)
                    || !fitsNonNegInt(newStart)
                    || !fitsNonNegInt(newCount)
                    || !fitsNonNegInt(expectedNewStart)) {
                return FileParse.err(reject("hunk coordinates exceed application limits"));
            }
            if (oldCount > 0) {
                Long startIdx = addExact(oldStart, -1L);
                if (startIdx == null) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
                Long applyEnd = addExact(startIdx, oldCount);
                if (applyEnd == null || applyEnd > Integer.MAX_VALUE || applyEnd < 0) {
                    return FileParse.err(reject("hunk coordinate overflow"));
                }
            }

            lastOldCovered = nextCovered;
            insertedBefore = nextInserted;

            hunks.add(new ParsedFileDiff.Hunk(
                    oldStart.intValue(),
                    oldCount.intValue(),
                    newStart.intValue(),
                    newCount.intValue(),
                    hunkLines));
        }

        if (hunks.isEmpty()) {
            return FileParse.err(reject("file has no hunks"));
        }
        return FileParse.ok(new ParsedFileDiff(path, kind, allAdded, changed, hunks), i);
    }

    private static Long parseHunkLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long addExact(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static boolean fitsPositiveInt(long value) {
        return value >= 0 && value <= Integer.MAX_VALUE;
    }

    private static boolean fitsNonNegInt(long value) {
        return value >= 0 && value <= Integer.MAX_VALUE;
    }

    private static boolean isAllowedRegularMode(String mode) {
        return "100644".equals(mode) || "100755".equals(mode);
    }

    private static String stripPrefix(String file) {
        if (file.startsWith("a/") || file.startsWith("b/")) {
            return file.substring(2);
        }
        return file;
    }

    private static boolean isSafeRepoPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("\0")) {
            return false;
        }
        if (path.contains("..")) {
            return false;
        }
        for (int c = 0; c < path.length(); c++) {
            char ch = path.charAt(c);
            if (ch < 0x20) {
                return false;
            }
        }
        return true;
    }

    private static ParseOutcome reject(String reason) {
        return ParseOutcome.reject(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH, reason);
    }

    record ParseOutcome(List<ParsedFileDiff> files, PatchRejectionCategory category, String reason) {
        static ParseOutcome ok(List<ParsedFileDiff> files) {
            return new ParseOutcome(List.copyOf(files), null, null);
        }

        static ParseOutcome reject(PatchRejectionCategory category, String reason) {
            return new ParseOutcome(null, category, reason);
        }

        boolean isOk() {
            return files != null;
        }
    }

    private record FileParse(ParsedFileDiff diff, int nextIndex, ParseOutcome error) {
        static FileParse ok(ParsedFileDiff diff, int next) {
            return new FileParse(diff, next, null);
        }

        static FileParse err(ParseOutcome error) {
            return new FileParse(null, -1, error);
        }

        boolean ok() {
            return error == null;
        }
    }
}

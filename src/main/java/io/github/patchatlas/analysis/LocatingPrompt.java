package io.github.patchatlas.analysis;

/**
 * 定位系统提示词骨架。文本工具与后续图工具共用，只替换工具描述段。
 *
 * <p>措辞是消融受控变量，冻结前不要改。
 */
public final class LocatingPrompt {

    public static final String TEXT_TOOL_SECTION =
            """
            Tools:
            - search: regex search over files; optional pathGlob
            - list: list a directory
            - read: read a file slice
            - submit: submit selected repository-relative paths and stop locating
            """;

    private LocatingPrompt() {}

    public static String skeleton(String toolSection) {
        return """
                You are locating source files in a Java repository that are needed to reproduce the reported issue.
                Explore the workspace with the tools listed below. Do not answer the issue in prose.
                After you have selected files, you must call submit with those repository-relative paths.
                Do not put the selected paths only in chat text.

                %s
                """
                .formatted(toolSection)
                .strip();
    }

    public static String textTools() {
        return skeleton(TEXT_TOOL_SECTION);
    }
}

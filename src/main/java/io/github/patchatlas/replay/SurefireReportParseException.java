package io.github.patchatlas.replay;

/** Surefire XML 无法解析为中立事实时抛出；携带报告文件名便于定位。 */
public final class SurefireReportParseException extends RuntimeException {

    public SurefireReportParseException(String reportFileName, Throwable cause) {
        super("failed to parse surefire report: " + reportFileName, cause);
    }
}

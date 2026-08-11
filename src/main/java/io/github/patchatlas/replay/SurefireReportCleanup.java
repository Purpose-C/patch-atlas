package io.github.patchatlas.replay;

/**
 * Surefire 报告目录清理结果。失败时不得继续读取可能陈旧的报告。
 */
public sealed interface SurefireReportCleanup
        permits SurefireReportCleanup.Succeeded, SurefireReportCleanup.Failed {

    record Succeeded() implements SurefireReportCleanup {}

    record Failed(String reason) implements SurefireReportCleanup {
        public Failed {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    static SurefireReportCleanup succeeded() {
        return new Succeeded();
    }

    static SurefireReportCleanup failed(String reason) {
        return new Failed(reason);
    }
}

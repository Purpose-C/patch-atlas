package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceFailureSummarizerTest {

    private static final String SECRET = "workspace-root/.env token=sk-abc";

    @ParameterizedTest
    @EnumSource(value = RunPurpose.class, names = "DIAGNOSTIC", mode = EnumSource.Mode.EXCLUDE)
    void nonDiagnosticPurposesNeverEchoExceptionMessage(RunPurpose purpose) {
        Exception ex = new IllegalArgumentException("testSelector leaked " + SECRET);
        RunFailure failure = WorkspaceFailureSummarizer.failure(ex, purpose);
        assertThat(failure.stage()).isEqualTo(FailureStage.WORKSPACE);
        assertThat(failure.category()).isEqualTo(FailureCategory.WORKSPACE_ERROR);
        assertThat(failure.summary()).isEqualTo("workspace: IllegalArgumentException");
        assertThat(failure.summary()).doesNotContain(SECRET);
        assertThat(failure.summary()).doesNotContain("testSelector");
        assertThat(failure.summary()).doesNotContain("sk-abc");
    }

    @Test
    void diagnosticIncludesExceptionMessage() {
        Exception ex = new IllegalArgumentException("testSelector must be a class or class#method selector");
        RunFailure failure = WorkspaceFailureSummarizer.failure(ex, RunPurpose.DIAGNOSTIC);
        assertThat(failure.category()).isEqualTo(FailureCategory.WORKSPACE_ERROR);
        assertThat(failure.summary())
                .isEqualTo(
                        "workspace: IllegalArgumentException: testSelector must be a class or class#method selector");
    }

    @Test
    void diagnosticStillBoundsSummary() {
        String longMessage = "x".repeat(RunFailure.MAX_SUMMARY_CHARS + 80);
        Exception ex = new IllegalArgumentException(longMessage);
        String summary = WorkspaceFailureSummarizer.failure(ex, RunPurpose.DIAGNOSTIC).summary();
        assertThat(summary).hasSize(RunFailure.MAX_SUMMARY_CHARS);
        assertThat(summary).startsWith("workspace: IllegalArgumentException: ");
    }
}

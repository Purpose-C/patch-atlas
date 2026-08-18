package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResponseTruncationGuardTest {

    @Test
    void lengthFinishReasonIsTruncatedRegardlessOfPatchBody() {
        assertThat(ResponseTruncationGuard.truncated(CompletionDiagnostics.of("length", "0", "10")))
                .isTrue();
        assertThat(ResponseTruncationGuard.rejection().category())
                .isEqualTo(PatchRejectionCategory.RESPONSE_TRUNCATED);
        assertThat(ResponseTruncationGuard.rejection().reason()).contains("响应被截断");
        assertThat(ResponseTruncationGuard.rejection().category())
                .isNotEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
    }

    @Test
    void stopAndUnknownAreNotTruncated() {
        assertThat(ResponseTruncationGuard.truncated(CompletionDiagnostics.of("stop", "0", "10")))
                .isFalse();
        assertThat(ResponseTruncationGuard.truncated(CompletionDiagnostics.unknown())).isFalse();
    }
}

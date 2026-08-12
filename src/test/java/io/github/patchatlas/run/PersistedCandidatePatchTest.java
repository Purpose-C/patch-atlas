package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.replay.TargetTest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/** ��candidate 映射、hash、大小边界。 */
class PersistedCandidatePatchTest {

    @Test
    void computesSha256OfUtf8BytesAndRoundTrips() throws Exception {
        String patch = "diff --git a/x b/x\n+hello";
        TargetTest target = new TargetTest("fixtures.NewTest", "works");

        PersistedCandidatePatch created = PersistedCandidatePatch.fromAccepted(patch, target);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(digest.digest(patch.getBytes(StandardCharsets.UTF_8)));
        assertThat(created.patchSha256()).isEqualTo(expected);
        assertThat(created.patchText()).isEqualTo(patch);
        assertThat(created.targetTest()).isEqualTo(target);

        PersistedCandidatePatch restored =
                PersistedCandidatePatch.restore(patch, expected, target.className(), target.methodName());
        assertThat(restored).isEqualTo(created);
    }

    @Test
    void restoreRejectsHashMismatch() {
        assertThatThrownBy(() -> PersistedCandidatePatch.restore(
                        "diff", "a".repeat(64), "fixtures.T", "m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash");
    }

    @Test
    void rejectsEmptyPatch() {
        assertThatThrownBy(() ->
                        PersistedCandidatePatch.fromAccepted("", new TargetTest("a.B", "m")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPatchWithNul() {
        assertThatThrownBy(() -> PersistedCandidatePatch.fromAccepted(
                        "diff\0xx", new TargetTest("a.B", "m")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void rejectsOversizedPatch() {
        String huge = "x".repeat(PersistedCandidatePatch.MAX_PATCH_BYTES + 1);
        assertThatThrownBy(() ->
                        PersistedCandidatePatch.fromAccepted(huge, new TargetTest("a.B", "m")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void rejectsSelectorExceedingCombinedLimit() {
        String longClass = "a." + "B".repeat(200);
        String longMethod = "m".repeat(60);
        assertThat(longClass.length() + 1 + longMethod.length())
                .isGreaterThan(PersistedCandidatePatch.MAX_SELECTOR_CHARS);
        assertThatThrownBy(() -> PersistedCandidatePatch.fromAccepted(
                        "diff --git a/x b/x", new TargetTest(longClass, longMethod)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selector");
    }
}

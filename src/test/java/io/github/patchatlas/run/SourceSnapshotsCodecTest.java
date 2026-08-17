package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.SourceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** source_snapshots JSONB codec 边界。 */
class SourceSnapshotsCodecTest {

    private final SourceSnapshotsCodec codec = new SourceSnapshotsCodec();

    @Test
    void roundTripsSnapshots() {
        List<SourceSnapshot> original = List.of(
                new SourceSnapshot("src/main/java/A.java", "class A {}"),
                new SourceSnapshot("src/test/java/ATest.java", "class ATest {}"));

        String json = codec.encode(original);
        List<SourceSnapshot> decoded = codec.decode(json, SourceSnapshotsCodec.SCHEMA_VERSION);

        assertThat(decoded).isEqualTo(original);
        assertThat(json).doesNotContain("fixedRevision");
        assertThat(json).doesNotContain("@class");
    }

    @Test
    void encodesEmptyList() {
        String json = codec.encode(List.of());
        assertThat(codec.decode(json, SourceSnapshotsCodec.SCHEMA_VERSION)).isEmpty();
    }

    @Test
    void decodesCurrentInputSchemaVersionSameAsLegacy() {
        List<SourceSnapshot> original = List.of(new SourceSnapshot("a/B.java", "x"));
        String json = codec.encode(original);
        assertThat(codec.decode(json, SourceSnapshotsCodec.CURRENT_INPUT_SCHEMA_VERSION))
                .isEqualTo(codec.decode(json, SourceSnapshotsCodec.SCHEMA_VERSION));
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        String json = codec.encode(List.of(new SourceSnapshot("a/B.java", "x")));
        assertThatThrownBy(() -> codec.decode(json, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void rejectsNonArrayJson() {
        assertThatThrownBy(() -> codec.decode("{\"relativePath\":\"a\"}", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> codec.decode("[{\"relativePath\":\"a/B.java\"}]", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooManySnapshotsInJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 13; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"relativePath\":\"p")
                    .append(i)
                    .append(".java\",\"content\":\"c\"}");
        }
        sb.append(']');
        assertThatThrownBy(() -> codec.decode(sb.toString(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("not-json", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

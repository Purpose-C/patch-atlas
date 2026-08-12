package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunListCursorTest {

    @Test
    void roundTrips() {
        Instant t = Instant.parse("2026-08-12T10:00:00Z");
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String opaque = new RunListCursor(t, id).encode();
        RunListCursor decoded = RunListCursor.decode(opaque);
        assertThat(decoded.createdAt()).isEqualTo(t);
        assertThat(decoded.runId()).isEqualTo(id);
    }

    @Test
    void rejectsMalformedBase64() {
        assertThatThrownBy(() -> RunListCursor.decode("not-valid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTamperedPayloadWithValidShape() {
        Instant t = Instant.parse("2026-08-12T10:00:00Z");
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String good = new RunListCursor(t, id).encode();
        String raw = new String(Base64.getUrlDecoder().decode(good), StandardCharsets.UTF_8);
        // 篡改 runId 但保留伪 mac 尾巴
        String[] parts = raw.split("\\|", -1);
        assertThat(parts).hasSize(4);
        String tampered = parts[0]
                + "|"
                + parts[1]
                + "|"
                + "22222222-2222-2222-2222-222222222222"
                + "|"
                + parts[3];
        String opaque = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RunListCursor.decode(opaque))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integrity");
    }
}

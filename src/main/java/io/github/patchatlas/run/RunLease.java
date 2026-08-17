package io.github.patchatlas.run;

import java.time.Instant;
import java.util.UUID;

/** 运行态租约凭证（LOCATING / GENERATING / REPLAYING）。 */
public record RunLease(UUID token, String owner, Instant expiresAt) {

    public static final int MAX_OWNER_CHARS = 128;

    public RunLease {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        if (owner.length() > MAX_OWNER_CHARS) {
            throw new IllegalArgumentException("owner exceeds " + MAX_OWNER_CHARS + " chars");
        }
    }
}

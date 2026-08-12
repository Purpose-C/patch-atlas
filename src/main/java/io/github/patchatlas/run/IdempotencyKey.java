package io.github.patchatlas.run;

import java.util.Objects;
import java.util.regex.Pattern;

/** 公开创建 API 的幂等键；不得写入应用日志。 */
public final class IdempotencyKey {

    public static final int MAX_LENGTH = 128;
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final String value;

    private IdempotencyKey(String value) {
        this.value = value;
    }

    public static IdempotencyKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String trimmed = raw.trim();
        if (!SAFE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1–128 chars of [A-Za-z0-9._:-]");
        }
        return new IdempotencyKey(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IdempotencyKey other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "IdempotencyKey[len=" + value.length() + "]";
    }
}

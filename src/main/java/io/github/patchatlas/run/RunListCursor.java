package io.github.patchatlas.run;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 不透明 keyset cursor：payload + HMAC 完整性校验。
 *
 * <p>格式：{@code base64url(v1|createdAt|runId|macHex)}。密钥优先取环境变量
 * {@code PATCHATLAS_CURSOR_SECRET}；未设置时使用<strong>进程启动时随机生成</strong>的密钥
 * （重启后旧 cursor 失效，避免源码中的固定密钥被客户端伪造）。
 */
public final class RunListCursor {

    private static final String VERSION = "v1";
    private static final String HMAC_ALG = "HmacSHA256";
    private static final byte[] SECRET = resolveSecret();

    private final Instant createdAt;
    private final UUID runId;

    public RunListCursor(Instant createdAt, UUID runId) {
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID runId() {
        return runId;
    }

    public String encode() {
        String payload = VERSION + "|" + createdAt + "|" + runId;
        String mac = hmacHex(payload);
        String raw = payload + "|" + mac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static RunListCursor decode(String opaque) {
        if (opaque == null || opaque.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(opaque), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("malformed cursor");
            }
            String payload = parts[0] + "|" + parts[1] + "|" + parts[2];
            if (!MessageDigest.isEqual(
                    hmacHex(payload).getBytes(StandardCharsets.UTF_8),
                    parts[3].getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("cursor integrity check failed");
            }
            Instant createdAt = Instant.parse(parts[1]);
            UUID runId = UUID.fromString(parts[2]);
            return new RunListCursor(createdAt, runId);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed cursor", ex);
        }
    }

    private static String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(SECRET, HMAC_ALG));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    private static byte[] resolveSecret() {
        String env = System.getenv("PATCHATLAS_CURSOR_SECRET");
        if (env != null && !env.isBlank()) {
            return env.getBytes(StandardCharsets.UTF_8);
        }
        // 进程级随机密钥：同一 JVM 内 encode/decode 一致；不在源码中公开固定默认值
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }
}

package io.github.patchatlas.run;

import io.github.patchatlas.replay.TargetTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * 已通过 Patch Gate、可入库的唯一 Candidate Test Patch。
 *
 * <p>读取时必须校验 {@code patch_sha256} 与 UTF-8 原文一致。
 */
public record PersistedCandidatePatch(
        String patchText,
        String patchSha256,
        TargetTest targetTest,
        TestPatchProvenance provenance) {

    public static final int MAX_PATCH_BYTES = 64 * 1024;
    /** 与 SQL {@code char_length(target_class) + 1 + char_length(target_method) <= 256} 对齐。 */
    public static final int MAX_SELECTOR_CHARS = 256;

    public PersistedCandidatePatch {
        Objects.requireNonNull(patchText, "patchText");
        Objects.requireNonNull(patchSha256, "patchSha256");
        Objects.requireNonNull(targetTest, "targetTest");
        Objects.requireNonNull(provenance, "provenance");
        validatePatchText(patchText);
        validateSelector(targetTest);
        String expected = sha256Hex(patchText);
        if (!expected.equals(patchSha256.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("candidate patch hash mismatch");
        }
        patchSha256 = expected;
    }

    public PersistedCandidatePatch(
            String patchText, String patchSha256, TargetTest targetTest) {
        this(patchText, patchSha256, targetTest, TestPatchProvenance.AGENT_GENERATED);
    }

    /** Gate 通过后构造：计算 hash。 */
    public static PersistedCandidatePatch fromAccepted(String patchText, TargetTest targetTest) {
        Objects.requireNonNull(patchText, "patchText");
        Objects.requireNonNull(targetTest, "targetTest");
        validatePatchText(patchText);
        validateSelector(targetTest);
        return new PersistedCandidatePatch(
                patchText,
                sha256Hex(patchText),
                targetTest,
                TestPatchProvenance.AGENT_GENERATED);
    }

    public static PersistedCandidatePatch fromKnownTrigger(
            String patchText, TargetTest targetTest) {
        Objects.requireNonNull(patchText, "patchText");
        Objects.requireNonNull(targetTest, "targetTest");
        validatePatchText(patchText);
        validateSelector(targetTest);
        return new PersistedCandidatePatch(
                patchText,
                sha256Hex(patchText),
                targetTest,
                TestPatchProvenance.KNOWN_TRIGGER);
    }

    /** 从数据库列恢复：校验 hash 与 selector。 */
    public static PersistedCandidatePatch restore(
            String patchText, String storedSha256, String targetClass, String targetMethod) {
        return restore(
                patchText,
                storedSha256,
                targetClass,
                targetMethod,
                TestPatchProvenance.AGENT_GENERATED);
    }

    public static PersistedCandidatePatch restore(
            String patchText,
            String storedSha256,
            String targetClass,
            String targetMethod,
            TestPatchProvenance provenance) {
        Objects.requireNonNull(storedSha256, "storedSha256");
        TargetTest target = new TargetTest(targetClass, targetMethod);
        return new PersistedCandidatePatch(patchText, storedSha256, target, provenance);
    }

    private static void validatePatchText(String patchText) {
        if (patchText.isEmpty()) {
            throw new IllegalArgumentException("patch text must not be empty");
        }
        if (patchText.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("patch text must not contain NUL");
        }
        int bytes = patchText.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PATCH_BYTES) {
            throw new IllegalArgumentException("patch text exceeds 64 KiB");
        }
    }

    private static void validateSelector(TargetTest targetTest) {
        int combined =
                targetTest.className().length() + 1 + targetTest.methodName().length();
        if (combined > MAX_SELECTOR_CHARS) {
            throw new IllegalArgumentException("target selector exceeds " + MAX_SELECTOR_CHARS + " chars");
        }
    }

    static String sha256Hex(String patchText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(patchText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}

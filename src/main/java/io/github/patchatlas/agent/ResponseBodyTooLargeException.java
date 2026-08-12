package io.github.patchatlas.agent;

/**
 * HTTP adapter 在边界截断超大响应时抛出。
 *
 * <p>必须映射为 {@link CallFailureCategory#STRUCTURED_OUTPUT_INVALID}，且<strong>不得</strong>做传输重试。
 */
public final class ResponseBodyTooLargeException extends RuntimeException {

    private final int maxBytes;

    public ResponseBodyTooLargeException(int maxBytes) {
        super("response body exceeds " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }
}

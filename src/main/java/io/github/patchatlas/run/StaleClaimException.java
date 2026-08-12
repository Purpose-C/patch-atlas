package io.github.patchatlas.run;

import java.util.UUID;

/**
 * 写操作未匹配 expected state + lease token + version（被 fence）。
 */
public final class StaleClaimException extends RuntimeException {

    private final UUID runId;

    public StaleClaimException(UUID runId, String message) {
        super(message);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}

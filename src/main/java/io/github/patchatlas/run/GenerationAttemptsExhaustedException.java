package io.github.patchatlas.run;

import java.util.UUID;

/** 全局 generation_attempt_count 已达 3，无法再预占。 */
public final class GenerationAttemptsExhaustedException extends RuntimeException {

    private final UUID runId;

    public GenerationAttemptsExhaustedException(UUID runId) {
        super("generation attempts exhausted for " + runId);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}

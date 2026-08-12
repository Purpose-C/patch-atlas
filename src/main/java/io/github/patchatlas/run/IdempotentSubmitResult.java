package io.github.patchatlas.run;

import java.util.Objects;
import java.util.UUID;

public sealed interface IdempotentSubmitResult
        permits IdempotentSubmitResult.Accepted, IdempotentSubmitResult.Conflict {

    record Accepted(UUID runId, RunState state, boolean created) implements IdempotentSubmitResult {
        public Accepted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(state, "state");
        }
    }

    record Conflict(UUID existingRunId) implements IdempotentSubmitResult {
        public Conflict {
            Objects.requireNonNull(existingRunId, "existingRunId");
        }
    }
}

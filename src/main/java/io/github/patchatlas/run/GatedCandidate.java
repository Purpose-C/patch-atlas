package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.PatchPreparationResult;
import java.util.Objects;

/**
 * 已通过 {@link io.github.patchatlas.agent.PatchGate} 的候选。
 */
public final class GatedCandidate {

    private final PersistedCandidatePatch patch;

    private GatedCandidate(PersistedCandidatePatch patch) {
        this.patch = Objects.requireNonNull(patch, "patch");
    }

    public static GatedCandidate afterSuccessfulGate(
            CandidateDraft draft, PatchPreparationResult.PreparedCandidate prepared) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(prepared, "prepared");
        if (!draft.targetTest().equals(prepared.targetTest())) {
            throw new IllegalArgumentException("gate prepared target does not match draft");
        }
        return new GatedCandidate(
                PersistedCandidatePatch.fromAccepted(draft.patchText(), prepared.targetTest()));
    }

    public PersistedCandidatePatch patch() {
        return patch;
    }
}

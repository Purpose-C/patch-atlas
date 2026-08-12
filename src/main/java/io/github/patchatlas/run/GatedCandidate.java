package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchPreparationResult;
import java.util.Objects;

/**
 * 已通过 {@link io.github.patchatlas.agent.PatchGate} 的候选。
 *
 * <p>只能由 {@link #afterSuccessfulGate} 构造，防止裸 {@link PersistedCandidatePatch}
 * 绕过路径/diff/workspace 策略直接入库。
 */
public final class GatedCandidate {

    private final PersistedCandidatePatch patch;

    private GatedCandidate(PersistedCandidatePatch patch) {
        this.patch = Objects.requireNonNull(patch, "patch");
    }

    /**
     * @param generated 送入 Gate 的同一候选原文
     * @param prepared  {@link io.github.patchatlas.agent.PatchGate#prepare} 成功结果
     */
    public static GatedCandidate afterSuccessfulGate(
            GenerationResult.GeneratedCandidate generated,
            PatchPreparationResult.PreparedCandidate prepared) {
        Objects.requireNonNull(generated, "generated");
        Objects.requireNonNull(prepared, "prepared");
        if (!generated.targetTest().equals(prepared.targetTest())) {
            throw new IllegalArgumentException("gate prepared target does not match generated candidate");
        }
        return new GatedCandidate(
                PersistedCandidatePatch.fromAccepted(generated.patchText(), prepared.targetTest()));
    }

    public PersistedCandidatePatch patch() {
        return patch;
    }
}

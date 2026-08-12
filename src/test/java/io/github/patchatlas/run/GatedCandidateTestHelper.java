package io.github.patchatlas.run;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import java.nio.file.Path;

/** 测试用：从已校验 patch 构造 GatedCandidate。 */
final class GatedCandidateTestHelper {

    private GatedCandidateTestHelper() {}

    static GatedCandidate gated(PersistedCandidatePatch patch) {
        CandidateDraft draft = new CandidateDraft(patch.patchText(), patch.targetTest());
        String selector =
                patch.targetTest().className() + "#" + patch.targetTest().methodName();
        PatchPreparationResult.PreparedCandidate prepared =
                new PatchPreparationResult.PreparedCandidate(
                        Path.of("."),
                        "",
                        patch.targetTest(),
                        new MavenTestCommand("", selector, MavenNetworkMode.OFFLINE));
        return GatedCandidate.afterSuccessfulGate(draft, prepared);
    }
}

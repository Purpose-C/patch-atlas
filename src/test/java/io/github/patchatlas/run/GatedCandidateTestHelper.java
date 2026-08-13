package io.github.patchatlas.run;

/** 测试用：从已校验 patch 构造 GatedCandidate，保留 provenance。 */
final class GatedCandidateTestHelper {

    private GatedCandidateTestHelper() {}

    static GatedCandidate gated(PersistedCandidatePatch patch) {
        return new GatedCandidate(patch);
    }
}

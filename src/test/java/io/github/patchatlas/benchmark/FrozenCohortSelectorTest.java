package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.FrozenCohortSelector.CandidateFacts;
import io.github.patchatlas.benchmark.FrozenCohortSelector.ExclusionCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FrozenCohortSelectorTest {

    private static final String DATASET_REVISION =
            "fe986fb7919be62c2a6f611ee16659e849646798";
    private static final String SEED = "cc279be0a2cfe38a327d24d828a49b8425ae37e7";

    private final FrozenCohortSelector selector =
            new FrozenCohortSelector(DATASET_REVISION, SEED);

    @Test
    void hashesTheFrozenTupleExactly() {
        assertThat(selector.rankingKey("alpha"))
                .isEqualTo("5d91d028ebaceac3ad033419535ed46ed58e552a927b29e3f2dd6334d601d7f8");
    }

    @Test
    void selectionIsIndependentOfMetadataInputOrderAndLimitsProbeQueue() {
        List<CandidateFacts> candidates = IntStream.range(0, 25)
                .mapToObj(i -> eligible("case-" + i))
                .toList();
        List<CandidateFacts> reversed = new ArrayList<>(candidates);
        Collections.reverse(reversed);

        var first = selector.select(candidates);
        var second = selector.select(reversed);

        assertThat(first).isEqualTo(second);
        assertThat(first.rankedEligible()).hasSize(25);
        assertThat(first.probeQueue()).hasSize(FrozenCohortSelector.MAX_DYNAMIC_PROBES);
        assertThat(first.probeQueue())
                .containsExactlyElementsOf(first.rankedEligible().subList(0, 18));
    }

    @Test
    void emitsOneStableStaticExclusionUsingDeclaredPrecedence() {
        List<CandidateFacts> candidates = List.of(
                facts("metadata", false, true, Set.of(17), false, true, true, true, true),
                facts("build", true, false, Set.of(17), false, true, true, true, true),
                facts("java", true, true, Set.of(8, 11), false, true, true, true, true),
                facts("snapshot", true, true, Set.of(21), true, true, true, true, true),
                facts("issue", true, true, Set.of(21), false, false, true, true, true),
                facts("test-change", true, true, Set.of(21), false, true, false, true, true),
                facts("license", true, true, Set.of(21), false, true, true, false, true),
                facts("patch-gate", true, true, Set.of(21), false, true, true, true, false),
                facts("precedence", false, false, Set.of(), true, false, false, false, false));

        var selection = selector.select(candidates);

        assertThat(selection.rankedEligible()).isEmpty();
        assertThat(selection.exclusions())
                .extracting(
                        FrozenCohortSelector.ExcludedCandidate::caseId,
                        FrozenCohortSelector.ExcludedCandidate::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("build", ExclusionCode.UNSUPPORTED_BUILD),
                        org.assertj.core.groups.Tuple.tuple("issue", ExclusionCode.ISSUE_UNAVAILABLE),
                        org.assertj.core.groups.Tuple.tuple("java", ExclusionCode.UNSUPPORTED_JAVA),
                        org.assertj.core.groups.Tuple.tuple("license", ExclusionCode.LICENSE_UNRESOLVED),
                        org.assertj.core.groups.Tuple.tuple("metadata", ExclusionCode.METADATA_INVALID),
                        org.assertj.core.groups.Tuple.tuple("patch-gate", ExclusionCode.PATCH_GATE_INCOMPATIBLE),
                        org.assertj.core.groups.Tuple.tuple("precedence", ExclusionCode.METADATA_INVALID),
                        org.assertj.core.groups.Tuple.tuple("snapshot", ExclusionCode.SNAPSHOT_DEPENDENCY),
                        org.assertj.core.groups.Tuple.tuple("test-change", ExclusionCode.TEST_CHANGE_ABSENT));
    }

    @Test
    void caseIdTieBreakerUsesUnicodeCodePoints() {
        String supplementary = "\uD800\uDC00";
        String privateUse = "\uE000";

        assertThat(FrozenCohortSelector.compareCaseIds(supplementary, privateUse)).isPositive();
    }

    private static CandidateFacts eligible(String caseId) {
        return facts(caseId, true, true, Set.of(17, 21), false, true, true, true, true);
    }

    private static CandidateFacts facts(
            String caseId,
            boolean metadataValid,
            boolean mavenBuild,
            Set<Integer> supportedJavaVersions,
            boolean snapshotDependencyPresent,
            boolean issueAvailable,
            boolean testChangePresent,
            boolean licenseResolved,
            boolean patchGateCompatible) {
        return new CandidateFacts(
                caseId,
                metadataValid,
                mavenBuild,
                supportedJavaVersions,
                snapshotDependencyPresent,
                issueAvailable,
                testChangePresent,
                licenseResolved,
                patchGateCompatible);
    }
}

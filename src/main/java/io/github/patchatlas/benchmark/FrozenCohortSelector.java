package io.github.patchatlas.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic static filtering and ranking for a frozen benchmark cohort. */
public final class FrozenCohortSelector {

    public static final int MAX_DYNAMIC_PROBES = 18;

    public enum ExclusionCode {
        METADATA_INVALID,
        UNSUPPORTED_BUILD,
        UNSUPPORTED_JAVA,
        SNAPSHOT_DEPENDENCY,
        ISSUE_UNAVAILABLE,
        TEST_CHANGE_ABSENT,
        LICENSE_UNRESOLVED,
        PATCH_GATE_INCOMPATIBLE,
        DIAGNOSTIC_CASE,
        SPRING_DEPENDENCY_ABSENT
    }

    public record CandidateFacts(
            String caseId,
            boolean metadataValid,
            boolean mavenBuild,
            Set<Integer> supportedJavaVersions,
            boolean snapshotDependencyPresent,
            boolean issueAvailable,
            boolean testChangePresent,
            boolean licenseResolved,
            boolean patchGateCompatible,
            boolean springDependencyPresent) {

        public CandidateFacts {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId must not be blank");
            }
            supportedJavaVersions = Set.copyOf(
                    Objects.requireNonNull(supportedJavaVersions, "supportedJavaVersions"));
        }
    }

    public record RankedCandidate(String caseId, String sortKey, CandidateFacts facts) {
        public RankedCandidate {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(sortKey, "sortKey");
            Objects.requireNonNull(facts, "facts");
        }
    }

    public record ExcludedCandidate(String caseId, ExclusionCode code) {
        public ExcludedCandidate {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(code, "code");
        }
    }

    public record StaticSelection(
            List<RankedCandidate> rankedEligible,
            List<ExcludedCandidate> exclusions,
            int maxDynamicProbes) {
        public StaticSelection {
            rankedEligible = List.copyOf(Objects.requireNonNull(rankedEligible, "rankedEligible"));
            exclusions = List.copyOf(Objects.requireNonNull(exclusions, "exclusions"));
            if (!SpringCohortFreezeRules.isAllowedProbeLimit(maxDynamicProbes)) {
                throw new IllegalArgumentException("undeclared dynamic probe limit");
            }
        }

        public List<RankedCandidate> probeQueue() {
            return List.copyOf(rankedEligible.subList(
                    0, Math.min(maxDynamicProbes, rankedEligible.size())));
        }
    }

    private static final Comparator<RankedCandidate> RANKING_ORDER = Comparator
            .comparing(RankedCandidate::sortKey)
            .thenComparing(RankedCandidate::caseId, FrozenCohortSelector::compareCaseIds);

    private final String datasetRevision;
    private final String seed;
    private final boolean requireSpring;
    private final int maxDynamicProbes;

    public FrozenCohortSelector(String datasetRevision, String seed) {
        this(datasetRevision, seed, false, MAX_DYNAMIC_PROBES);
    }

    public FrozenCohortSelector(
            String datasetRevision, String seed, boolean requireSpring, int maxDynamicProbes) {
        this.datasetRevision = requireSha(datasetRevision, "datasetRevision");
        this.seed = requireSha(seed, "seed");
        this.requireSpring = requireSpring;
        if (!SpringCohortFreezeRules.isAllowedProbeLimit(maxDynamicProbes)) {
            throw new IllegalArgumentException("undeclared dynamic probe limit");
        }
        this.maxDynamicProbes = maxDynamicProbes;
    }

    public StaticSelection select(List<CandidateFacts> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<RankedCandidate> eligible = new ArrayList<>();
        List<ExcludedCandidate> excluded = new ArrayList<>();

        for (CandidateFacts candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            Optional<ExclusionCode> exclusion = exclusionFor(candidate);
            if (exclusion.isPresent()) {
                excluded.add(new ExcludedCandidate(candidate.caseId(), exclusion.orElseThrow()));
            } else {
                eligible.add(new RankedCandidate(
                        candidate.caseId(), rankingKey(candidate.caseId()), candidate));
            }
        }

        eligible.sort(RANKING_ORDER);
        excluded.sort((left, right) -> compareCaseIds(left.caseId(), right.caseId()));
        return new StaticSelection(eligible, excluded, maxDynamicProbes);
    }

    public String rankingKey(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        String tuple = datasetRevision + "\n" + caseId + "\n" + seed;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tuple.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static int compareCaseIds(String left, String right) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int shared = Math.min(leftPoints.length, rightPoints.length);
        for (int i = 0; i < shared; i++) {
            int compared = Integer.compare(leftPoints[i], rightPoints[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftPoints.length, rightPoints.length);
    }

    private Optional<ExclusionCode> exclusionFor(CandidateFacts candidate) {
        if (!candidate.metadataValid()) {
            return Optional.of(ExclusionCode.METADATA_INVALID);
        }
        if (!candidate.mavenBuild()) {
            return Optional.of(ExclusionCode.UNSUPPORTED_BUILD);
        }
        if (!candidate.supportedJavaVersions().contains(17)
                && !candidate.supportedJavaVersions().contains(21)) {
            return Optional.of(ExclusionCode.UNSUPPORTED_JAVA);
        }
        if (candidate.snapshotDependencyPresent()) {
            return Optional.of(ExclusionCode.SNAPSHOT_DEPENDENCY);
        }
        if (!candidate.issueAvailable()) {
            return Optional.of(ExclusionCode.ISSUE_UNAVAILABLE);
        }
        if (!candidate.testChangePresent()) {
            return Optional.of(ExclusionCode.TEST_CHANGE_ABSENT);
        }
        if (!candidate.licenseResolved()) {
            return Optional.of(ExclusionCode.LICENSE_UNRESOLVED);
        }
        if (!candidate.patchGateCompatible()) {
            return Optional.of(ExclusionCode.PATCH_GATE_INCOMPATIBLE);
        }
        if (requireSpring && SpringCohortFreezeRules.isDiagnosticCase(candidate.caseId())) {
            return Optional.of(ExclusionCode.DIAGNOSTIC_CASE);
        }
        if (requireSpring && !candidate.springDependencyPresent()) {
            return Optional.of(ExclusionCode.SPRING_DEPENDENCY_ABSENT);
        }
        return Optional.empty();
    }

    private static String requireSha(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException(field + " must be 40 lowercase hex chars");
        }
        return value;
    }
}

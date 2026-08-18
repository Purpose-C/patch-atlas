package io.github.patchatlas.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Recomputable freeze rules for the Spring usage cohort.
 *
 * <p>Does not inspect issue text, test patches, or fix patches. Spring usage is the
 * {@code pom.xml} {@code <dependency>} groupId gate only.
 */
public final class SpringCohortFreezeRules {

    public static final String SELECTOR_VERSION = "spring-v1";
    public static final int MAX_DYNAMIC_PROBES = 24;
    public static final int TARGET_SIZE = 6;
    public static final int MIN_SIZE = 4;
    public static final String SEED = BenchmarkArtifacts.SEED;
    public static final String RANKING_REVISION = "12d857dbb984911dcfbb40a89a6e246b6a13cdbd";
    public static final String SCAN_PATH = "benchmark-cases/spring-source-gate/scan.json";

    public static final String RULES = """
            ranking_revision=12d857dbb984911dcfbb40a89a6e246b6a13cdbd
            ranking_preimage=sha256(spring-source-gate\\n<source instanceIdsSha256 in declared order>)[:40]
            source_order=gitbug-java,multi-swe-bench-java,swe-polybench-java
            selector=spring-v1
            seed=cc279be0a2cfe38a327d24d828a49b8425ae37e7
            max_dynamic_probes=24
            target_size=6
            min_size=4
            require_spring=true
            spring_condition=buggy_revision_pom_dependency_groupId_contains_org.springframework
            ignore=parent_plugin_coordinates,repository_name,issue_text,test_patch,fix_patch
            diagnostic_denylist=spring-cloud-openfeign,scof-1326
            parent_revision=fixed_first_parent_equals_buggy
            """;

    private SpringCohortFreezeRules() {}

    public static FrozenCohortSelector selector() {
        return new FrozenCohortSelector(RANKING_REVISION, SEED, true, MAX_DYNAMIC_PROBES);
    }

    public static boolean isDiagnosticCase(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return false;
        }
        String folded = caseId.toLowerCase(Locale.ROOT);
        return folded.contains("spring-cloud-openfeign") || folded.contains("scof-1326");
    }

    public static boolean isAllowedProbeLimit(int maxDynamicProbes) {
        return maxDynamicProbes == FrozenCohortSelector.MAX_DYNAMIC_PROBES
                || maxDynamicProbes == MAX_DYNAMIC_PROBES;
    }

    public static String rankingRevision(
            String gitbugInstanceIdsSha256,
            String multiSweInstanceIdsSha256,
            String polybenchInstanceIdsSha256) {
        Objects.requireNonNull(gitbugInstanceIdsSha256, "gitbugInstanceIdsSha256");
        Objects.requireNonNull(multiSweInstanceIdsSha256, "multiSweInstanceIdsSha256");
        Objects.requireNonNull(polybenchInstanceIdsSha256, "polybenchInstanceIdsSha256");
        String preimage = "spring-source-gate\n"
                + gitbugInstanceIdsSha256
                + "\n"
                + multiSweInstanceIdsSha256
                + "\n"
                + polybenchInstanceIdsSha256;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(preimage.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

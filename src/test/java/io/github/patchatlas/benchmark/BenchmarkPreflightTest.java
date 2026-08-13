package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkPreflight.Result;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BenchmarkPreflightTest {

    @Test
    void missingApiKeyIsNotReady() {
        FakeTestGenerator generator = FakeTestGenerator.of(unusedDraft());
        BenchmarkPreflight preflight = preflight("", true, image -> true);

        Result result = preflight.check(validCohort());

        assertThat(result).isInstanceOf(Result.NotReady.class);
        assertThat(((Result.NotReady) result).reasons()).anyMatch(reason -> reason.toLowerCase().contains("key"));
        assertThat(generator.callCount()).isZero();
    }

    @Test
    void tamperedCohortDigestIsNotReady() {
        Cohort tampered = new Cohort(
                validCohort().datasetRevision(),
                validCohort().seed(),
                validCohort().selectorVersion(),
                validCohort().rulesSha256(),
                "b".repeat(64),
                validCohort().cases(),
                List.of());

        Result result = readyPreflight().check(tampered);

        assertThat(result).isInstanceOf(Result.NotReady.class);
        assertThat(((Result.NotReady) result).reasons()).anyMatch(reason -> reason.contains("cohort"));
    }

    @Test
    void allSharedPreconditionsYieldReady() {
        assertThat(readyPreflight().check(validCohort())).isInstanceOf(Result.Ready.class);
    }

    @Test
    void notReadyReasonsDoNotLeakSecretsPathsOrExceptionText() {
        BenchmarkPreflight preflight = new BenchmarkPreflight(
                () -> {
                    throw new RuntimeException(
                            "password=sk-secret jdbc:postgresql://localhost /opt/secret/proj boom");
                },
                new BenchmarkPreflight.DockerProbe() {
                    @Override
                    public boolean daemonReady() {
                        return true;
                    }

                    @Override
                    public boolean imagePresent(String image) {
                        return true;
                    }
                },
                () -> BenchmarkPreflight.MIN_FREE_BYTES + 1,
                () -> "sk-secret-value");

        Result result = preflight.check(validCohort());

        assertThat(result).isInstanceOf(Result.NotReady.class);
        String reasons = String.join(" ", ((Result.NotReady) result).reasons());
        assertThat(reasons).doesNotContain("sk-secret");
        assertThat(reasons).doesNotContain("/opt/secret");
        assertThat(reasons).doesNotContain("password=");
        assertThat(reasons).doesNotContain("boom");
    }

    @Test
    void preflightDoesNotCallTheModel() {
        FakeTestGenerator generator = FakeTestGenerator.of(unusedDraft());

        readyPreflight().check(validCohort());

        assertThat(generator.callCount()).isZero();
    }

    @Test
    void dockerImageMustExistForCohortJavaVersions() {
        AtomicBoolean inspected21 = new AtomicBoolean();
        BenchmarkPreflight preflight = new BenchmarkPreflight(
                () -> {},
                new BenchmarkPreflight.DockerProbe() {
                    @Override
                    public boolean daemonReady() {
                        return true;
                    }

                    @Override
                    public boolean imagePresent(String image) {
                        if (image.contains("temurin-21")) {
                            inspected21.set(true);
                            return false;
                        }
                        return true;
                    }
                },
                () -> BenchmarkPreflight.MIN_FREE_BYTES + 1,
                () -> "sk-test");

        Result result = preflight.check(mixedJavaCohort());

        assertThat(result).isInstanceOf(Result.NotReady.class);
        assertThat(inspected21).isTrue();
        assertThat(((Result.NotReady) result).reasons()).anyMatch(reason -> reason.contains("image"));
    }

    private static BenchmarkPreflight readyPreflight() {
        return preflight("sk-test", true, image -> true);
    }

    private static BenchmarkPreflight preflight(
            String apiKey, boolean dockerReady, java.util.function.Predicate<String> images) {
        return new BenchmarkPreflight(
                () -> {},
                new BenchmarkPreflight.DockerProbe() {
                    @Override
                    public boolean daemonReady() {
                        return dockerReady;
                    }

                    @Override
                    public boolean imagePresent(String image) {
                        return images.test(image);
                    }
                },
                () -> BenchmarkPreflight.MIN_FREE_BYTES + 1,
                () -> apiKey);
    }

    private static GenerationResult unusedDraft() {
        return new GenerationResult.GenerationCallFailure(CallFailureCategory.MODEL_UNAVAILABLE, "unused");
    }

    private static Cohort validCohort() {
        return cohort("17", "17", "17", "17", "17", "17");
    }

    private static Cohort mixedJavaCohort() {
        return cohort("17", "17", "17", "21", "21", "21");
    }

    private static Cohort cohort(String... javaVersions) {
        List<CohortCase> cases = List.of(
                caseAt(1, BenchmarkArtifacts.Role.CALIBRATION, javaVersions[0]),
                caseAt(2, BenchmarkArtifacts.Role.CALIBRATION, javaVersions[1]),
                caseAt(3, BenchmarkArtifacts.Role.CALIBRATION, javaVersions[2]),
                caseAt(4, BenchmarkArtifacts.Role.AGENT_BENCHMARK, javaVersions[3]),
                caseAt(5, BenchmarkArtifacts.Role.AGENT_BENCHMARK, javaVersions[4]),
                caseAt(6, BenchmarkArtifacts.Role.AGENT_BENCHMARK, javaVersions[5]));
        return new Cohort(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                "a".repeat(40),
                BenchmarkArtifacts.cohortSha256(cases),
                cases,
                List.of());
    }

    private static CohortCase caseAt(int position, BenchmarkArtifacts.Role role, String javaVersion) {
        return new CohortCase(
                position,
                role,
                "case-" + position,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT",
                "",
                javaVersion);
    }
}

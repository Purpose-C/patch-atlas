package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkPreflight.Result;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunPurpose;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TestPatchProvenance;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormalBenchmarkRunnerTest {

    private static final TargetTest TARGET = new TargetTest("c.T", "m");

    @Test
    void repeatedAgentActionCreatesOnlyOneRun() {
        ScriptedStore store = new ScriptedStore();
        CountingOps ops = new CountingOps(store);
        FormalBenchmarkRunner runner = runner(readyPreflight(), store, ops, true);

        runner.execute("agent-4", validCohort());
        FormalBenchmarkRunner.Outcome second = runner.execute("agent-4", validCohort());

        assertThat(ops.agentLaunches.get()).isEqualTo(1);
        assertThat(ops.calibrationLaunches.get()).isZero();
        assertThat(store.created).hasSize(1);
        assertThat(second).isInstanceOf(FormalBenchmarkRunner.Outcome.Finished.class);
        RunDetailView detail = ((FormalBenchmarkRunner.Outcome.Finished) second).details().getFirst();
        assertThat(detail.purpose()).isEqualTo(RunPurpose.AGENT_BENCHMARK);
        assertThat(detail.candidate().orElseThrow().provenance())
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
    }

    @Test
    void preflightNotReadyCreatesNoRunAndDoesNotCallModel() {
        FakeTestGenerator generator = FakeTestGenerator.of(
                new io.github.patchatlas.agent.GenerationResult.GenerationCallFailure(
                        io.github.patchatlas.agent.CallFailureCategory.MODEL_UNAVAILABLE, "unused"));
        ScriptedStore store = new ScriptedStore();
        CountingOps ops = new CountingOps(store);
        BenchmarkPreflight preflight = new BenchmarkPreflight(
                () -> {},
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
                () -> "");
        FormalBenchmarkRunner runner = runner(preflight, store, ops, true);

        FormalBenchmarkRunner.Outcome outcome = runner.execute("calibrate", validCohort());

        assertThat(outcome).isInstanceOf(FormalBenchmarkRunner.Outcome.PreflightFailed.class);
        assertThat(store.created).isEmpty();
        assertThat(ops.calibrationLaunches.get()).isZero();
        assertThat(ops.agentLaunches.get()).isZero();
        assertThat(generator.callCount()).isZero();
    }

    @Test
    void waitTimeoutDoesNotCreateSubstituteOrRewriteState() {
        ScriptedStore store = new ScriptedStore();
        CountingOps ops = new CountingOps(store);
        FormalBenchmarkRunner runner = runner(readyPreflight(), store, ops, false);

        FormalBenchmarkRunner.Outcome first = runner.execute("agent-5", validCohort());
        RunState stateAfterTimeout = store.findRunByCase(
                        validCohort().cases().get(4).caseId(), RunPurpose.AGENT_BENCHMARK)
                .orElseThrow()
                .state();
        FormalBenchmarkRunner.Outcome second = runner.execute("agent-5", validCohort());

        assertThat(first).isInstanceOf(FormalBenchmarkRunner.Outcome.TimedOut.class);
        assertThat(second).isInstanceOf(FormalBenchmarkRunner.Outcome.TimedOut.class);
        assertThat(((FormalBenchmarkRunner.Outcome.TimedOut) first).runId())
                .isEqualTo(((FormalBenchmarkRunner.Outcome.TimedOut) second).runId());
        assertThat(ops.agentLaunches.get()).isEqualTo(1);
        assertThat(store.created).hasSize(1);
        assertThat(stateAfterTimeout).isEqualTo(RunState.QUEUED);
        assertThat(store.findRunByCase(
                        validCohort().cases().get(4).caseId(), RunPurpose.AGENT_BENCHMARK)
                .orElseThrow()
                .state()).isEqualTo(RunState.QUEUED);
    }

    @Test
    void calibrateUsesCalibrationLaunchWithZeroGenerationAttempts() {
        ScriptedStore store = new ScriptedStore();
        CountingOps ops = new CountingOps(store);
        FormalBenchmarkRunner runner = runner(readyPreflight(), store, ops, true);

        FormalBenchmarkRunner.Outcome outcome = runner.execute("calibrate", validCohort());

        assertThat(ops.calibrationLaunches.get()).isEqualTo(3);
        assertThat(ops.agentLaunches.get()).isZero();
        assertThat(outcome).isInstanceOf(FormalBenchmarkRunner.Outcome.Finished.class);
        List<RunDetailView> details = ((FormalBenchmarkRunner.Outcome.Finished) outcome).details();
        assertThat(details).hasSize(3);
        assertThat(details).allMatch(detail -> detail.purpose() == RunPurpose.CALIBRATION);
        assertThat(details).allMatch(detail -> detail.generation().attemptCount() == 0);
        assertThat(details).allMatch(detail ->
                detail.candidate().orElseThrow().provenance() == TestPatchProvenance.KNOWN_TRIGGER);
    }

    @Test
    void agentActionUsesAgentLaunch() {
        ScriptedStore store = new ScriptedStore();
        CountingOps ops = new CountingOps(store);
        FormalBenchmarkRunner.Outcome outcome =
                runner(readyPreflight(), store, ops, true).execute("agent-6", validCohort());

        assertThat(ops.agentLaunches.get()).isEqualTo(1);
        assertThat(ops.calibrationLaunches.get()).isZero();
        assertThat(outcome).isInstanceOf(FormalBenchmarkRunner.Outcome.Finished.class);
        assertThat(((FormalBenchmarkRunner.Outcome.Finished) outcome).details().getFirst().purpose())
                .isEqualTo(RunPurpose.AGENT_BENCHMARK);
    }

    @Test
    void illegalActionIsRejectedByClosedSet() {
        ScriptedStore store = new ScriptedStore();
        assertThatThrownBy(() -> runner(readyPreflight(), store, new CountingOps(store), true)
                        .execute("agent-7", validCohort()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown benchmark action");
    }

    @Test
    void verifyDoesNotCreateRuns() {
        ScriptedStore store = new ScriptedStore();
        CountingOps ops = new CountingOps(store);
        seedCompletedCohort(store);
        FormalBenchmarkRunner.Outcome outcome =
                runner(readyPreflight(), store, ops, true).execute("verify", validCohort());

        assertThat(ops.calibrationLaunches.get()).isZero();
        assertThat(ops.agentLaunches.get()).isZero();
        assertThat(ops.verifyCalls.get()).isEqualTo(1);
        assertThat(outcome).isInstanceOf(FormalBenchmarkRunner.Outcome.Verified.class);
    }

    private static FormalBenchmarkRunner runner(
            BenchmarkPreflight preflight,
            ScriptedStore store,
            CountingOps ops,
            boolean complete) {
        return new FormalBenchmarkRunner(
                preflight,
                store,
                ops,
                (runId, budget) -> complete ? store.complete(runId) : Optional.empty());
    }

    private static BenchmarkPreflight readyPreflight() {
        return new BenchmarkPreflight(
                () -> {},
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
                () -> "sk-test");
    }

    private static void seedCompletedCohort(ScriptedStore store) {
        Cohort cohort = validCohort();
        for (CohortCase item : cohort.cases()) {
            RunPurpose purpose = item.position() <= 3
                    ? RunPurpose.CALIBRATION
                    : RunPurpose.AGENT_BENCHMARK;
            TestPatchProvenance provenance = purpose == RunPurpose.CALIBRATION
                    ? TestPatchProvenance.KNOWN_TRIGGER
                    : TestPatchProvenance.AGENT_GENERATED;
            store.put(view(
                    UUID.randomUUID(),
                    item.caseId(),
                    purpose,
                    RunState.COMPLETED,
                    provenance,
                    purpose == RunPurpose.CALIBRATION ? 0 : 1));
        }
    }

    private static Cohort validCohort() {
        List<CohortCase> cases = List.of(
                caseAt(1, BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(2, BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(3, BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(4, BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(5, BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(6, BenchmarkArtifacts.Role.AGENT_BENCHMARK));
        return new Cohort(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                "a".repeat(40),
                BenchmarkArtifacts.cohortSha256(cases),
                cases,
                List.of());
    }

    private static CohortCase caseAt(int position, BenchmarkArtifacts.Role role) {
        return new CohortCase(
                position,
                role,
                "case-" + position,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT",
                "",
                "17");
    }

    private static RunDetailView view(
            UUID runId,
            String caseId,
            RunPurpose purpose,
            RunState state,
            TestPatchProvenance provenance,
            int attempts) {
        Optional<RunDetailView.CandidateView> candidate = provenance == null
                ? Optional.empty()
                : Optional.of(new RunDetailView.CandidateView(
                        "diff --git a/src/test/java/c/T.java b/src/test/java/c/T.java\n",
                        BenchmarkArtifacts.sha256(
                                "diff --git a/src/test/java/c/T.java b/src/test/java/c/T.java\n"),
                        TARGET,
                        provenance));
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        return new RunDetailView(
                runId,
                VerificationMode.HISTORICAL,
                purpose,
                state,
                caseId,
                now,
                now,
                state.isTerminal() ? now : null,
                new RunDetailView.InputSummary(
                        "https://github.com/ex/repo.git",
                        "https://github.com/ex/repo/issues/1",
                        "title",
                        "body",
                        "a".repeat(40),
                        "b".repeat(40),
                        ""),
                new MavenExecutionPolicy("17", MavenNetworkMode.OFFLINE),
                new RunDetailView.GenerationMeta(attempts, null, null, 0, 0, 0, 0),
                candidate,
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    private static final class CountingOps implements FormalBenchmarkRunner.Operations {
        private final ScriptedStore store;
        private final AtomicInteger calibrationLaunches = new AtomicInteger();
        private final AtomicInteger agentLaunches = new AtomicInteger();
        private final AtomicInteger verifyCalls = new AtomicInteger();

        private CountingOps(ScriptedStore store) {
            this.store = store;
        }

        @Override
        public UUID launchCalibration(CohortCase cohortCase) {
            calibrationLaunches.incrementAndGet();
            return store.create(
                    cohortCase.caseId(),
                    RunPurpose.CALIBRATION,
                    TestPatchProvenance.KNOWN_TRIGGER,
                    RunState.REPLAYING,
                    0);
        }

        @Override
        public UUID launchAgent(CohortCase cohortCase) {
            agentLaunches.incrementAndGet();
            return store.create(
                    cohortCase.caseId(),
                    RunPurpose.AGENT_BENCHMARK,
                    null,
                    RunState.QUEUED,
                    0);
        }

        @Override
        public Path exportEvidence(Cohort cohort, List<RunDetailView> details) {
            verifyCalls.incrementAndGet();
            return Path.of("results.json");
        }
    }

    private static final class ScriptedStore implements FormalBenchmarkRunner.Store {
        private final Map<UUID, RunDetailView> byId = new LinkedHashMap<>();
        private final List<UUID> created = new ArrayList<>();

        UUID create(
                String caseId,
                RunPurpose purpose,
                TestPatchProvenance provenance,
                RunState state,
                int attempts) {
            UUID id = UUID.randomUUID();
            put(view(id, caseId, purpose, state, provenance, attempts));
            created.add(id);
            return id;
        }

        void put(RunDetailView detail) {
            byId.put(detail.runId(), detail);
        }

        Optional<RunDetailView> complete(UUID runId) {
            RunDetailView current = byId.get(runId);
            if (current == null) {
                return Optional.empty();
            }
            TestPatchProvenance provenance = current.candidate()
                    .map(RunDetailView.CandidateView::provenance)
                    .orElse(current.purpose() == RunPurpose.CALIBRATION
                            ? TestPatchProvenance.KNOWN_TRIGGER
                            : TestPatchProvenance.AGENT_GENERATED);
            int attempts = current.purpose() == RunPurpose.CALIBRATION ? 0 : 1;
            RunDetailView completed = view(
                    current.runId(),
                    current.caseId(),
                    current.purpose(),
                    RunState.COMPLETED,
                    provenance,
                    attempts);
            put(completed);
            return Optional.of(completed);
        }

        @Override
        public Optional<RunDetailView> findRunByCase(String caseId, RunPurpose purpose) {
            return byId.values().stream()
                    .filter(detail -> caseId.equals(detail.caseId()) && detail.purpose() == purpose)
                    .findFirst();
        }

        @Override
        public Optional<RunDetailView> findRunDetail(UUID runId) {
            return Optional.ofNullable(byId.get(runId));
        }
    }
}

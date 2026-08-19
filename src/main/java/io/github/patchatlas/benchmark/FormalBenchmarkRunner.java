package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkPreflight.Result;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunPurpose;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Offline-testable formal benchmark collaborator. The tagged harness only assembles
 * production adapters around this type.
 */
public final class FormalBenchmarkRunner {

    public static final Duration CALIBRATION_WAIT = Duration.ofMinutes(20);
    public static final Duration AGENT_WAIT = Duration.ofMinutes(60);
    public static final String RESUME_MESSAGE =
            "timed out waiting for the same runId; retry the same action to resume";

    public sealed interface Outcome permits Outcome.PreflightFailed, Outcome.TimedOut,
            Outcome.Finished, Outcome.Verified {
        record PreflightFailed(List<String> reasons) implements Outcome {
            public PreflightFailed {
                reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            }
        }

        record TimedOut(UUID runId, String message) implements Outcome {
            public TimedOut {
                Objects.requireNonNull(runId, "runId");
                Objects.requireNonNull(message, "message");
            }
        }

        record Finished(List<RunDetailView> details) implements Outcome {
            public Finished {
                details = List.copyOf(Objects.requireNonNull(details, "details"));
            }
        }

        record Verified(Path output) implements Outcome {
            public Verified {
                Objects.requireNonNull(output, "output");
            }
        }
    }

    public interface Store {
        Optional<RunDetailView> findRunByCase(String caseId, RunPurpose purpose);

        Optional<RunDetailView> findRunByCase(String caseId, RunPurpose purpose, ContextOrigin origin);

        Optional<RunDetailView> findRunByCase(
                String caseId, RunPurpose purpose, ContextOrigin origin, String evaluationId);

        Optional<RunDetailView> findRunDetail(UUID runId);
    }

    public interface Operations {
        UUID launchCalibration(CohortCase cohortCase);

        UUID launchAgent(CohortCase cohortCase);

        UUID launchAgent(CohortCase cohortCase, ContextOrigin origin);

        default UUID launchAgent(CohortCase cohortCase, ContextOrigin origin, String evaluationId) {
            return launchAgent(cohortCase, origin);
        }

        UUID launchDiagnostic(ContextOrigin origin);

        default UUID launchDiagnostic() {
            return launchDiagnostic(ContextOrigin.HEURISTIC);
        }

        Path exportEvidence(Cohort cohort, List<RunDetailView> details) throws IOException;

        Path exportThreeArmEvidence(Cohort cohort, List<ThreeArmRun> runs) throws IOException;

        default Path exportThreeArmEvidence(
                Cohort cohort, List<ThreeArmRun> runs, String evaluationId) throws IOException {
            return exportThreeArmEvidence(cohort, runs);
        }
    }

    @FunctionalInterface
    public interface RunWaiter {
        Optional<RunDetailView> awaitTerminal(UUID runId, Duration budget);
    }

    private final BenchmarkPreflight preflight;
    private final Store store;
    private final Operations operations;
    private final RunWaiter waiter;

    public FormalBenchmarkRunner(
            BenchmarkPreflight preflight,
            Store store,
            Operations operations,
            RunWaiter waiter) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.store = Objects.requireNonNull(store, "store");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    public Outcome execute(String action, Cohort cohort) {
        Objects.requireNonNull(cohort, "cohort");
        String parsed = BenchmarkActions.parseAction(action);
        if (BenchmarkActions.FREEZE.equals(parsed)) {
            throw new IllegalArgumentException("freeze is not a formal run action");
        }
        if (BenchmarkActions.VERIFY.equals(parsed)) {
            return verify(cohort);
        }
        if (BenchmarkActions.VERIFY_THREE_ARM.equals(parsed)
                || BenchmarkActions.VERIFY_THREE_ARM_036.equals(parsed)) {
            return verifyThreeArm(cohort, BenchmarkActions.threeArmEvaluationId(parsed));
        }
        if (parsed.startsWith("dry-run")) {
            return dryRun(BenchmarkActions.locatingOrigin(parsed));
        }
        if (BenchmarkActions.isCaseStudy(parsed)) {
            throw new IllegalArgumentException("use executeCaseStudy for " + parsed);
        }

        Result checked = preflight.check(cohort);
        if (checked instanceof Result.NotReady notReady) {
            return new Outcome.PreflightFailed(notReady.reasons());
        }

        return switch (parsed) {
            case BenchmarkActions.CALIBRATE -> runPositions(cohort, 1, 3, RunPurpose.CALIBRATION);
            case BenchmarkActions.CALIBRATE_1,
                    BenchmarkActions.CALIBRATE_2,
                    BenchmarkActions.CALIBRATE_3 -> {
                int position = BenchmarkActions.calibratePosition(parsed);
                yield runPositions(cohort, position, position, RunPurpose.CALIBRATION);
            }
            case BenchmarkActions.AGENT_4, BenchmarkActions.AGENT_5, BenchmarkActions.AGENT_6 -> {
                int position = BenchmarkActions.agentPosition(parsed);
                yield runPositions(cohort, position, position, RunPurpose.AGENT_BENCHMARK);
            }
            case BenchmarkActions.ARM_HEURISTIC, BenchmarkActions.ARM_TEXT, BenchmarkActions.ARM_GRAPH ->
                    runArm(
                            cohort.cases(),
                            BenchmarkActions.locatingOrigin(parsed),
                            BenchmarkActions.threeArmEvaluationId(parsed));
            default -> throw new IllegalArgumentException("unsupported formal action " + parsed);
        };
    }

    /**
     * Single confirmed case, three-arm evaluation id. Preflight still checks the frozen
     * six-case cohort digest and shared hosts; the launched case is {@code studyCase}.
     */
    public Outcome executeCaseStudy(String action, Cohort preflightCohort, CohortCase studyCase) {
        Objects.requireNonNull(preflightCohort, "preflightCohort");
        Objects.requireNonNull(studyCase, "studyCase");
        String parsed = BenchmarkActions.parseAction(action);
        if (!BenchmarkActions.isCaseStudy(parsed)) {
            throw new IllegalArgumentException(parsed + " is not a case-study action");
        }
        Result checked = preflight.check(preflightCohort);
        if (checked instanceof Result.NotReady notReady) {
            return new Outcome.PreflightFailed(notReady.reasons());
        }
        return runArm(
                List.of(studyCase),
                BenchmarkActions.locatingOrigin(parsed),
                BenchmarkActions.threeArmEvaluationId(parsed));
    }

    private Outcome runPositions(Cohort cohort, int from, int to, RunPurpose purpose) {
        List<RunDetailView> details = new ArrayList<>();
        Duration budget = purpose == RunPurpose.CALIBRATION ? CALIBRATION_WAIT : AGENT_WAIT;
        for (int position = from; position <= to; position++) {
            CohortCase cohortCase = cohort.cases().get(position - 1);
            Optional<RunDetailView> existing = store.findRunByCase(cohortCase.caseId(), purpose);
            UUID runId;
            if (existing.isPresent()) {
                runId = existing.orElseThrow().runId();
            } else if (purpose == RunPurpose.CALIBRATION) {
                runId = operations.launchCalibration(cohortCase);
            } else {
                runId = operations.launchAgent(cohortCase);
            }
            Optional<RunDetailView> terminal = waiter.awaitTerminal(runId, budget);
            if (terminal.isEmpty()) {
                return new Outcome.TimedOut(runId, RESUME_MESSAGE);
            }
            details.add(terminal.orElseThrow());
        }
        return new Outcome.Finished(details);
    }

    private Outcome runArm(List<CohortCase> cases, ContextOrigin origin, String evaluationId) {
        List<RunDetailView> details = new ArrayList<>();
        for (CohortCase cohortCase : cases) {
            Optional<RunDetailView> existing = store.findRunByCase(
                    cohortCase.caseId(), RunPurpose.AGENT_BENCHMARK, origin, evaluationId);
            UUID runId = existing.isPresent()
                    ? existing.orElseThrow().runId()
                    : operations.launchAgent(cohortCase, origin, evaluationId);
            Optional<RunDetailView> terminal = waiter.awaitTerminal(runId, AGENT_WAIT);
            if (terminal.isEmpty()) {
                return new Outcome.TimedOut(runId, RESUME_MESSAGE);
            }
            details.add(terminal.orElseThrow());
        }
        return new Outcome.Finished(details);
    }

    private Outcome verifyThreeArm(Cohort cohort, String evaluationId) {
        List<ThreeArmRun> runs = new ArrayList<>(18);
        for (ContextOrigin origin : ThreeArmEvidenceExporter.ARMS) {
            for (CohortCase cohortCase : cohort.cases()) {
                RunDetailView detail = store.findRunByCase(
                                cohortCase.caseId(),
                                RunPurpose.AGENT_BENCHMARK,
                                origin,
                                evaluationId)
                        .orElseThrow(() -> new IllegalStateException(
                                "missing " + origin + " run for case " + cohortCase.caseId()));
                if (!detail.state().isTerminal()) {
                    throw new IllegalStateException(
                            origin + " run for case " + cohortCase.caseId() + " is not terminal");
                }
                if (detail.purpose() != RunPurpose.AGENT_BENCHMARK) {
                    throw new IllegalStateException(
                            "three-arm verify requires AGENT_BENCHMARK, got " + detail.purpose());
                }
                runs.add(new ThreeArmRun(origin, detail));
            }
        }
        try {
            return new Outcome.Verified(
                    operations.exportThreeArmEvidence(cohort, runs, evaluationId));
        } catch (IOException ex) {
            throw new IllegalStateException("three-arm evidence export failed", ex);
        }
    }

    private Outcome verify(Cohort cohort) {
        List<RunDetailView> details = new ArrayList<>(6);
        for (CohortCase cohortCase : cohort.cases()) {
            RunPurpose purpose = cohortCase.position() <= 3
                    ? RunPurpose.CALIBRATION
                    : RunPurpose.AGENT_BENCHMARK;
            RunDetailView detail = store.findRunByCase(cohortCase.caseId(), purpose)
                    .orElseThrow(() -> new IllegalStateException(
                            "missing run for case " + cohortCase.caseId()));
            if (!detail.state().isTerminal()) {
                throw new IllegalStateException(
                        "run for case " + cohortCase.caseId() + " is not terminal");
            }
            details.add(detail);
        }
        try {
            return new Outcome.Verified(operations.exportEvidence(cohort, details));
        } catch (IOException ex) {
            throw new IllegalStateException("evidence export failed", ex);
        }
    }

    private Outcome dryRun(ContextOrigin origin) {
        UUID runId = operations.launchDiagnostic(origin);
        Optional<RunDetailView> terminal = waiter.awaitTerminal(runId, AGENT_WAIT);
        if (terminal.isEmpty()) {
            return new Outcome.TimedOut(runId, RESUME_MESSAGE);
        }
        return new Outcome.Finished(List.of(terminal.orElseThrow()));
    }
}

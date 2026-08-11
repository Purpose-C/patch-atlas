package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 对单一工作区执行固定两次：信任边界校验 → 清理报告 → 沙箱执行 → 解析 → 归约 → 稳定化。
 *
 * <p>任何宿主写操作（清理）之前必须通过 {@link WorkspaceTrust}。
 */
public final class SideReplayRunner {

    private final SandboxRunner sandboxRunner;
    private final Path allowedWorkspaceRoot;
    private final SurefireReportCleaner cleaner;
    private final SurefireReportParser parser;
    private final ExecutionClassifier classifier;
    private final SideEvidenceStabilizer stabilizer;

    public SideReplayRunner(SandboxRunner sandboxRunner, Path allowedWorkspaceRoot) {
        this(
                sandboxRunner,
                allowedWorkspaceRoot,
                new SurefireReportCleaner(),
                new SurefireReportParser(),
                new ExecutionClassifier(),
                new SideEvidenceStabilizer());
    }

    SideReplayRunner(
            SandboxRunner sandboxRunner,
            Path allowedWorkspaceRoot,
            SurefireReportCleaner cleaner,
            SurefireReportParser parser,
            ExecutionClassifier classifier,
            SideEvidenceStabilizer stabilizer) {
        this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner");
        this.allowedWorkspaceRoot = WorkspaceTrust.normalizeAllowedRoot(allowedWorkspaceRoot);
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.stabilizer = Objects.requireNonNull(stabilizer, "stabilizer");
    }

    public SideExecutionResult runSide(Path workspace, MavenTestCommand command, TargetTest targetTest) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(targetTest, "targetTest");

        List<AttemptRecord> attempts = new ArrayList<>(SideEvidenceStabilizer.REQUIRED_ATTEMPTS);
        for (int i = 0; i < SideEvidenceStabilizer.REQUIRED_ATTEMPTS; i++) {
            attempts.add(runOnce(workspace, command, targetTest));
        }

        List<SingleAttemptEvidence> evidences =
                attempts.stream().map(AttemptRecord::targetEvidence).toList();
        StableSideEvidence stable = stabilizer.stabilize(evidences);

        Optional<RunOutcome> aggregated = aggregateOutcomes(attempts);
        if (aggregated.isPresent() && aggregated.get() == RunOutcome.FLAKY_FAILURE) {
            stable = StableSideEvidence.OTHER_OR_INVALID;
        }
        return new SideExecutionResult(attempts, stable, aggregated);
    }

    private Optional<RunOutcome> aggregateOutcomes(List<AttemptRecord> attempts) {
        List<RunOutcome> outcomes = new ArrayList<>(attempts.size());
        for (AttemptRecord attempt : attempts) {
            if (attempt.outcome().isEmpty()) {
                return Optional.empty();
            }
            outcomes.add(attempt.outcome().orElseThrow());
        }
        return Optional.of(classifier.classifyAttempts(outcomes));
    }

    private AttemptRecord runOnce(Path workspace, MavenTestCommand command, TargetTest targetTest) {
        try {
            WorkspaceTrust.requireUnderAllowedRoot(workspace, allowedWorkspaceRoot);
        } catch (IllegalArgumentException ex) {
            return AttemptRecord.preExecutionFailure("workspace trust failed: " + safeDetail(ex));
        }

        SurefireReportCleanup cleanup = cleaner.clean(workspace, command.modulePath());
        if (cleanup instanceof SurefireReportCleanup.Failed failed) {
            return AttemptRecord.preExecutionFailure("surefire cleanup failed: " + failed.reason());
        }

        SandboxExecution execution = sandboxRunner.execute(workspace, command);

        final Path reportsDir;
        try {
            reportsDir = SurefireReportsLocation.resolve(workspace, command.modulePath());
        } catch (IllegalArgumentException ex) {
            return AttemptRecord.reportFailure(
                    execution, "surefire reports path rejected: " + safeDetail(ex));
        }

        final TestReport report;
        try {
            report = parser.parse(reportsDir);
        } catch (SurefireReportParseException | IllegalArgumentException ex) {
            return AttemptRecord.reportFailure(
                    execution, "surefire report unreadable: " + safeDetail(ex));
        }

        return AttemptRecord.executed(execution, report, targetTest);
    }

    private static String safeDetail(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }
}

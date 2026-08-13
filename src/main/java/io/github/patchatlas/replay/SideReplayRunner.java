package io.github.patchatlas.replay;

import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.observability.SandboxObservations;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    private final SandboxExecutionObserver observer;

    public SideReplayRunner(SandboxRunner sandboxRunner, Path allowedWorkspaceRoot) {
        this(sandboxRunner, allowedWorkspaceRoot, SandboxExecutionObserver.NOOP);
    }

    public SideReplayRunner(
            SandboxRunner sandboxRunner, Path allowedWorkspaceRoot, SandboxExecutionObserver observer) {
        this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner");
        this.allowedWorkspaceRoot = WorkspaceTrust.normalizeAllowedRoot(allowedWorkspaceRoot);
        this.cleaner = new SurefireReportCleaner();
        this.parser = new SurefireReportParser();
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public SideExecutionResult runSide(Path workspace, MavenTestCommand command, TargetTest targetTest) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(targetTest, "targetTest");

        List<AttemptRecord> attempts = new ArrayList<>(SideEvidenceStabilizer.REQUIRED_ATTEMPTS);
        for (int i = 0; i < SideEvidenceStabilizer.REQUIRED_ATTEMPTS; i++) {
            attempts.add(runOnce(workspace, command, targetTest));
        }

        return new SideExecutionResult(attempts);
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
        SandboxObservations.recordSafely(observer, command, execution);

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

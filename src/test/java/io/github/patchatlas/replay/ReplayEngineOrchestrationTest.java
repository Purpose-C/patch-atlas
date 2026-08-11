package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenSandboxCommand;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayEngineOrchestrationTest {

    @TempDir
    Path tempDir;

    private Path buggy;
    private Path fixed;
    private Path live;
    private FakeSandboxRunner fake;
    private final TargetTest target = new TargetTest("fixtures.StringUtilsTest", "lastChar");
    private final MavenTestCommand command =
            new MavenTestCommand("", "fixtures.StringUtilsTest#lastChar", MavenNetworkMode.OFFLINE);

    @BeforeEach
    void setUp() throws Exception {
        buggy = tempDir.resolve("buggy");
        fixed = tempDir.resolve("fixed");
        live = tempDir.resolve("live");
        Files.createDirectories(buggy);
        Files.createDirectories(fixed);
        Files.createDirectories(live);
        fake = new FakeSandboxRunner();
    }

    @Test
    void liveDoubleRunProducesReproductionCandidate() {
        enqueueFailure(fake);
        enqueueFailure(fake);

        ReplayResult result = new LiveReplayEngine(fake, tempDir)
                .verify(new LiveReplayRequest(live, command, target));

        assertThat(result.mode()).isEqualTo(VerificationMode.LIVE);
        assertThat(result.verdict()).isEqualTo(ReplayVerdict.REPRODUCTION_CANDIDATE);
        assertThat(result.primarySide().attempts()).hasSize(2);
        assertThat(result.fixedSide()).isEmpty();
        assertThat(fake.remaining()).isZero();
        assertThat(fake.executedWorkspaces()).containsExactly(live, live);
    }

    @Test
    void liveDoubleRunNotReproducedWhenTargetPasses() {
        enqueuePass(fake);
        enqueuePass(fake);

        ReplayResult result = new LiveReplayEngine(fake, tempDir)
                .verify(new LiveReplayRequest(live, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.NOT_REPRODUCED);
        assertThat(result.primarySide().stableEvidence())
                .isEqualTo(StableSideEvidence.TARGET_PASSED);
    }

    @Test
    void historicalShortCircuitsWithoutCallingFixedWhenBuggyPasses() {
        enqueuePass(fake);
        enqueuePass(fake);

        ReplayResult result = new HistoricalReplayEngine(fake, tempDir)
                .verify(new HistoricalReplayRequest(buggy, fixed, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.NOT_REPRODUCED);
        assertThat(result.fixedSide()).isEmpty();
        assertThat(result.fixedNotExecutedReason()).isPresent();
        assertThat(fake.executedWorkspaces()).containsExactly(buggy, buggy);
        assertThat(fake.remaining()).isZero();
    }

    @Test
    void historicalValidReproductionRunsFixedOnlyAfterBuggyAssertionFailure() {
        enqueueFailure(fake);
        enqueueFailure(fake);
        enqueuePass(fake);
        enqueuePass(fake);

        ReplayResult result = new HistoricalReplayEngine(fake, tempDir)
                .verify(new HistoricalReplayRequest(buggy, fixed, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(result.fixedSide()).isPresent();
        assertThat(result.fixedSide().orElseThrow().attempts()).hasSize(2);
        assertThat(result.fixedNotExecutedReason()).isEmpty();
        assertThat(fake.executedWorkspaces()).containsExactly(buggy, buggy, fixed, fixed);
    }

    @Test
    void historicalFailedOnBothCommits() {
        enqueueFailure(fake);
        enqueueFailure(fake);
        enqueueFailure(fake);
        enqueueFailure(fake);

        ReplayResult result = new HistoricalReplayEngine(fake, tempDir)
                .verify(new HistoricalReplayRequest(buggy, fixed, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.FAILED_ON_BOTH_COMMITS);
        assertThat(result.fixedSide()).isPresent();
    }

    @Test
    void flakyBuggySideIsInconclusiveAndSkipsFixed() {
        enqueueFailure(fake);
        enqueuePass(fake);

        ReplayResult result = new HistoricalReplayEngine(fake, tempDir)
                .verify(new HistoricalReplayRequest(buggy, fixed, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.INCONCLUSIVE);
        assertThat(result.primarySide().stableEvidence())
                .isEqualTo(StableSideEvidence.OTHER_OR_INVALID);
        assertThat(result.primarySide().aggregatedOutcome())
                .contains(RunOutcome.FLAKY_FAILURE);
        assertThat(result.fixedSide()).isEmpty();
        assertThat(fake.executedWorkspaces()).containsExactly(buggy, buggy);
    }

    @Test
    void retainsAttemptRecordsWithExecutionAndOutcome() {
        enqueueFailure(fake);
        enqueueFailure(fake);

        ReplayResult result = new LiveReplayEngine(fake, tempDir)
                .verify(new LiveReplayRequest(live, command, target));

        AttemptRecord first = result.primarySide().attempts().getFirst();
        assertThat(first.phase()).isEqualTo(AttemptPhase.EXECUTED);
        assertThat(first.outcome()).contains(RunOutcome.ASSERTION_FAILURE);
        assertThat(first.targetEvidence()).isEqualTo(SingleAttemptEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(first.report().testCases()).isNotEmpty();
        assertThat(first.execution().orElseThrow().status())
                .isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(result.primarySide().aggregatedOutcome()).contains(RunOutcome.ASSERTION_FAILURE);
    }

    @Test
    void reportPathTurnedIntoSymlinkAfterExecutionIsReportFailureNotCrash() {
        // 第一次成功；第二次执行后把报告目录换成 symlink，parser 应变成 REPORT_FAILURE
        enqueueFailure(fake);
        fake.enqueue(completed(1, "Failures: 1"), null);
        SandboxRunner mutating = new SandboxRunner() {
            int calls;

            @Override
            public SandboxExecution execute(Path workspace, MavenSandboxCommand cmd) {
                SandboxExecution execution = fake.execute(workspace, cmd);
                calls++;
                if (calls == 2) {
                    try {
                        Path reports = SurefireReportsLocation.resolve(workspace, "");
                        // 清空后变成指向外部的 symlink，触发 parser IllegalArgumentException
                        if (Files.exists(reports)) {
                            // reports may not exist when xml was null
                        }
                        Path parent = workspace.resolve("target");
                        Files.createDirectories(parent);
                        Path outside = tempDir.resolve("hijacked-reports-" + System.nanoTime());
                        Files.createDirectory(outside);
                        Path link = parent.resolve("surefire-reports");
                        if (Files.exists(link)) {
                            // delete if plain dir empty
                            try (var s = Files.list(link)) {
                                s.forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (Exception ignored) {
                                    }
                                });
                            }
                            Files.deleteIfExists(link);
                        }
                        Files.createSymbolicLink(link, outside);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }
                return execution;
            }
        };

        ReplayResult result = new LiveReplayEngine(mutating, tempDir)
                .verify(new LiveReplayRequest(live, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.INCONCLUSIVE);
        AttemptRecord second = result.primarySide().attempts().get(1);
        assertThat(second.phase()).isEqualTo(AttemptPhase.REPORT_FAILURE);
        assertThat(second.execution()).isPresent();
        assertThat(second.diagnostic()).isPresent();
        assertThat(second.diagnostic().orElseThrow()).doesNotContain(tempDir.toString());
    }

    @Test
    void cleanupFailureIsPreExecutionWithoutForgedSandboxExecution() throws Exception {
        Path reportsParent = live.resolve("target");
        Files.createDirectory(reportsParent);
        Path outside = tempDir.resolve("outside-reports");
        Files.createDirectory(outside);
        Files.createSymbolicLink(reportsParent.resolve("surefire-reports"), outside);

        // 即使 fake 有队列，清理失败时也不应调用 sandbox
        enqueueFailure(fake);
        enqueueFailure(fake);

        ReplayResult result = new LiveReplayEngine(fake, tempDir)
                .verify(new LiveReplayRequest(live, command, target));

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.INCONCLUSIVE);
        AttemptRecord first = result.primarySide().attempts().getFirst();
        assertThat(first.phase()).isEqualTo(AttemptPhase.PRE_EXECUTION_FAILURE);
        assertThat(first.execution()).isEmpty();
        assertThat(first.outcome()).isEmpty();
        assertThat(first.diagnostic()).isPresent();
        assertThat(result.primarySide().aggregatedOutcome()).isEmpty();
        assertThat(fake.executedWorkspaces()).isEmpty();
        assertThat(fake.remaining()).isEqualTo(2);
    }

    @Test
    void rejectsWorkspaceOutsideAllowedRootBeforeCleanup() throws Exception {
        Path outsideRoot = Files.createTempDirectory("outside-allowed-");
        Path outsideWs = outsideRoot.resolve("ws");
        Files.createDirectories(outsideWs.resolve("target/surefire-reports"));
        Files.writeString(outsideWs.resolve("target/surefire-reports/TEST-Keep.xml"), "<testsuite/>");

        enqueueFailure(fake);
        enqueueFailure(fake);

        try {
            ReplayResult result = new LiveReplayEngine(fake, tempDir)
                    .verify(new LiveReplayRequest(outsideWs, command, target));

            assertThat(result.verdict()).isEqualTo(ReplayVerdict.INCONCLUSIVE);
            assertThat(result.primarySide().attempts().getFirst().phase())
                    .isEqualTo(AttemptPhase.PRE_EXECUTION_FAILURE);
            assertThat(result.primarySide().attempts().getFirst().diagnostic().orElseThrow())
                    .contains("workspace trust failed")
                    .doesNotContain(outsideWs.toString());
            // 清理不得发生在 allowed root 之外
            assertThat(Files.exists(outsideWs.resolve("target/surefire-reports/TEST-Keep.xml"))).isTrue();
            assertThat(fake.executedWorkspaces()).isEmpty();
        } finally {
            // best-effort cleanup of temp outside root
            try (var walk = Files.walk(outsideRoot)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void historicalRequestRejectsSameRealDirectory() throws Exception {
        Path alias = tempDir.resolve("buggy-alias");
        Files.createSymbolicLink(alias, buggy);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HistoricalReplayRequest(buggy, alias, command, target));
    }

    private static void enqueuePass(FakeSandboxRunner fake) {
        fake.enqueue(
                completed(0, "BUILD SUCCESS"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="fixtures.StringUtilsTest" tests="1" errors="0" skipped="0" failures="0">
                  <testcase name="lastChar" classname="fixtures.StringUtilsTest" time="0.01"/>
                </testsuite>
                """);
    }

    private static void enqueueFailure(FakeSandboxRunner fake) {
        fake.enqueue(
                completed(1, "Failures: 1"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="fixtures.StringUtilsTest" tests="1" errors="0" skipped="0" failures="1">
                  <testcase name="lastChar" classname="fixtures.StringUtilsTest" time="0.01">
                    <failure message="expected: &lt;c&gt; but was: &lt;b&gt;" type="org.opentest4j.AssertionFailedError"/>
                  </testcase>
                </testsuite>
                """);
    }

    private static SandboxExecution completed(int exitCode, String log) {
        return new SandboxExecution(
                SandboxExecutionStatus.COMPLETED,
                exitCode,
                Duration.ofMillis(50),
                false,
                List.of("mvn", "-B", "test"),
                log,
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }
}

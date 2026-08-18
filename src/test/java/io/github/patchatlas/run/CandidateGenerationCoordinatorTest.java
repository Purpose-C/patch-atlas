package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.CallFailureCategory;
import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationFeedbackCategory;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.HistoricalReplayEngine;
import io.github.patchatlas.replay.HistoricalReplayRequest;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.github.patchatlas.sandbox.SandboxRunner;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 1/2/3 轮自控 fixture + 耗尽 + STRUCTURED_OUTPUT 反馈契约。
 *
 * <p>默认离线：内存 session + Fake 模型 + 真实 Patch Gate + 脚本化 SandboxRunner。
 */
class CandidateGenerationCoordinatorTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);

    private static final String BAD_OTHER_FILE_PATCH =
            """
            diff --git a/src/test/java/fixtures/Other.java b/src/test/java/fixtures/Other.java
            new file mode 100644
            --- /dev/null
            +++ b/src/test/java/fixtures/Other.java
            @@ -0,0 +1,3 @@
            +package fixtures;
            +class Other {}
            """;

    private static final String WRONG_OLD_AND_NEW_COUNT_PATCH =
            """
            diff --git a/src/test/java/fixtures/OldTest.java b/src/test/java/fixtures/OldTest.java
            --- a/src/test/java/fixtures/OldTest.java
            +++ b/src/test/java/fixtures/OldTest.java
            @@ -6,99 +6,99 @@
               @Test
               void already() {}
            +
            +  @Test
            +  void added() {}
            """;

    private static final String HUNK_COUNT_MISMATCH_PATCH =
            """
            diff --git a/src/test/java/fixtures/OldTest.java b/src/test/java/fixtures/OldTest.java
            --- a/src/test/java/fixtures/OldTest.java
            +++ b/src/test/java/fixtures/OldTest.java
            @@ -6,2 +6,99 @@
               @Test
               void already() {}
            +
            +  @Test
            +  void added() {}
            """;

    @TempDir
    Path temp;

    private Path workspaceRoot;
    private LocalGitFixture.Fixture liveFixture;
    private LocalGitFixture.Fixture historicalFixture;
    private GenerationInput input;
    private final List<Path> materializeLog = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        workspaceRoot = Files.createDirectories(temp.resolve("ws"));
        liveFixture = LocalGitFixture.initWithExistingTest(temp.resolve("git-live"));
        historicalFixture = LocalGitFixture.initHistoricalWithExistingTest(temp.resolve("git-hist"));
        input = generationInput(liveFixture.buggySha());
        materializeLog.clear();
    }

    @Test
    void truncatedLengthRejectsCountMismatchBeforeParse() throws Exception {
        FakeTestGenerator generator = truncatedDrafts(HUNK_COUNT_MISMATCH_PATCH);
        CandidateGenerationCoordinator coordinator = coordinator(generator, unusedSandbox());
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RunEvents.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = coordinator.run(input, session);
            assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
            assertThat(materializeLog).isEmpty();
            List<ch.qos.logback.classic.spi.ILoggingEvent> rejected = rejectedEvents(appender);
            assertThat(rejected).isNotEmpty();
            assertThat(kv(rejected.getFirst()))
                    .containsEntry("feedback_summary", "响应被截断")
                    .doesNotContainEntry("feedback_summary", "hunk new count mismatch");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void truncatedLengthRejectsSelfConsistentPatchBeforeParse() throws Exception {
        FakeTestGenerator generator = truncatedDrafts(LocalGitFixture.MODIFY_EXISTING_PATCH);
        CandidateGenerationCoordinator coordinator = coordinator(generator, unusedSandbox());
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        assertThat(materializeLog).isEmpty();
        assertThat(session.generationAttemptCount()).isEqualTo(3);
    }

    @Test
    void stopFinishReasonAcceptsHeaderCountMismatchAndAppliesBody() throws Exception {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(WRONG_OLD_AND_NEW_COUNT_PATCH, TARGET),
                Optional.empty(),
                Optional.of(CompletionDiagnostics.of("stop", "0", "10"))));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure());
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        assertThat(session.generationAttemptCount()).isEqualTo(1);
        assertThat(((CandidateGenerationCoordinator.Result.CandidateCommitted) result)
                        .claim()
                        .candidate()
                        .orElseThrow()
                        .patchText())
                .isEqualTo(WRONG_OLD_AND_NEW_COUNT_PATCH);
    }

    @Test
    void unknownFinishReasonStillRejectsHeaderCountMismatch() throws Exception {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(WRONG_OLD_AND_NEW_COUNT_PATCH, TARGET),
                Optional.empty(),
                Optional.of(CompletionDiagnostics.unknown())));
        CandidateGenerationCoordinator coordinator = coordinator(generator, unusedSandbox());
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RunEvents.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = coordinator.run(input, session);
            assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
            List<ch.qos.logback.classic.spi.ILoggingEvent> rejected = rejectedEvents(appender);
            assertThat(kv(rejected.getFirst())).containsEntry("feedback_summary", "hunk new count mismatch");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void oneRoundHistoricalFailToPass() throws Exception {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        // 预验证 2 次 fail + 正式 Buggy 2 次 fail + Fixed 2 次 pass
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.targetPassed());
        InMemoryGenerationRunSession session = newSession(VerificationMode.HISTORICAL);
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        ClaimedRun claim = ((CandidateGenerationCoordinator.Result.CandidateCommitted) result).claim();
        assertThat(claim.state()).isEqualTo(RunState.REPLAYING);
        assertThat(claim.candidate()).isPresent();
        assertThat(session.generationAttemptCount()).isEqualTo(1);
        assertThat(generator.callCount()).isEqualTo(1);

        GenerationRequest req = generator.capturedRequests().getFirst();
        assertThat(req.attemptOrdinal()).isEqualTo(1);
        assertThat(req.hasFeedback()).isFalse();
        assertThat(req.previousDraft()).isEmpty();
        assertNoFixedInRequest(req, historicalFixture.fixedSha());
        assertThat(materializeLog).hasSize(1);

        ReplayResult formal = formalHistoricalReplay(claim.candidate().orElseThrow(), sandbox);
        assertThat(formal.verdict()).isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(claim.candidate().orElseThrow().patchText())
                .isEqualTo(LocalGitFixture.MODIFY_EXISTING_PATCH);
    }

    @Test
    void twoRoundSuccessAfterGateRejection() throws Exception {
        FakeTestGenerator generator = FakeTestGenerator.of(
                new GenerationResult.GeneratedDraft(new CandidateDraft(BAD_OTHER_FILE_PATCH, TARGET)),
                new GenerationResult.GeneratedDraft(
                        new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.targetPassed());
        InMemoryGenerationRunSession session = newSession(VerificationMode.HISTORICAL);
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        assertThat(session.generationAttemptCount()).isEqualTo(2);
        assertThat(generator.callCount()).isEqualTo(2);

        List<GenerationRequest> reqs = generator.capturedRequests();
        assertThat(reqs.get(0).attemptOrdinal()).isEqualTo(1);
        assertThat(reqs.get(0).hasFeedback()).isFalse();
        assertThat(reqs.get(1).attemptOrdinal()).isEqualTo(2);
        assertThat(reqs.get(1).isCorrection()).isTrue();
        assertThat(reqs.get(1).generationFeedback().orElseThrow().category())
                .isEqualTo(GenerationFeedbackCategory.PATCH_POLICY_REJECTED);
        assertNoFixedInRequest(reqs.get(0), historicalFixture.fixedSha());
        assertNoFixedInRequest(reqs.get(1), historicalFixture.fixedSha());
        assertThat(materializeLog).hasSize(2);
        assertThat(materializeLog.get(0)).isNotEqualTo(materializeLog.get(1));

        ClaimedRun claim = ((CandidateGenerationCoordinator.Result.CandidateCommitted) result).claim();
        assertThat(claim.candidate()).isPresent();
        ReplayResult formal = formalHistoricalReplay(claim.candidate().orElseThrow(), sandbox);
        assertThat(formal.verdict()).isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
    }

    @Test
    void unsafePathRejectionEntersCorrectionLoop() throws Exception {
        String mainPatch =
                """
                diff --git a/src/main/java/fixtures/Evil.java b/src/main/java/fixtures/Evil.java
                new file mode 100644
                --- /dev/null
                +++ b/src/main/java/fixtures/Evil.java
                @@ -0,0 +1,2 @@
                +package fixtures;
                +class Evil {}
                """;
        FakeTestGenerator generator = FakeTestGenerator.of(
                new GenerationResult.GeneratedDraft(new CandidateDraft(mainPatch, TARGET)),
                new GenerationResult.GeneratedDraft(new CandidateDraft(mainPatch, TARGET)),
                new GenerationResult.GeneratedDraft(new CandidateDraft(mainPatch, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        var failed = (CandidateGenerationCoordinator.Result.RunFailed) result;
        assertThat(failed.details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.GENERATION_EXHAUSTED);
        assertThat(session.generationAttemptCount()).isEqualTo(3);
        assertThat(generator.callCount()).isEqualTo(3);
        // 每轮反馈都是 PATCH_POLICY_REJECTED（unsafe path 可修正）
        List<GenerationRequest> reqs = generator.capturedRequests();
        assertThat(reqs.get(1).generationFeedback().orElseThrow().category())
                .isEqualTo(GenerationFeedbackCategory.PATCH_POLICY_REJECTED);
        assertThat(reqs.get(2).generationFeedback().orElseThrow().category())
                .isEqualTo(GenerationFeedbackCategory.PATCH_POLICY_REJECTED);
    }

    @Test
    void gateRejectionLogsAttemptRejectedWithoutLeakingSentinels() throws Exception {
        String sentinelPatch =
                """
                diff --git a/src/main/java/fixtures/Evil.java b/src/main/java/fixtures/Evil.java
                new file mode 100644
                --- /dev/null
                +++ b/src/main/java/fixtures/Evil.java
                @@ -0,0 +1,2 @@
                +package fixtures;
                +class Evil { /* SENTINEL-PATCH-CONTENT */ }
                """;
        GenerationInput sentinelInput = new GenerationInput(
                input.generatorContext(), "title", "SENTINEL-ISSUE-BODY", List.of());
        FakeTestGenerator generator = FakeTestGenerator.of(
                new GenerationResult.GeneratedDraft(new CandidateDraft(sentinelPatch, TARGET)),
                new GenerationResult.GeneratedDraft(new CandidateDraft(sentinelPatch, TARGET)),
                new GenerationResult.GeneratedDraft(new CandidateDraft(sentinelPatch, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RunEvents.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = coordinator.run(sentinelInput, session);
            assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);

            List<ch.qos.logback.classic.spi.ILoggingEvent> rejected = appender.list.stream()
                    .filter(event -> event.getKeyValuePairs() != null
                            && event.getKeyValuePairs().stream()
                                    .anyMatch(pair -> "event".equals(pair.key)
                                            && "generation.attempt.rejected".equals(String.valueOf(pair.value))))
                    .toList();
            assertThat(rejected).hasSize(3);
            assertThat(kv(rejected.get(0))).containsEntry("attempt_ordinal", "1");
            assertThat(kv(rejected.get(1))).containsEntry("attempt_ordinal", "2");
            assertThat(kv(rejected.get(2))).containsEntry("attempt_ordinal", "3");
            for (var event : rejected) {
                assertThat(kv(event)).containsEntry("feedback_category", "PATCH_POLICY_REJECTED");
                assertThat(kv(event)).containsEntry("feedback_summary", "path outside test sources");
                assertThat(event.getFormattedMessage() + kv(event) + event.getMDCPropertyMap())
                        .doesNotContain("SENTINEL-PATCH-CONTENT")
                        .doesNotContain("SENTINEL-ISSUE-BODY");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static java.util.Map<String, String> kv(ch.qos.logback.classic.spi.ILoggingEvent event) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (org.slf4j.event.KeyValuePair pair : event.getKeyValuePairs()) {
                map.put(pair.key, String.valueOf(pair.value));
            }
        }
        return map;
    }

    @Test
    void threeRoundSuccessAfterTargetPassedThenCompileFailure() throws Exception {
        CandidateDraft good = new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET);
        FakeTestGenerator generator = FakeTestGenerator.of(
                new GenerationResult.GeneratedDraft(good),
                new GenerationResult.GeneratedDraft(good),
                new GenerationResult.GeneratedDraft(good));
        // 轮1 预验证: 2x pass；轮2: 2x compile；轮3: 2x assert fail；正式: 2 buggy fail + 2 fixed pass
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.compileFailure(),
                ScriptedSandboxRunner.compileFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.targetPassed());
        InMemoryGenerationRunSession session = newSession(VerificationMode.HISTORICAL);
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        assertThat(session.generationAttemptCount()).isEqualTo(3);
        assertThat(generator.callCount()).isEqualTo(3);

        List<GenerationRequest> reqs = generator.capturedRequests();
        assertThat(reqs).hasSize(3);
        assertThat(reqs.get(0).attemptOrdinal()).isEqualTo(1);
        assertThat(reqs.get(0).hasFeedback()).isFalse();
        assertThat(reqs.get(1).attemptOrdinal()).isEqualTo(2);
        assertThat(reqs.get(1).generationFeedback().orElseThrow().category())
                .isEqualTo(GenerationFeedbackCategory.TARGET_TEST_PASSED);
        assertThat(reqs.get(2).attemptOrdinal()).isEqualTo(3);
        assertThat(reqs.get(2).generationFeedback().orElseThrow().category())
                .isEqualTo(GenerationFeedbackCategory.COMPILATION_FAILED);
        for (GenerationRequest r : reqs) {
            assertNoFixedInRequest(r, historicalFixture.fixedSha());
        }
        assertThat(materializeLog).hasSize(3);
        assertThat(materializeLog.stream().distinct().count()).isEqualTo(3);

        ClaimedRun claim = ((CandidateGenerationCoordinator.Result.CandidateCommitted) result).claim();
        ReplayResult formal = formalHistoricalReplay(claim.candidate().orElseThrow(), sandbox);
        assertThat(formal.verdict()).isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
    }

    @Test
    void threeCorrectableFailuresExhaust() {
        FakeTestGenerator generator = FakeTestGenerator.of(
                new GenerationResult.GeneratedDraft(new CandidateDraft(BAD_OTHER_FILE_PATCH, TARGET)),
                new GenerationResult.GeneratedDraft(new CandidateDraft(BAD_OTHER_FILE_PATCH, TARGET)),
                new GenerationResult.GeneratedDraft(new CandidateDraft(BAD_OTHER_FILE_PATCH, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        var failed = (CandidateGenerationCoordinator.Result.RunFailed) result;
        assertThat(failed.details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.GENERATION_EXHAUSTED);
        assertThat(session.generationAttemptCount()).isEqualTo(3);
        assertThat(failed.details().candidate()).isEmpty();
    }

    @Test
    void structuredOutputInvalidCarriesFeedbackOnlyOnRetry() {
        FakeTestGenerator generator = FakeTestGenerator.of(
                new GenerationResult.GenerationCallFailure(
                        CallFailureCategory.STRUCTURED_OUTPUT_INVALID, "not json"),
                new GenerationResult.GeneratedDraft(
                        new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        assertThat(generator.callCount()).isEqualTo(2);
        GenerationRequest retry = generator.capturedRequests().get(1);
        assertThat(retry.hasFeedback()).isTrue();
        assertThat(retry.previousDraft()).isEmpty();
        assertThat(retry.isCorrection()).isFalse();
        assertThat(retry.generationFeedback().orElseThrow().category())
                .isEqualTo(GenerationFeedbackCategory.STRUCTURED_OUTPUT_INVALID);
    }

    @Test
    void authenticationErrorIsTerminalWithoutRetry() {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GenerationCallFailure(
                CallFailureCategory.MODEL_AUTHENTICATION_ERROR, "auth"));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        var failed = (CandidateGenerationCoordinator.Result.RunFailed) result;
        assertThat(failed.details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.MODEL_AUTHENTICATION_ERROR);
        assertThat(generator.callCount()).isEqualTo(1);
        assertThat(session.generationAttemptCount()).isEqualTo(1);
    }

    @Test
    void dependencyWarmupFailureIsTerminalWithoutRetryingModel() {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(new SandboxExecution(
                SandboxExecutionStatus.TIMED_OUT,
                null,
                Duration.ofSeconds(1),
                true,
                List.of("mvn", "test"),
                "warmup timed out",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.ONLINE));
        AtomicInteger counter = new AtomicInteger();
        var factory = new TempCandidateWorkspaceFactory(workspaceRoot, (url, sha, parent, name) -> {
            Path dir = LocalGitFixture.fetcher(liveFixture.originDir())
                    .materialize(url, sha, parent, name + "-" + counter.incrementAndGet());
            materializeLog.add(dir);
            return dir;
        });
        var gate = new PatchGate(workspaceRoot);
        var side = new SideReplayRunner(sandbox, workspaceRoot);
        CandidateGenerationCoordinator coordinator =
                new CandidateGenerationCoordinator(
                        generator,
                        gate,
                        factory,
                        new DependencyWarmupRunner(sandbox, workspaceRoot),
                        side);
        InMemoryGenerationRunSession session = newSession(VerificationMode.LIVE);

        var result = coordinator.run(input, session);

        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        var failed = (CandidateGenerationCoordinator.Result.RunFailed) result;
        assertThat(failed.details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.REPLAY_SYSTEM_ERROR);
        assertThat(generator.callCount()).isEqualTo(1);
        assertThat(session.generationAttemptCount()).isEqualTo(1);
    }

    @Test
    void dependencyWarmupCannotExecuteCandidatePatch() throws Exception {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        ScriptedSandboxRunner evidence = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure());
        AtomicBoolean candidateVisibleDuringWarmup = new AtomicBoolean();
        SandboxRunner sandbox = (workspace, command) -> {
            if (command instanceof MavenDependencyWarmupCommand) {
                Path testFile = workspace.resolve("src/test/java/fixtures/OldTest.java");
                try {
                    candidateVisibleDuringWarmup.set(
                            Files.readString(testFile).contains("void added()"));
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                }
                return ScriptedSandboxRunner.completed(0);
            }
            return evidence.execute(workspace, command);
        };
        AtomicInteger counter = new AtomicInteger();
        var factory = new TempCandidateWorkspaceFactory(workspaceRoot, (url, sha, parent, name) ->
                LocalGitFixture.fetcher(liveFixture.originDir())
                        .materialize(url, sha, parent, name + "-" + counter.incrementAndGet()));
        CandidateGenerationCoordinator coordinator = new CandidateGenerationCoordinator(
                generator,
                new PatchGate(workspaceRoot),
                factory,
                new DependencyWarmupRunner(sandbox, workspaceRoot),
                new SideReplayRunner(sandbox, workspaceRoot));

        var result = coordinator.run(input, newSession(VerificationMode.LIVE));

        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        assertThat(candidateVisibleDuringWarmup).isFalse();
    }

    @Test
    void workspaceRuntimeFailureIsWorkspaceErrorAndAgentBenchmarkDoesNotEchoMessage() {
        TargetTest illegal = new TargetTest(LocalGitFixture.TARGET_CLASS, "1badMethod");
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, illegal)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        InMemoryGenerationRunSession session =
                newSession(VerificationMode.LIVE, RunPurpose.AGENT_BENCHMARK);
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        var failed = (CandidateGenerationCoordinator.Result.RunFailed) result;
        RunFailure failure = failed.details().failure().orElseThrow();
        assertThat(failure.stage()).isEqualTo(FailureStage.WORKSPACE);
        assertThat(failure.category()).isEqualTo(FailureCategory.WORKSPACE_ERROR);
        assertThat(failure.summary()).isEqualTo("workspace: IllegalArgumentException");
        assertThat(failure.summary()).doesNotContain("testSelector");
        assertThat(failure.summary()).doesNotContain("1badMethod");
        assertThat(session.generationAttemptCount()).isEqualTo(1);
    }

    @Test
    void diagnosticWorkspaceFailureEchoesExceptionMessage() {
        TargetTest illegal = new TargetTest(LocalGitFixture.TARGET_CLASS, "1badMethod");
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, illegal)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
        InMemoryGenerationRunSession session =
                newSession(VerificationMode.LIVE, RunPurpose.DIAGNOSTIC);
        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.RunFailed.class);
        var failed = (CandidateGenerationCoordinator.Result.RunFailed) result;
        assertThat(failed.details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.WORKSPACE_ERROR);
        assertThat(failed.details().failure().orElseThrow().summary())
                .contains("IllegalArgumentException")
                .contains("testSelector must be a class or class#method selector");
    }

    @Test
    void staleClaimDuringCandidateCommitEscapesWithoutRecordingWorkspaceFailure() {
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        ScriptedSandboxRunner sandbox = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure());
        InMemoryGenerationRunSession delegate = newSession(VerificationMode.LIVE);
        AtomicInteger failCalls = new AtomicInteger();
        GenerationRunSession staleOnCommit = new GenerationRunSession() {
            @Override
            public ReserveResult reserveGenerationAttempt(String provider, String modelName) {
                return delegate.reserveGenerationAttempt(provider, modelName);
            }

            @Override
            public ClaimedRun recordModelUsage(io.github.patchatlas.agent.ModelUsage usage) {
                return delegate.recordModelUsage(usage);
            }

            @Override
            public ClaimedRun commitCandidate(GatedCandidate gated) {
                throw new StaleClaimException(UUID.randomUUID(), "stale commit");
            }

            @Override
            public RunDetails fail(RunFailure failure) {
                failCalls.incrementAndGet();
                return delegate.fail(failure);
            }

            @Override
            public RunPurpose purpose() {
                return delegate.purpose();
            }
        };

        CandidateGenerationCoordinator coordinator = coordinator(generator, sandbox);

        assertThatThrownBy(() -> coordinator.run(input, staleOnCommit))
                .isInstanceOf(StaleClaimException.class)
                .hasMessageContaining("stale commit");
        assertThat(failCalls).hasValue(0);
    }

    private ReplayResult formalHistoricalReplay(
            PersistedCandidatePatch candidate, ScriptedSandboxRunner sandbox) throws Exception {
        Path formalRoot = Files.createDirectories(temp.resolve("formal-" + UUID.randomUUID()));
        Path buggy = LocalGitFixture.fetcher(historicalFixture.originDir())
                .materialize(
                        "file://" + historicalFixture.originDir(),
                        historicalFixture.buggySha(),
                        formalRoot,
                        "buggy");
        Path fixed = LocalGitFixture.fetcher(historicalFixture.originDir())
                .materialize(
                        "file://" + historicalFixture.originDir(),
                        historicalFixture.fixedSha(),
                        formalRoot,
                        "fixed");
        PatchGate gate = new PatchGate(formalRoot);
        CandidateDraft draft = new CandidateDraft(candidate.patchText(), candidate.targetTest());
        assertThat(gate.prepare(buggy, "", draft, MavenNetworkMode.OFFLINE))
                .isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(gate.prepare(fixed, "", draft, MavenNetworkMode.OFFLINE))
                .isInstanceOf(PatchPreparationResult.PreparedCandidate.class);

        HistoricalReplayEngine engine = new HistoricalReplayEngine(sandbox, formalRoot);
        MavenTestCommand command = new MavenTestCommand(
                "",
                candidate.targetTest().className() + "#" + candidate.targetTest().methodName(),
                MavenNetworkMode.OFFLINE);
        return engine.verify(new HistoricalReplayRequest(buggy, fixed, command, candidate.targetTest()));
    }

    private CandidateGenerationCoordinator coordinator(
            TestGenerator generator, ScriptedSandboxRunner sandbox) {
        AtomicInteger counter = new AtomicInteger();
        var factory = new TempCandidateWorkspaceFactory(workspaceRoot, (url, sha, parent, name) -> {
            Path dir = LocalGitFixture.fetcher(liveFixture.originDir())
                    .materialize(url, sha, parent, name + "-" + counter.incrementAndGet());
            materializeLog.add(dir);
            return dir;
        });
        var side = new SideReplayRunner(sandbox, workspaceRoot);
        return new CandidateGenerationCoordinator(
                generator,
                new PatchGate(workspaceRoot),
                factory,
                new DependencyWarmupRunner(warmupSucceeds(sandbox), workspaceRoot),
                side);
    }

    private static FakeTestGenerator truncatedDrafts(String patch) {
        GenerationResult draft = new GenerationResult.GeneratedDraft(
                new CandidateDraft(patch, TARGET),
                Optional.empty(),
                Optional.of(CompletionDiagnostics.of("length", "0", "10")));
        return FakeTestGenerator.of(draft, draft, draft);
    }

    private static ScriptedSandboxRunner unusedSandbox() {
        return ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
    }

    private static List<ch.qos.logback.classic.spi.ILoggingEvent> rejectedEvents(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getKeyValuePairs() != null
                        && event.getKeyValuePairs().stream()
                                .anyMatch(pair -> "event".equals(pair.key)
                                        && "generation.attempt.rejected".equals(String.valueOf(pair.value))))
                .toList();
    }

    private static SandboxRunner warmupSucceeds(ScriptedSandboxRunner evidenceRunner) {
        return (workspace, command) -> command instanceof MavenDependencyWarmupCommand
                ? ScriptedSandboxRunner.completed(0)
                : evidenceRunner.execute(workspace, command);
    }

    private InMemoryGenerationRunSession newSession(VerificationMode mode) {
        return newSession(mode, RunPurpose.STANDARD);
    }

    private InMemoryGenerationRunSession newSession(VerificationMode mode, RunPurpose purpose) {
        ClaimedRun claim = new ClaimedRun(
                UUID.randomUUID(),
                mode,
                RunState.GENERATING,
                1L,
                new RunLease(UUID.randomUUID(), "t", Instant.now().plusSeconds(600)),
                0,
                0,
                Optional.empty());
        return new InMemoryGenerationRunSession(claim, purpose);
    }

    private static GenerationInput generationInput(String buggySha) {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        buggySha,
                        "",
                        "21"),
                "title",
                "body",
                List.of());
    }

    private static void assertNoFixedInRequest(GenerationRequest request, String fixedSha) {
        String dump = request.toString().toLowerCase();
        assertThat(dump).doesNotContain("fixedrevision");
        assertThat(dump).doesNotContain("oracledata");
        assertThat(dump).doesNotContain("knowntriggertest");
        // 类型表面只有 GeneratorContext；捕获值不得出现 Historical Fixed SHA
        assertThat(dump).doesNotContain(fixedSha.toLowerCase());
    }
}

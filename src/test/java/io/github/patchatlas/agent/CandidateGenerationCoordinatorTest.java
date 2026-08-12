package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.HistoricalReplayEngine;
import io.github.patchatlas.replay.HistoricalReplayRequest;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.InMemoryGenerationRunSession;
import io.github.patchatlas.run.LocalGitFixture;
import io.github.patchatlas.run.PersistedCandidatePatch;
import io.github.patchatlas.run.RunLease;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TempCandidateWorkspaceFactory;
import io.github.patchatlas.run.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ��1/2/3 轮自控 fixture + 耗尽 + STRUCTURED_OUTPUT 反馈契约。
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
        assertThat(session.currentClaim().candidate()).isEmpty();
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
        var gate = new PatchGate(workspaceRoot);
        var side = new SideReplayRunner(sandbox, workspaceRoot);
        return new CandidateGenerationCoordinator(generator, gate, factory, side);
    }

    private InMemoryGenerationRunSession newSession(VerificationMode mode) {
        ClaimedRun claim = new ClaimedRun(
                UUID.randomUUID(),
                mode,
                RunState.GENERATING,
                1L,
                new RunLease(UUID.randomUUID(), "t", Instant.now().plusSeconds(600)),
                0,
                0,
                Optional.empty());
        return new InMemoryGenerationRunSession(claim);
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

package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPreparationResult;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.benchmark.BenchmarkArtifacts;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.CaseResult;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.HistoricalReplayEngine;
import io.github.patchatlas.replay.HistoricalReplayRequest;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.StableSideEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxRunner;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 离线端到端：本地 origin + Fake 模型 + 脚本沙箱，走通草稿 → Gate → 预热 → 预验证 →
 * 正式 Replay 双跑 → complete，以及证据导出。默认套件，无 Docker 无网络。
 */
class Issue2TestOfflineEndToEndTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @TempDir
    Path temp;

    private Path workspaceRoot;
    private LocalGitFixture.Fixture historical;
    private GenerationInput input;

    @BeforeEach
    void setUp() throws Exception {
        workspaceRoot = Files.createDirectories(temp.resolve("ws"));
        historical = LocalGitFixture.initHistoricalWithExistingTest(temp.resolve("git-hist"));
        input = new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        historical.buggySha(),
                        "",
                        "21"),
                "title",
                "body",
                List.of());
    }

    @Test
    void validReproductionCompletesWithFourReplayAttempts() throws Exception {
        ScriptedSandboxRunner evidence = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.targetPassed());
        Pipeline pipeline = runPipeline(evidence);

        assertThat(pipeline.completed().state()).isEqualTo(RunState.COMPLETED);
        assertThat(pipeline.completed().verdict()).contains(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(pipeline.claim().candidate().orElseThrow().provenance())
                .isEqualTo(TestPatchProvenance.AGENT_GENERATED);
        assertThat(replayAttemptCount(pipeline.formal())).isEqualTo(4);
        assertThat(pipeline.formal().fixedSide()).isPresent();
        assertThat(pipeline.warmupCalls()).isGreaterThanOrEqualTo(3);
        assertThat(evidence.callCount()).isEqualTo(6);
    }

    @Test
    void buggyStablePassShortCircuitsFixedAsNotReproduced() throws Exception {
        ScriptedSandboxRunner evidence = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetPassed(),
                ScriptedSandboxRunner.targetPassed());
        Pipeline pipeline = runPipeline(evidence);

        assertThat(pipeline.completed().state()).isEqualTo(RunState.COMPLETED);
        assertThat(pipeline.completed().verdict()).contains(ReplayVerdict.NOT_REPRODUCED);
        assertThat(pipeline.formal().fixedSide()).isEmpty();
        assertThat(pipeline.formal().fixedNotExecutedReason()).isPresent();
        assertThat(pipeline.formal().primarySide().stableEvidence())
                .isEqualTo(StableSideEvidence.TARGET_PASSED);
        assertThat(replayAttemptCount(pipeline.formal())).isEqualTo(2);
    }

    @Test
    void buggyInconsistentAttemptsReduceToInconclusive() throws Exception {
        ScriptedSandboxRunner evidence = ScriptedSandboxRunner.of(
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetAssertionFailure(),
                ScriptedSandboxRunner.targetPassed());
        Pipeline pipeline = runPipeline(evidence);

        assertThat(pipeline.completed().state()).isEqualTo(RunState.COMPLETED);
        assertThat(pipeline.completed().verdict()).contains(ReplayVerdict.INCONCLUSIVE);
        assertThat(pipeline.formal().primarySide().stableEvidence())
                .isEqualTo(StableSideEvidence.OTHER_OR_INVALID);
        assertThat(pipeline.formal().fixedSide()).isEmpty();
    }

    @Test
    void exportsEvidenceReportWithModelIdentityAndLimits() throws Exception {
        Path out = Files.createDirectories(temp.resolve("evidence"));
        Path protocol = out.resolve("protocol.json");
        Files.writeString(
                protocol,
                """
                {
                  "provider": "agnes",
                  "model": "agnes-2.5-flash",
                  "endpoint": "https://apihub.agnes-ai.com/v1",
                  "limitations": [
                    "该 model 标识无日期版本锚点，供应商可在不改名的前提下更换权重，因此本批次结果不保证长期可复现。",
                    "该模型的训练数据构成与知识截止时间未公开，无法论证其对 GitBug-Java 案例的污染边界。"
                  ],
                  "failureHandling": "Patch Gate 的策略性拒绝可修正并进入下一次 Generation Attempt（三轮上限不变）；仅 WORKSPACE_UNSAFE 立即终态。该规则在任何正式模型调用之前确定。"
                }
                """);
        List<CaseResult> results = List.of(
                calibration(1, "case-1"),
                calibration(2, "case-2"),
                calibration(3, "case-3"),
                agent(4, "case-4", ReplayVerdict.VALID_REPRODUCTION, false),
                agent(5, "case-5", ReplayVerdict.NOT_REPRODUCED, false),
                agent(6, "case-6", null, true));

        new BenchmarkEvidenceExporter().export(sampleCohort(), results, protocol, out);

        String json = Files.readString(out.resolve("results.json"));
        String md = Files.readString(out.resolve("evidence-report.md"));
        assertThat(json)
                .contains("agnes-2.5-flash")
                .contains("无日期版本锚点")
                .contains("训练数据构成")
                .contains("failureHandling");
        assertThat(md)
                .contains("Model provider: agnes")
                .contains("Model: agnes-2.5-flash")
                .contains("Protocol Limitations")
                .contains("Failure Handling")
                .contains("无日期版本锚点")
                .contains("训练数据构成");
        assertThat(Files.isRegularFile(out.resolve("results.json"))).isTrue();
        assertThat(Files.isRegularFile(out.resolve("evidence-report.md"))).isTrue();
    }

    private Pipeline runPipeline(ScriptedSandboxRunner evidence) throws Exception {
        AtomicInteger warmupCalls = new AtomicInteger();
        SandboxRunner routed = (workspace, command) -> {
            if (command instanceof MavenDependencyWarmupCommand) {
                warmupCalls.incrementAndGet();
                return ScriptedSandboxRunner.completed(0);
            }
            return evidence.execute(workspace, command);
        };
        FakeTestGenerator generator = FakeTestGenerator.of(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        InMemoryGenerationRunSession session = newSession();
        CandidateGenerationCoordinator coordinator = coordinator(generator, routed);

        var result = coordinator.run(input, session);
        assertThat(result).isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);
        ClaimedRun claim = ((CandidateGenerationCoordinator.Result.CandidateCommitted) result).claim();
        assertThat(claim.state()).isEqualTo(RunState.REPLAYING);
        assertThat(claim.candidate()).isPresent();

        ReplayResult formal = formalReplay(claim.candidate().orElseThrow(), routed);
        RunDetails completed = session.complete(formal);
        return new Pipeline(claim, formal, completed, warmupCalls.get());
    }

    private ReplayResult formalReplay(PersistedCandidatePatch candidate, SandboxRunner sandbox)
            throws Exception {
        Path formalRoot = Files.createDirectories(temp.resolve("formal-" + UUID.randomUUID()));
        Path buggy = LocalGitFixture.fetcher(historical.originDir())
                .materialize(
                        "file://" + historical.originDir(),
                        historical.buggySha(),
                        formalRoot,
                        "buggy");
        Path fixed = LocalGitFixture.fetcher(historical.originDir())
                .materialize(
                        "file://" + historical.originDir(),
                        historical.fixedSha(),
                        formalRoot,
                        "fixed");
        CandidateDraft draft = new CandidateDraft(candidate.patchText(), candidate.targetTest());
        MavenTestCommand command = new MavenTestCommand(
                "",
                candidate.targetTest().className() + "#" + candidate.targetTest().methodName(),
                MavenNetworkMode.OFFLINE);
        DependencyWarmupRunner warmup = new DependencyWarmupRunner(sandbox, formalRoot);
        assertThat(warmup.warm(buggy, command)).isEmpty();
        assertThat(warmup.warm(fixed, command)).isEmpty();
        PatchGate gate = new PatchGate(formalRoot);
        assertThat(gate.prepare(buggy, "", draft, MavenNetworkMode.OFFLINE))
                .isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(gate.prepare(fixed, "", draft, MavenNetworkMode.OFFLINE))
                .isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        return new HistoricalReplayEngine(sandbox, formalRoot)
                .verify(new HistoricalReplayRequest(buggy, fixed, command, candidate.targetTest()));
    }

    private CandidateGenerationCoordinator coordinator(TestGenerator generator, SandboxRunner sandbox) {
        AtomicInteger counter = new AtomicInteger();
        var factory = new TempCandidateWorkspaceFactory(workspaceRoot, (url, sha, parent, name) ->
                LocalGitFixture.fetcher(historical.originDir())
                        .materialize(url, sha, parent, name + "-" + counter.incrementAndGet()));
        return new CandidateGenerationCoordinator(
                generator,
                new PatchGate(workspaceRoot),
                factory,
                new DependencyWarmupRunner(sandbox, workspaceRoot),
                new SideReplayRunner(sandbox, workspaceRoot));
    }

    private InMemoryGenerationRunSession newSession() {
        ClaimedRun claim = new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.HISTORICAL,
                RunState.GENERATING,
                1L,
                new RunLease(UUID.randomUUID(), "t", Instant.now().plusSeconds(600)),
                0,
                0,
                Optional.empty());
        return new InMemoryGenerationRunSession(claim);
    }

    private static int replayAttemptCount(ReplayResult result) {
        int n = result.primarySide().attempts().size();
        if (result.fixedSide().isPresent()) {
            n += result.fixedSide().orElseThrow().attempts().size();
        }
        return n;
    }

    private static Cohort sampleCohort() {
        List<CohortCase> cases = List.of(
                caseAt(1, "case-1", BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(2, "case-2", BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(3, "case-3", BenchmarkArtifacts.Role.CALIBRATION),
                caseAt(4, "case-4", BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(5, "case-5", BenchmarkArtifacts.Role.AGENT_BENCHMARK),
                caseAt(6, "case-6", BenchmarkArtifacts.Role.AGENT_BENCHMARK));
        return new Cohort(
                BenchmarkArtifacts.DATASET_REVISION,
                BenchmarkArtifacts.SEED,
                BenchmarkArtifacts.SELECTOR_VERSION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                cases,
                List.of());
    }

    private static CohortCase caseAt(int position, String caseId, BenchmarkArtifacts.Role role) {
        return new CohortCase(
                position,
                role,
                caseId,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://github.com/ex/repo.git",
                "https://github.com/ex/repo/issues/1",
                "MIT",
                "",
                "17");
    }

    private static CaseResult calibration(int pos, String caseId) {
        return new CaseResult(
                pos,
                caseId,
                RunPurpose.CALIBRATION,
                UUID.randomUUID(),
                RunState.COMPLETED,
                TestPatchProvenance.KNOWN_TRIGGER,
                Optional.of(ReplayVerdict.VALID_REPRODUCTION),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                null,
                null,
                0,
                0,
                0,
                null,
                Optional.of("a".repeat(64)),
                Optional.of("c.T"),
                Optional.of("m"),
                NOW,
                NOW);
    }

    private static CaseResult agent(int pos, String caseId, ReplayVerdict verdict, boolean failed) {
        return new CaseResult(
                pos,
                caseId,
                RunPurpose.AGENT_BENCHMARK,
                UUID.randomUUID(),
                failed ? RunState.FAILED : RunState.COMPLETED,
                TestPatchProvenance.AGENT_GENERATED,
                Optional.ofNullable(verdict),
                failed ? Optional.of("GENERATION") : Optional.empty(),
                failed ? Optional.of("GENERATION_EXHAUSTED") : Optional.empty(),
                failed ? Optional.of("generation attempts exhausted") : Optional.empty(),
                failed ? 3 : 1,
                "openai",
                "gpt-4.1-mini",
                100,
                200,
                300,
                1,
                Optional.of("a".repeat(64)),
                Optional.of("c.T"),
                Optional.of("m"),
                NOW,
                NOW);
    }

    private record Pipeline(
            ClaimedRun claim, ReplayResult formal, RunDetails completed, int warmupCalls) {}
}

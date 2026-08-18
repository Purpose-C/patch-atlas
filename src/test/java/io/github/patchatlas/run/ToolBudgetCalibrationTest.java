package io.github.patchatlas.run;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.benchmark.BenchmarkArtifacts;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.VerificationMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class ToolBudgetCalibrationTest {

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path temp;

    @Test
    void loadQueueReadsFrozenCohortWithoutOracleFiles() throws Exception {
        List<ToolBudgetCalibration.Slot> slots =
                ToolBudgetCalibration.loadQueue(Path.of("benchmark-cases/task018"));

        assertThat(slots).hasSize(6);
        assertThat(slots).extracting(ToolBudgetCalibration.Slot::caseId)
                .containsExactly(
                        "Bindambc-whatsapp-business-java-api-362caf5eb33c",
                        "jhy-jsoup-91b630f86b5c",
                        "davidmoten-word-wrap-e59eedf0bac7",
                        "jhy-jsoup-a96ebc95f9ad",
                        "jhy-jsoup-9de27fa7cd82",
                        "AuthMe-ConfigMe-7bf10c513479");
        assertThat(slots.get(0).issueContentSha256())
                .isEqualTo("06e47f05f0ff7bc8c38ea5ec721a61ca006ea6db1afcc7d0220b89ceb64bf8a1");
        assertThat(slots.get(0).buggyRevision())
                .isEqualTo("dec4ca17d1194c063e63197d214322f2149c4ee5");
        assertThat(slots.get(0).issueUrl())
                .isEqualTo("https://github.com/Bindambc/whatsapp-business-java-api/issues/100");
    }

    @Test
    void digestUsesFrozenTitleNewlineBodyAlgorithm() {
        String title = "NoSuchMethodError while uploadMedia";
        String body = "body-one\nbody-two";
        ToolBudgetCalibration.Slot slot = slotWithDigest(BenchmarkArtifacts.issueContentSha256(title, body));

        assertThat(ToolBudgetCalibration.skipReason(slot, title, body)).isEmpty();
        assertThat(ToolBudgetCalibration.skipReason(slot, title, body + " edited"))
                .contains("Issue edited, digest mismatch");
        assertThat(ToolBudgetCalibration.skipReason(slot, title, ""))
                .contains("Issue text blank, cannot verify digest");
    }

    @Test
    void mismatchIsNotAcceptedToPadTheSample() {
        ToolBudgetCalibration.Slot slot = slotWithDigest("a".repeat(64));
        Optional<String> skip = ToolBudgetCalibration.skipReason(slot, "title", "body");
        assertThat(skip).isPresent();
        assertThat(BenchmarkArtifacts.issueContentSha256("title", "body")).isNotEqualTo("a".repeat(64));
    }

    @Test
    void submissionIsLiveDiagnosticTextToolsWithoutFixedRevision() {
        ToolBudgetCalibration.Slot slot = slotWithDigest("b".repeat(64));
        ToolBudgetCalibration.PreparedCase prepared =
                new ToolBudgetCalibration.PreparedCase(slot, "title", "body");
        RunSubmission submission = ToolBudgetCalibration.submission(prepared);

        assertThat(submission.mode()).isEqualTo(VerificationMode.LIVE);
        assertThat(submission.fixedRevision()).isNull();
        assertThat(submission.contextOrigin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(submission.sourceSnapshots()).isEmpty();
        assertThat(submission.caseId()).isEqualTo(slot.caseId());
        assertThat(submission.buggyRevision()).isEqualTo(slot.buggyRevision());
        assertThat(submission.issueTitle()).isEqualTo("title");
        assertThat(submission.issueBody()).isEqualTo("body");
    }

    @Test
    void stopAfterThreeConsecutiveTransportStartFailures() {
        ToolBudgetCalibration.Report report =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        report.absorb(transportStartFail());
        report.absorb(transportStartFail());
        assertThat(report.consecutiveStartFails).isEqualTo(2);
        report.absorb(transportStartFail());
        assertThat(report.consecutiveStartFails).isEqualTo(3);
        assertThat(report.transportFailures).isEqualTo(3);
        assertThat(report.submits).isEqualTo(3);
        assertThat(report.transportFailures * 2).isGreaterThan(report.submits);
    }

    @Test
    void transportRateOverHalfStopsEvenWhenStartsSucceedLater() {
        ToolBudgetCalibration.Report report =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        report.absorb(transportStartFail());
        report.absorb(okSession());
        report.absorb(transportMidFail());
        assertThat(report.consecutiveStartFails).isEqualTo(0);
        assertThat(report.submits).isEqualTo(3);
        assertThat(report.transportFailures).isEqualTo(2);
        assertThat(report.transportFailures * 2).isGreaterThan(report.submits);
    }

    @Test
    void successfulStartResetsConsecutiveTransportStartFailures() {
        ToolBudgetCalibration.Report report =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        report.absorb(transportStartFail());
        report.absorb(transportStartFail());
        report.absorb(okSession());
        assertThat(report.consecutiveStartFails).isEqualTo(0);
        assertThat(report.transportFailures).isEqualTo(2);
    }

    @Test
    void parallelGuardAndRelaxedCapAreDetectedFromTraces() {
        assertThat(ToolBudgetCalibration.isParallelGuard(
                        "parallel tool calls exceed limit: received 9 (max 8) [search, search]"))
                .isTrue();
        assertThat(ToolBudgetCalibration.isParallelGuard(
                        "parallel tool calls are not supported: received 2 [search, read]"))
                .isFalse();
        assertThat(ToolBudgetCalibration.isParallelGuard("locating produced no readable context"))
                .isFalse();

        LocatingTraceStep exhausted = LocatingTraceStep.of(
                5,
                LocatingStepKind.BUDGET_EXHAUSTED,
                LocatingTraceOutcome.OK,
                ".",
                "CALLS",
                "{}");
        LocatingTraceStep clock = LocatingTraceStep.of(
                5,
                LocatingStepKind.BUDGET_EXHAUSTED,
                LocatingTraceOutcome.OK,
                ".",
                "CLOCK",
                "{}");
        assertThat(ToolBudgetCalibration.hitRelaxedCap(List.of(exhausted))).isTrue();
        assertThat(ToolBudgetCalibration.hitRelaxedCap(List.of(clock))).isFalse();
        assertThat(ToolBudgetCalibration.toolCalls(List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.SEARCH, "src", "search", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.READ, "src/A.java", "read", "{}"),
                        LocatingTraceStep.of(2, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"),
                        exhausted)))
                .isEqualTo(3);
        assertThat(ToolBudgetCalibration.reachedSubmit(List.of(
                        LocatingTraceStep.of(
                                0,
                                LocatingStepKind.SUBMIT,
                                LocatingTraceOutcome.ERROR,
                                ".",
                                "submit",
                                "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"))))
                .isTrue();
    }

    @Test
    void transportClassifierMatches429AndHttpZeroWithoutTreatingAuthAsTransport() {
        assertThat(ToolBudgetCalibration.isTransportFailure(new RuntimeException("HTTP 429 Too Many Requests")))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportFailure(new RuntimeException("curl HTTP=000")))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportFailure(
                        new RuntimeException(new java.net.ConnectException("connection refused"))))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportFailure(new RuntimeException("Unauthorized 401")))
                .isFalse();
        assertThat(ToolBudgetCalibration.isTransportSummary(
                        "workspace: InternalServerException: 503: No available channel"))
                .isTrue();
        assertThat(ToolBudgetCalibration.isTransportSummary("locating produced no readable context"))
                .isFalse();
    }

    @Test
    void locatingOptionsKeepModelNameAndDisableParallelCalls() {
        var options = ToolBudgetCalibration.locatingOptions("agnes-2.5-flash");
        assertThat(options.getModel()).isEqualTo("agnes-2.5-flash");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.TEXT);
    }

    @Test
    void locatingRequestSendsToolsAndNamedModelWithoutDraftSchema() throws Exception {
        wireMock.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(okJson(
                        """
                        {
                          "id": "chatcmpl-1",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "agnes-2.5-flash",
                          "choices": [{
                            "index": 0,
                            "message": { "role": "assistant", "content": "done" },
                            "finish_reason": "stop"
                          }],
                          "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                        }
                        """)));
        Path workspace = Files.createDirectories(temp.resolve("req-shape"));
        Files.writeString(workspace.resolve("README.md"), "hello");
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("req-git"));
        Path root = Files.createDirectories(temp.resolve("req-root"));
        ToolBudgetCalibration.RecordingTools tools = new ToolBudgetCalibration.RecordingTools();
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder(),
                ToolBudgetCalibration.recordingLoop(
                        OpenAiChatModelFactory.create("sk-test", "agnes-2.5-flash", wireMock.baseUrl()),
                        "agnes-2.5-flash",
                        tools));
        ClaimedRun claimed = new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.LOCATING,
                1L,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.empty());
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        GenerationInput input = new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        fixture.buggySha(),
                        "",
                        "21"),
                "title",
                "body",
                List.of());
        coordinator.run(claimed, input, session, RunPurpose.DIAGNOSTIC, ContextOrigin.TEXT_TOOLS);
        String requestBody = wireMock.findAll(postRequestedFor(urlPathMatching(".*/chat/completions")))
                .getFirst()
                .getBodyAsString();
        assertThat(requestBody).contains("\"model\":\"agnes-2.5-flash\"");
        assertThat(requestBody).contains("\"parallel_tool_calls\":false");
        assertThat(requestBody).contains("\"name\":\"search\"");
        assertThat(requestBody).contains("\"name\":\"list\"");
        assertThat(requestBody).contains("\"name\":\"read\"");
        assertThat(requestBody).contains("\"name\":\"submit\"");
        assertThat(requestBody).contains("\"role\":\"system\"");
        assertThat(requestBody).doesNotContain("patchText");
        assertThat(requestBody).doesNotContain("json_schema");
        assertThat(requestBody).doesNotContain("fixedRevision");
    }

    @Test
    void graphLocatingRequestSendsFindExpandReadSubmitWithoutSearch() throws Exception {
        wireMock.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(okJson(
                        """
                        {
                          "id": "chatcmpl-graph-1",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "glm-5.2",
                          "choices": [{
                            "index": 0,
                            "message": { "role": "assistant", "content": "done" },
                            "finish_reason": "stop"
                          }],
                          "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                        }
                        """)));
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("graph-git"));
        Path root = Files.createDirectories(temp.resolve("graph-root"));
        Path graphCache = Files.createDirectories(temp.resolve("graph-cache"));
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder(),
                null,
                ToolBudgetCalibration.graphLoop(
                        OpenAiChatModelFactory.create("sk-test", "glm-5.2", wireMock.baseUrl()),
                        "glm-5.2",
                        graphCache));
        ClaimedRun claimed = new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.LOCATING,
                1L,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.empty());
        InMemoryLocatingRunSession session = new InMemoryLocatingRunSession(claimed);
        GenerationInput input = new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        fixture.buggySha(),
                        "",
                        "21"),
                "title",
                "body",
                List.of());
        coordinator.run(claimed, input, session, RunPurpose.DIAGNOSTIC, ContextOrigin.GRAPH_TOOLS);
        String requestBody = wireMock.findAll(postRequestedFor(urlPathMatching(".*/chat/completions")))
                .getFirst()
                .getBodyAsString();
        assertThat(requestBody).contains("\"name\":\"find\"");
        assertThat(requestBody).contains("\"name\":\"expand\"");
        assertThat(requestBody).contains("\"name\":\"read\"");
        assertThat(requestBody).contains("\"name\":\"submit\"");
        assertThat(requestBody).doesNotContain("\"name\":\"search\"");
        assertThat(requestBody).doesNotContain("\"name\":\"list\"");
        assertThat(requestBody).doesNotContain("patchText");
        assertThat(requestBody).doesNotContain("json_schema");
        assertThat(session.traces())
                .anyMatch(step -> "GRAPH_BUILD".equals(step.reason()));
    }

    @Test
    void matchesCaseUsesOptionalCaseIdSubstring() {
        ToolBudgetCalibration.Slot slot = slotWithDigest("a".repeat(64));
        ToolBudgetCalibration.PreparedCase prepared =
                new ToolBudgetCalibration.PreparedCase(slot, "t", "b");
        assertThat(ToolBudgetCalibration.matchesCase(prepared)).isTrue();
    }

    @Test
    void smokeVerdictRequiresExploreThenReadThenSubmit() {
        ToolBudgetCalibration.Report empty =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        assertThat(ToolBudgetCalibration.smokeVerdict(empty)).contains("no session");

        ToolBudgetCalibration.Report onlySubmit =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        onlySubmit.absorb(new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                1,
                true,
                "SUBMIT",
                false,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of(LocatingTraceStep.of(0, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"))));
        assertThat(ToolBudgetCalibration.smokeVerdict(onlySubmit)).contains("one round");

        ToolBudgetCalibration.Report explored =
                ToolBudgetCalibration.Report.empty("agnes-2.5-flash", "https://example.invalid", Instant.now());
        explored.absorb(new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                3,
                true,
                "SUBMIT",
                false,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.SEARCH, ".", "search", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.READ, "src/A.java", "read", "{}"),
                        LocatingTraceStep.of(2, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"))));
        assertThat(ToolBudgetCalibration.smokeVerdict(explored)).isEqualTo("smoke passed");
    }

    @Test
    void parseOriginDefaultsToTextToolsAndAcceptsGraphTools() {
        assertThat(ToolBudgetCalibration.parseOrigin(null)).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(ToolBudgetCalibration.parseOrigin("  ")).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(ToolBudgetCalibration.parseOrigin("TEXT_TOOLS")).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(ToolBudgetCalibration.parseOrigin("GRAPH_TOOLS")).isEqualTo(ContextOrigin.GRAPH_TOOLS);
        assertThat(ToolBudgetCalibration.defaultOutput(ContextOrigin.TEXT_TOOLS))
                .isEqualTo("benchmark-cases/calibration-027-tool-budget");
        assertThat(ToolBudgetCalibration.defaultOutput(ContextOrigin.GRAPH_TOOLS))
                .isEqualTo("benchmark-cases/calibration-032-graph-tools-glm");
        assertThatThrownBy(() -> ToolBudgetCalibration.parseOrigin("HEURISTIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported calibrate origin");
    }

    @Test
    void submissionCanSelectGraphToolsWithoutFixedRevision() {
        ToolBudgetCalibration.Slot slot = slotWithDigest("b".repeat(64));
        ToolBudgetCalibration.PreparedCase prepared =
                new ToolBudgetCalibration.PreparedCase(slot, "title", "body");
        RunSubmission submission = ToolBudgetCalibration.submission(prepared, ContextOrigin.GRAPH_TOOLS);

        assertThat(submission.mode()).isEqualTo(VerificationMode.LIVE);
        assertThat(submission.fixedRevision()).isNull();
        assertThat(submission.contextOrigin()).isEqualTo(ContextOrigin.GRAPH_TOOLS);
        assertThat(submission.sourceSnapshots()).isEmpty();
    }

    @Test
    void toolCallsCountFindAndExpandButNotGraphBuild() {
        LocatingTraceStep build = LocatingTraceStep.of(
                0, LocatingStepKind.SELECTION, "graph", "GRAPH_BUILD", "{\"durationMs\":12,\"cacheHit\":false}");
        LocatingTraceStep find = LocatingTraceStep.of(1, LocatingStepKind.FIND, ".", "find", "{}");
        LocatingTraceStep expand = LocatingTraceStep.of(2, LocatingStepKind.EXPAND, "e1", "expand", "{}");
        LocatingTraceStep read = LocatingTraceStep.of(3, LocatingStepKind.READ, "src/A.java", "read", "{}");
        LocatingTraceStep submit = LocatingTraceStep.of(4, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}");
        assertThat(ToolBudgetCalibration.toolCalls(List.of(build, find, expand, read, submit))).isEqualTo(4);
        assertThat(ToolBudgetCalibration.graphBuild(List.of(build, find)))
                .contains(new ToolBudgetCalibration.GraphBuild(12L, false));
        assertThat(ToolBudgetCalibration.graphBuild(List.of(find))).isEmpty();
    }

    @Test
    void smokeVerdictAcceptsFindThenReadThenSubmit() {
        ToolBudgetCalibration.Report explored =
                ToolBudgetCalibration.Report.empty("glm-5.2", "https://example.invalid", Instant.now());
        explored.absorb(new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                3,
                true,
                "SUBMIT",
                false,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of(
                        LocatingTraceStep.of(0, LocatingStepKind.FIND, ".", "find", "{}"),
                        LocatingTraceStep.of(1, LocatingStepKind.READ, "src/A.java", "read", "{}"),
                        LocatingTraceStep.of(2, LocatingStepKind.SUBMIT, "src/A.java", "submit", "{}"))));
        assertThat(ToolBudgetCalibration.smokeVerdict(explored)).isEqualTo("smoke passed");
    }

    @Test
    void measureBudgetStaysAtRelaxedLimitsAndDoesNotChangeDefaults() {
        assertThat(ToolBudgetCalibration.MEASURE_MAX_CALLS).isEqualTo(60);
        assertThat(ToolBudgetCalibration.MEASURE_WALL_CLOCK).isEqualTo(Duration.ofMinutes(15));
        assertThat(io.github.patchatlas.analysis.LocalizationBudget.MAX_TOOL_CALLS).isEqualTo(35);
        assertThat(io.github.patchatlas.analysis.LocalizationBudget.WALL_CLOCK).isEqualTo(Duration.ofMinutes(9));
    }

    @Test
    void sanitizeStripsCredentialAssignments() {
        assertThat(ToolBudgetCalibration.sanitize("OPENAI_API_KEY=sk-secret leftover"))
                .doesNotContain("sk-secret")
                .contains("OPENAI_API_KEY=***");
    }

    @Test
    @Tag("model")
    @EnabledIfEnvironmentVariable(named = "PATCHATLAS_CALIBRATE_027", matches = "1")
    void runCalibrationWhenRequested() throws Exception {
        int code = ToolBudgetCalibration.execute();
        assertThat(code).isIn(0, 2);
    }

    private static ToolBudgetCalibration.Slot slotWithDigest(String digest) {
        return new ToolBudgetCalibration.Slot(
                1,
                "Bindambc-whatsapp-business-java-api-362caf5eb33c",
                "https://github.com/Bindambc/whatsapp-business-java-api.git",
                "https://github.com/Bindambc/whatsapp-business-java-api/issues/100",
                "MIT",
                "",
                "17",
                digest,
                "dec4ca17d1194c063e63197d214322f2149c4ee5");
    }

    private static ToolBudgetCalibration.SessionRow transportStartFail() {
        return new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                0,
                false,
                "TRANSPORT",
                false,
                true,
                false,
                false,
                List.of(),
                List.of(),
                List.of());
    }

    private static ToolBudgetCalibration.SessionRow transportMidFail() {
        return new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                3,
                false,
                "TRANSPORT",
                false,
                true,
                false,
                true,
                List.of(),
                List.of(),
                List.of());
    }

    private static ToolBudgetCalibration.SessionRow okSession() {
        return new ToolBudgetCalibration.SessionRow(
                UUID.randomUUID().toString(),
                "case",
                1,
                Instant.now().toString(),
                10L,
                8,
                true,
                "SUBMIT",
                false,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of());
    }
}

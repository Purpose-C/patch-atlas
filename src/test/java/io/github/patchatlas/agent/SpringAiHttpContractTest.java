package io.github.patchatlas.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.patchatlas.benchmark.BenchmarkArtifacts;
import io.github.patchatlas.benchmark.BenchmarkEvidenceExporter.GenerationRejectionLog;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.json.JsonMapper;

class SpringAiHttpContractTest {

    private static final String COMPLETIONS = ".*/chat/completions";
    private static final String VALID_DRAFT =
            CandidateDraftParserTest.envelope(FakeTestGeneratorTest.minimalCreatePatch());

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void successfulChatCompletionProducesDraft() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(VALID_DRAFT, "stop"))));

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.GeneratedDraft.class);
        var draft = (GenerationResult.GeneratedDraft) call.result();
        assertThat(draft.draft().targetTest()).isEqualTo(new TargetTest("fixtures.NewTest", "works"));
        assertThat(draft.usage()).contains(new ModelUsage(10, 20, 30));
        assertThat(call.requests()).isEqualTo(1);
        String request = wireMock.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(request).contains("\"tools\"");
        assertThat(request).contains(SubmitDraftTool.NAME);
        assertThat(request).contains("tool_choice");
        assertThat(request).contains("required");
        assertThat(request).doesNotContain("JSON_OBJECT");
        assertThat(request).doesNotContain("json_schema");
        assertThat(request).contains("diff --git");
    }

    @Test
    void stopFinishReasonRecountsWrongHunkHeaderThroughRealGenerator() {
        String envelope = CandidateDraftParserTest.envelope(wrongHeaderCreatePatch());
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(envelope, "stop"))));

        ObservedCall call = generate();

        assertThat(call.result())
                .isInstanceOf(GenerationResult.GeneratedDraft.class)
                .isNotInstanceOf(GenerationResult.GenerationCallFailure.class);
        var draft = (GenerationResult.GeneratedDraft) call.result();
        assertThat(draft.draft().targetTest()).isEqualTo(new TargetTest("fixtures.NewTest", "works"));
        assertThat(draft.draft().patchText()).isEqualTo(wrongHeaderCreatePatch());
    }

    @Test
    void lengthFinishReasonRejectsWrongHunkHeaderAsTruncatedNotStructuredInvalid() {
        String envelope = CandidateDraftParserTest.envelope(wrongHeaderCreatePatch());
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(envelope, "length"))));

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.DraftRejected.class);
        var rejected = (GenerationResult.DraftRejected) call.result();
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.RESPONSE_TRUNCATED);
        assertThat(rejected.reason()).contains("响应被截断");
        assertThat(call.result()).isNotInstanceOf(GenerationResult.GenerationCallFailure.class);
        assertThat(call.result()).isNotInstanceOf(GenerationResult.GeneratedDraft.class);
    }

    @Test
    void underivableTargetIsDraftRejectedNotStructuredInvalid() {
        String envelope = CandidateDraftParserTest.envelope(twoTestsCreatePatch());
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(envelope, "stop"))));

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.DraftRejected.class);
        var rejected = (GenerationResult.DraftRejected) call.result();
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(call.result()).isNotInstanceOf(GenerationResult.GenerationCallFailure.class);
    }

    @Test
    void invalidJsonRemainsStructuredOutputInvalidEvenWhenFinishReasonIsLength() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion("not-json", "length"))));
        ObservedCall call = generate();
        assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        assertThat(((GenerationResult.GenerationCallFailure) call.result()).summary()).contains("not json");
        assertThat(call.result()).isNotInstanceOf(GenerationResult.DraftRejected.class);
    }

    @Test
    void generatorPathRejectionCategorySurvivesEvidenceLog() throws Exception {
        String envelope = CandidateDraftParserTest.envelope(twoTestsCreatePatch());
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(envelope, "stop"))));
        ObservedCall call = generate();
        var rejected = (GenerationResult.DraftRejected) call.result();

        Path file = Files.createTempFile("generation-rejections", ".json");
        try {
            String json =
                    """
                    {
                      "cases": [
                        {
                          "caseId": "generator-path",
                          "rejections": [
                            {
                              "attemptOrdinal": 1,
                              "feedbackCategory": "PATCH_POLICY_REJECTED",
                              "feedbackSummary": %s,
                              "rejectionCategory": "%s"
                            }
                          ]
                        }
                      ]
                    }
                    """
                            .formatted(
                                    JsonMapper.shared().writeValueAsString(rejected.reason()),
                                    rejected.category().name());
            Files.writeString(file, json);
            GenerationRejectionLog log =
                    new BenchmarkArtifacts().readJson(file, GenerationRejectionLog.class);
            assertThat(log.cases().getFirst().rejections().getFirst().rejectionCategory())
                    .isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
            assertThat(log.cases().getFirst().rejections().getFirst().feedbackCategory())
                    .isNotEqualTo("STRUCTURED_OUTPUT_INVALID");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFinishReasonStillRejectsWrongHunkHeaderThroughRealGenerator() {
        String envelope = CandidateDraftParserTest.envelope(wrongHeaderCreatePatch());
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(envelope, "unknown"))));

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.DraftRejected.class);
        var rejected = (GenerationResult.DraftRejected) call.result();
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.MALFORMED_OR_OVERSIZED_PATCH);
        assertThat(rejected.reason()).contains("hunk new count mismatch");
        CompletionDiagnostics diagnostics = call.result().completionDiagnostics().orElseThrow();
        assertThat(diagnostics.indicatesComplete()).isFalse();
        assertThat(diagnostics.finishReason()).isNotIn("stop", "tool_calls");
        assertThat(call.result()).isNotInstanceOf(GenerationResult.GeneratedDraft.class);
        assertThat(call.result()).isNotInstanceOf(GenerationResult.GenerationCallFailure.class);
    }

    @Test
    void illegalToolArgumentsAreRejectedWithoutThrowing() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion("not-json", "stop"))));
        assertThatCode(() -> {
                    ObservedCall call = generate();
                    assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
                    assertThat(((GenerationResult.GenerationCallFailure) call.result()).summary())
                            .contains("not json");
                    assertThat(call.requests()).isEqualTo(1);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void textOnlyResponseDoesNotScrapePatchFromContent() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(textCompletion(VALID_DRAFT))));
        ObservedCall call = generate();
        assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        assertThat(((GenerationResult.GenerationCallFailure) call.result()).summary())
                .contains("submit_draft");
        assertThat(call.result()).isNotInstanceOf(GenerationResult.GeneratedDraft.class);
    }

    @Test
    void markdownFenceInPatchArgumentsIsRejectedWithoutStripping() {
        String fenced = "{\"patch\":\"```diff\\nnot-a-patch```\"}";
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion(fenced, "stop"))));
        ObservedCall call = generate();
        assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        assertThat(((GenerationResult.GenerationCallFailure) call.result()).summary())
                .contains("markdown fence");
    }

    @Test
    void retriesHttp429UntilLimitThenSucceeds() {
        stubFailuresThenSuccess(429, SpringAiTestGenerator.MAX_TRANSPORT_RETRIES);

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.GeneratedDraft.class);
        assertThat(call.requests()).isEqualTo(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES + 1);
        assertThat(call.delays())
                .containsExactly(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(40));
    }

    @Test
    void exhaustedHttp429StopsAtRetryLimit() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate\"}")));

        ObservedCall call = generate();

        assertFailure(call.result(), CallFailureCategory.MODEL_UNAVAILABLE);
        assertThat(call.requests()).isEqualTo(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES + 1);
        assertThat(call.delays()).hasSize(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES);
    }

    @Test
    void retriesHttp5xxUntilLimitThenSucceeds() {
        stubStatusSequenceThenSuccess(500, 503, 500, 503);

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.GeneratedDraft.class);
        assertThat(call.requests()).isEqualTo(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES + 1);
        assertThat(call.delays()).hasSize(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES);
    }

    @Test
    void exhaustedHttp5xxStopsAtRetryLimit() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));

        ObservedCall call = generate();

        assertFailure(call.result(), CallFailureCategory.MODEL_UNAVAILABLE);
        assertThat(call.requests()).isEqualTo(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES + 1);
    }

    @Test
    void retriesConnectionResetUntilLimit() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ObservedCall call = generate();

        assertFailure(call.result(), CallFailureCategory.MODEL_UNAVAILABLE);
        assertThat(call.delays())
                .containsExactly(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(40));
        assertThat(call.requests()).isGreaterThanOrEqualTo(SpringAiTestGenerator.MAX_TRANSPORT_RETRIES + 1);
    }

    @Test
    void http401And403DoNotRetry() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"no\"}")));
        ObservedCall unauthorized = generate();
        assertFailure(unauthorized.result(), CallFailureCategory.MODEL_AUTHENTICATION_ERROR);
        assertThat(unauthorized.requests()).isEqualTo(1);
        assertThat(unauthorized.delays()).isEmpty();

        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"no\"}")));
        ObservedCall forbidden = generate();
        assertFailure(forbidden.result(), CallFailureCategory.MODEL_AUTHENTICATION_ERROR);
        assertThat(forbidden.requests()).isEqualTo(1);
        assertThat(forbidden.delays()).isEmpty();
    }

    @Test
    void illegalJsonAndEmptyContentAreRejectedWithoutThrowing() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion("not-json", "stop"))));
        assertThatCode(() -> {
                    ObservedCall call = generate();
                    assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
                    assertThat(call.requests()).isEqualTo(1);
                })
                .doesNotThrowAnyException();

        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletion("", "stop"))));
        assertThatCode(() -> {
                    ObservedCall call = generate();
                    assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
                    assertThat(((GenerationResult.GenerationCallFailure) call.result()).summary())
                            .isEqualTo("empty content");
                })
                .doesNotThrowAnyException();
    }

    @Test
    void finishReasonLengthRecordsTruncationAndZeroTextTokens() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(okJson(chatCompletion(VALID_DRAFT, "length", 10, 8192, 8202, 8192L))));

        ObservedCall call = generate();

        CompletionDiagnostics diagnostics = call.result().completionDiagnostics().orElseThrow();
        assertThat(diagnostics.finishReason()).isEqualTo("length");
        assertThat(diagnostics.reasoningTokens()).isEqualTo("8192");
        assertThat(diagnostics.textTokens()).isEqualTo("0");
    }

    @Test
    void missingUsageIsUnknownNotZero() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS)).willReturn(okJson(chatCompletionWithoutUsage(VALID_DRAFT))));

        ObservedCall call = generate();

        assertThat(call.result()).isInstanceOf(GenerationResult.GeneratedDraft.class);
        assertThat(call.result().usage()).isEmpty();
    }

    @Test
    void oversizedHttpBodyIsRejectedWithoutRetry() {
        String oversized = "{\"pad\":\"" + "x".repeat(OpenAiChatModelFactory.MAX_HTTP_BODY_BYTES + 1) + "\"}";
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(oversized)));

        ObservedCall call = generate();

        assertFailure(call.result(), CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        assertThat(call.requests()).isEqualTo(1);
        assertThat(call.delays()).isEmpty();
    }

    private ObservedCall generate() {
        List<Duration> delays = new ArrayList<>();
        ChatModel model = OpenAiChatModelFactory.create("sk-test", "gpt-test", wireMock.baseUrl());
        SpringAiTestGenerator generator = new SpringAiTestGenerator(
                GeneratorIdentity.openai("gpt-test"), model, new CandidateDraftParser(), delays::add);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        return new ObservedCall(result, List.copyOf(delays), wireMock.getAllServeEvents().size());
    }

    private static void assertFailure(GenerationResult result, CallFailureCategory category) {
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        assertThat(((GenerationResult.GenerationCallFailure) result).category()).isEqualTo(category);
    }

    private void stubFailuresThenSuccess(int status, int failureCount) {
        String scenario = "fail-" + status;
        String state = STARTED;
        for (int i = 0; i < failureCount; i++) {
            String next = "after-" + i;
            wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                    .inScenario(scenario)
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(status).withBody("{\"error\":\"retry\"}"))
                    .willSetStateTo(next));
            state = next;
        }
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario(scenario)
                .whenScenarioStateIs(state)
                .willReturn(okJson(chatCompletion(VALID_DRAFT, "stop"))));
    }

    private void stubStatusSequenceThenSuccess(int... statuses) {
        String scenario = "mixed-5xx";
        String state = STARTED;
        for (int i = 0; i < statuses.length; i++) {
            String next = "after-" + i;
            wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                    .inScenario(scenario)
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(statuses[i]).withBody("{\"error\":\"retry\"}"))
                    .willSetStateTo(next));
            state = next;
        }
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario(scenario)
                .whenScenarioStateIs(state)
                .willReturn(okJson(chatCompletion(VALID_DRAFT, "stop"))));
    }

    private static String chatCompletion(String argumentsJson, String finishReason) {
        return chatCompletion(argumentsJson, finishReason, 10, 20, 30, null);
    }

    private static String chatCompletion(
            String argumentsJson,
            String finishReason,
            int prompt,
            int completion,
            int total,
            Long reasoningTokens) {
        String escaped = argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        String details = reasoningTokens == null
                ? ""
                : """
                        ,
                        "completion_tokens_details": { "reasoning_tokens": %d }
                        """
                        .formatted(reasoningTokens);
        String finish = "stop".equals(finishReason) ? "tool_calls" : finishReason;
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "gpt-test",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": { "name": "submit_draft", "arguments": "%s" }
                          }
                        ]
                      },
                      "finish_reason": "%s"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": %d,
                    "completion_tokens": %d,
                    "total_tokens": %d
                    %s
                  }
                }
                """
                .formatted(escaped, finish, prompt, completion, total, details);
    }

    private static String chatCompletionWithoutUsage(String argumentsJson) {
        String escaped = argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "gpt-test",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": { "name": "submit_draft", "arguments": "%s" }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ]
                }
                """
                .formatted(escaped);
    }

    private static String wrongHeaderCreatePatch() {
        return """
                diff --git a/src/test/java/fixtures/NewTest.java b/src/test/java/fixtures/NewTest.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/NewTest.java
                @@ -0,0 +1,99 @@
                +package fixtures;
                +
                +import org.junit.jupiter.api.Test;
                +
                +class NewTest {
                +  @Test
                +  void works() {}
                +}
                """;
    }

    private static String twoTestsCreatePatch() {
        return TargetTestDeriverTest.createPatch(
                "src/test/java/fixtures/NewTest.java",
                """
                package fixtures;

                import org.junit.jupiter.api.Test;

                class NewTest {
                  @Test
                  void first() {}

                  @Test
                  void second() {}
                }
                """);
    }

    private static String textCompletion(String contentJson) {
        String escaped = contentJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "gpt-test",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "%s" },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30
                  }
                }
                """
                .formatted(escaped);
    }

    private static GenerationInput sampleInput() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "a".repeat(40),
                        "",
                        "21"),
                "t",
                "b",
                List.of());
    }

    private record ObservedCall(GenerationResult result, List<Duration> delays, int requests) {}
}

package io.github.patchatlas.analysis;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.InMemoryLocatingRunSession;
import io.github.patchatlas.run.LocatingCoordinator;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceOutcome;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.RunLease;
import io.github.patchatlas.run.RunState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class LocalizationToolCallingManagerTest {

    private static final Instant T0 = Instant.parse("2026-08-17T00:00:00Z");
    private static final String COMPLETIONS = ".*/chat/completions";

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path temp;

    @Test
    void searchReadSubmitWritesThreeTraceRowsInOrder() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        manager.executeToolCalls(prompt(), toolCall("c1", "search", "{\"pattern\":\"Foo\"}"));
        manager.executeToolCalls(prompt(), toolCall("c2", "read", "{\"path\":\"src/Foo.java\"}"));
        ToolExecutionResult submit =
                manager.executeToolCalls(prompt(), toolCall("c3", "submit", "{\"paths\":[\"src/Foo.java\"]}"));

        assertThat(submit.returnDirect()).isTrue();
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.SEARCH, LocatingStepKind.READ, LocatingStepKind.SUBMIT);
        assertThat(session.traces()).extracting(LocatingTraceStep::seq).containsExactly(0, 1, 2);
        assertThat(session.traces()).extracting(LocatingTraceStep::outcome)
                .containsOnly(LocatingTraceOutcome.OK);
    }

    @Test
    void callBudgetExhaustionTruncatesIntoGeneratingWhenReadsExist() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 1);

        manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"src/Foo.java\"}"));
        ToolExecutionResult exhausted =
                manager.executeToolCalls(prompt(), toolCall("c2", "search", "{\"pattern\":\"x\"}"));
        LocatingCoordinator.Result result = manager.finish();

        assertThat(exhausted.returnDirect()).isTrue();
        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.claim().state()).isEqualTo(RunState.GENERATING);
        assertThat(session.origin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(session.committedSnapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactly("src/Foo.java");
        assertThat(session.traces())
                .anyMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED
                        && "CALLS".equals(step.reason()));
    }

    @Test
    void wallClockExhaustionTruncatesIntoGeneratingWhenReadsExist() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                workspaceTools(),
                session,
                new LocalizationBudget(25, Duration.ofMinutes(5), T0),
                clock);

        manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"src/Foo.java\"}"));
        clock.advance(Duration.ofMinutes(5));
        ToolExecutionResult exhausted =
                manager.executeToolCalls(prompt(), toolCall("c2", "search", "{\"pattern\":\"x\"}"));
        LocatingCoordinator.Result result = manager.finish();

        assertThat(exhausted.returnDirect()).isTrue();
        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.claim().state()).isEqualTo(RunState.GENERATING);
        assertThat(session.traces())
                .anyMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED
                        && "CLOCK".equals(step.reason()));
    }

    @Test
    void toolErrorGoesToToolResponseAndTraceNotGenerationFeedback() throws Exception {
        List<GenerationFeedback> feedbackSink = new ArrayList<>();
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult result =
                manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"../secret.txt\"}"));

        ToolResponseMessage response = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(response.getResponses().getFirst().responseData()).contains("error");
        assertThat(result.returnDirect()).isFalse();
        assertThat(session.traces()).hasSize(1);
        assertThat(session.traces().getFirst().kind()).isEqualTo(LocatingStepKind.READ);
        assertThat(session.traces().getFirst().outcome()).isEqualTo(LocatingTraceOutcome.ERROR);
        assertThat(feedbackSink).isEmpty();
        assertThat(session.origin()).isNull();
        assertThat(session.claim().state()).isEqualTo(RunState.LOCATING);
    }

    @Test
    void callBudgetExhaustionWithZeroReadsFailsAsLocatingNoContext() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 1);

        manager.executeToolCalls(prompt(), toolCall("c1", "search", "{\"pattern\":\"Foo\"}"));
        manager.executeToolCalls(prompt(), toolCall("c2", "search", "{\"pattern\":\"Bar\"}"));
        LocatingCoordinator.Result result = manager.finish();

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        var details = ((LocatingCoordinator.Result.RunFailed) result).details();
        assertThat(details.state()).isEqualTo(RunState.FAILED);
        assertThat(details.failure().orElseThrow().stage()).isEqualTo(FailureStage.LOCATING);
        assertThat(details.failure().orElseThrow().category())
                .isEqualTo(FailureCategory.LOCATING_NO_CONTEXT);
        assertThat(session.origin()).isNull();
        assertThat(session.claim().state()).isEqualTo(RunState.LOCATING);
        assertThat(session.traces())
                .anyMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED
                        && "CALLS".equals(step.reason()));
        assertThat(session.traces())
                .noneMatch(step -> step.kind() == LocatingStepKind.SUBMIT);
        assertThat(session.traces())
                .noneMatch(step -> step.kind() == LocatingStepKind.READ);
    }

    @Test
    void advisorLoopSearchReadSubmitHitsWireMockTwiceOrMore() throws Exception {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("tools")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson(completion("c1", "search", "{\"pattern\":\"Foo\"}")))
                .willSetStateTo("read"));
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("tools")
                .whenScenarioStateIs("read")
                .willReturn(okJson(completion("c2", "read", "{\"path\":\"src/Foo.java\"}")))
                .willSetStateTo("submit"));
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("tools")
                .whenScenarioStateIs("submit")
                .willReturn(okJson(completion("c3", "submit", "{\"paths\":[\"src/Foo.java\"]}")))
                .willSetStateTo("done"));
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("tools")
                .whenScenarioStateIs("done")
                .willReturn(okJson(completion("c4", "search", "{\"pattern\":\"nope\"}"))));

        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);
        ChatModel chatModel = OpenAiChatModelFactory.create("sk-test", "gpt-test", wireMock.baseUrl());
        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallAdvisor.builder().toolCallingManager(manager).build())
                .defaultToolCallbacks(
                        stub(LocalizationToolCallingManager.SEARCH),
                        stub(LocalizationToolCallingManager.LIST),
                        stub(LocalizationToolCallingManager.READ),
                        stub(LocalizationToolCallingManager.SUBMIT))
                .build();

        client.prompt().user("locate Foo").call().chatResponse();

        int httpCalls = wireMock.findAll(postRequestedFor(urlPathMatching(COMPLETIONS))).size();
        assertThat(httpCalls).isGreaterThanOrEqualTo(2);
        assertThat(httpCalls).isEqualTo(3);
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.SEARCH, LocatingStepKind.READ, LocatingStepKind.SUBMIT);
    }

    private LocalizationToolCallingManager manager(InMemoryLocatingRunSession session, int maxCalls)
            throws Exception {
        return new LocalizationToolCallingManager(
                workspaceTools(),
                session,
                new LocalizationBudget(maxCalls, Duration.ofMinutes(5), T0),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private LocalizationTools workspaceTools() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        Path src = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");
        return new TextSearchTools(workspace);
    }

    private static InMemoryLocatingRunSession newSession() {
        return new InMemoryLocatingRunSession(new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.LOCATING,
                1,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.empty()));
    }

    private static Prompt prompt() {
        return new Prompt("locate");
    }

    private static ChatResponse toolCall(String id, String name, String args) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ToolCallback stub(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String functionInput) {
                throw new AssertionError("Advisor must dispatch via LocalizationToolCallingManager");
            }
        };
    }

    private static String completion(String callId, String name, String arguments) {
        String escaped = arguments.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "id": "chatcmpl-%s",
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
                            "id": "%s",
                            "type": "function",
                            "function": { "name": "%s", "arguments": "%s" }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ],
                  "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                }
                """
                .formatted(callId, callId, name, escaped);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant start) {
            this.instant = new AtomicReference<>(start);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

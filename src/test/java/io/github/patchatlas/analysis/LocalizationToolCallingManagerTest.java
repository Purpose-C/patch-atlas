package io.github.patchatlas.analysis;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
    void repeatedExactCallIsNotReexecutedButStillConsumesBudget() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationBudget budget = new LocalizationBudget(25, Duration.ofMinutes(5), T0);
        CountingTools tools = new CountingTools(workspaceTools());
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                tools, session, budget, Clock.fixed(T0, ZoneOffset.UTC));

        manager.executeToolCalls(prompt(), toolCall("c1", "search", "{\"pattern\":\"Foo\"}"));
        ToolExecutionResult second =
                manager.executeToolCalls(prompt(), toolCall("c2", "search", "{\"pattern\":\"Foo\"}"));

        assertThat(tools.searches).isEqualTo(1);
        assertThat(budget.calls()).isEqualTo(2);
        String body = lastToolResponse(second).getResponses().getFirst().responseData();
        assertThat(body).contains("already made at step 0");
        assertThat(session.traces()).extracting(LocatingTraceStep::outcome)
                .containsExactly(LocatingTraceOutcome.OK, LocatingTraceOutcome.OK);
        assertThat(session.traces().get(1).detailJson()).contains("\"repeat_of\":0");
    }

    @Test
    void argumentsDifferingOnlyByWhitespaceAreRepeats() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        CountingTools tools = new CountingTools(workspaceTools());
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                tools,
                session,
                new LocalizationBudget(25, Duration.ofMinutes(5), T0),
                Clock.fixed(T0, ZoneOffset.UTC));

        manager.executeToolCalls(prompt(), toolCall("c1", "search", "{\"pattern\":\"Foo\"}"));
        manager.executeToolCalls(prompt(), toolCall("c2", "search", "{\"pattern\":\" Foo \"}"));

        assertThat(tools.searches).isEqualTo(1);
        assertThat(session.traces().get(1).detailJson()).contains("repeat_of");
    }

    @Test
    void differentStartLineIsNotARepeat() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        CountingTools tools = new CountingTools(workspaceTools());
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                tools,
                session,
                new LocalizationBudget(25, Duration.ofMinutes(5), T0),
                Clock.fixed(T0, ZoneOffset.UTC));

        manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"src/Foo.java\",\"startLine\":1}"));
        manager.executeToolCalls(prompt(), toolCall("c2", "read", "{\"path\":\"src/Foo.java\",\"startLine\":2}"));

        assertThat(tools.reads).isEqualTo(2);
        assertThat(session.traces()).noneMatch(step -> step.detailJson().contains("repeat_of"));
    }

    @Test
    void budgetWarningIsAppendedOnceWhenRemainingDropsBelowQuarter() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationBudget budget = new LocalizationBudget(8, Duration.ofMinutes(5), T0);
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                workspaceTools(), session, budget, Clock.fixed(T0, ZoneOffset.UTC));

        ToolExecutionResult seventh = null;
        for (int i = 1; i <= 7; i++) {
            seventh = manager.executeToolCalls(prompt(), toolCall("c" + i, "search", "{\"pattern\":\"p" + i + "\"}"));
        }
        String body = lastToolResponse(seventh).getResponses().getFirst().responseData();
        assertThat(body).contains("Budget nearly exhausted");
        assertThat(body).contains("7 of 8");
        assertThat(session.traces().stream().filter(step -> step.kind() == LocatingStepKind.BUDGET_WARNING))
                .hasSize(1);

        ToolExecutionResult later =
                manager.executeToolCalls(prompt(), toolCall("c8", "search", "{\"pattern\":\"last\"}"));
        String laterBody = lastToolResponse(later).getResponses().getFirst().responseData();
        assertThat(laterBody).doesNotContain("Budget nearly exhausted");
        assertThat(session.traces().stream().filter(step -> step.kind() == LocatingStepKind.BUDGET_WARNING))
                .hasSize(1);
    }

    @Test
    void threeParallelCallsWriteThreeTracesAndConsumeThreeBudget() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationBudget budget = new LocalizationBudget(25, Duration.ofMinutes(5), T0);
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                workspaceTools(), session, budget, Clock.fixed(T0, ZoneOffset.UTC));

        ToolExecutionResult result = manager.executeToolCalls(
                prompt(),
                toolCalls(
                        tc("c1", "search", "{\"pattern\":\"Foo\"}"),
                        tc("c2", "list", "{\"path\":\".\"}"),
                        tc("c3", "read", "{\"path\":\"src/Foo.java\"}")));

        assertThat(result.returnDirect()).isFalse();
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.SEARCH, LocatingStepKind.LIST, LocatingStepKind.READ);
        assertThat(session.traces()).extracting(LocatingTraceStep::seq).containsExactly(0, 1, 2);
        assertThat(budget.calls()).isEqualTo(3);
        assertThat(toolResponseIds(result)).containsExactly("c1", "c2", "c3");
    }

    @Test
    void successfulSubmitInBatchStillFinishesRemainingCallsThenTerminates() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult result = manager.executeToolCalls(
                prompt(),
                toolCalls(
                        tc("c1", "list", "{\"path\":\".\"}"),
                        tc("c2", "submit", "{\"paths\":[\"src/Foo.java\"]}"),
                        tc("c3", "search", "{\"pattern\":\"Foo\"}")));

        assertThat(result.returnDirect()).isTrue();
        assertThat(toolResponseIds(result)).containsExactly("c1", "c2", "c3");
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.LIST, LocatingStepKind.SUBMIT, LocatingStepKind.SEARCH);
        assertThat(session.traces()).extracting(LocatingTraceStep::seq).containsExactly(0, 1, 2);
        assertThat(manager.finish()).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.origin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(session.committedSnapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactly("src/Foo.java");
    }

    @Test
    void midBatchBudgetExhaustionRespondsToRemainingIdsWithoutExecutingThem() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationBudget budget = new LocalizationBudget(1, Duration.ofMinutes(5), T0);
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                workspaceTools(), session, budget, Clock.fixed(T0, ZoneOffset.UTC));

        ToolExecutionResult result = manager.executeToolCalls(
                prompt(),
                toolCalls(
                        tc("c1", "search", "{\"pattern\":\"Foo\"}"),
                        tc("c2", "list", "{\"path\":\".\"}"),
                        tc("c3", "read", "{\"path\":\"src/Foo.java\"}")));

        assertThat(result.returnDirect()).isTrue();
        assertThat(budget.calls()).isEqualTo(1);
        assertThat(toolResponseIds(result)).containsExactly("c1", "c2", "c3");
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(
                        LocatingStepKind.SEARCH,
                        LocatingStepKind.BUDGET_EXHAUSTED,
                        LocatingStepKind.BUDGET_EXHAUSTED);
        assertThat(session.traces()).extracting(LocatingTraceStep::seq).containsExactly(0, 1, 2);
        List<String> bodies = lastToolResponse(result).getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .toList();
        assertThat(bodies.get(0)).doesNotContain("budget exhausted");
        assertThat(bodies.get(1)).contains("budget exhausted");
        assertThat(bodies.get(2)).contains("budget exhausted");
        assertThat(manager.hasReads()).isFalse();
    }

    @Test
    void nineParallelToolCallsAreRejectedAndNotRecordedOnTrace() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            calls.add(tc("c" + i, "search", "{\"pattern\":\"p" + i + "\"}"));
        }
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(calls).build();

        assertThatThrownBy(() -> manager.executeToolCalls(prompt(), new ChatResponse(List.of(new Generation(assistant)))))
                .isInstanceOf(LocatingToolCallException.class)
                .hasMessageContaining("9")
                .hasMessageContaining("8");
        assertThat(session.traces()).isEmpty();
        assertThat(manager.hasReads()).isFalse();
    }

    @Test
    void emptyToolCallsAreRejectedAndDoNotFabricateSearch() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);
        AssistantMessage empty = AssistantMessage.builder().content("").build();

        assertThatThrownBy(() -> manager.executeToolCalls(prompt(), new ChatResponse(List.of(new Generation(empty)))))
                .isInstanceOf(LocatingToolCallException.class)
                .hasMessageContaining("without tool calls");
        assertThat(session.traces()).isEmpty();
        assertThat(session.traces()).noneMatch(step -> step.kind() == LocatingStepKind.SEARCH);
        assertThat(manager.hasReads()).isFalse();
    }

    @Test
    void resolveToolDefinitionsDeclaresSearchListReadSubmitSchemas() throws Exception {
        LocalizationToolCallingManager manager = manager(newSession(), 25);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        List<ToolDefinition> definitions = manager.resolveToolDefinitions(options);

        assertThat(definitions).extracting(ToolDefinition::name).containsExactly("search", "list", "read", "submit");
        assertThat(manager.resolveToolDefinitions(options))
                .isEqualTo(LocalizationToolCallingManager.locatingToolDefinitions());

        JsonNode search = schema(definitions, "search");
        assertThat(required(search)).containsExactly("pattern");
        assertThat(search.get("properties").get("pattern").get("type").asString()).isEqualTo("string");
        assertThat(search.get("properties").get("pathGlob").get("type").asString()).isEqualTo("string");

        JsonNode list = schema(definitions, "list");
        assertThat(required(list)).containsExactly("path");
        assertThat(list.get("properties").get("path").get("type").asString()).isEqualTo("string");

        JsonNode read = schema(definitions, "read");
        assertThat(required(read)).containsExactly("path");
        assertThat(read.get("properties").get("path").get("type").asString()).isEqualTo("string");
        assertThat(read.get("properties").get("startLine").get("type").asString()).isEqualTo("integer");
        assertThat(read.get("properties").get("span").get("type").asString()).isEqualTo("integer");

        JsonNode submit = schema(definitions, "submit");
        assertThat(required(submit)).containsExactly("paths");
        assertThat(submit.get("properties").get("paths").get("type").asString()).isEqualTo("array");
        assertThat(submit.get("properties").get("paths").get("items").get("type").asString()).isEqualTo("string");
    }

    @Test
    void readPastEndOfFileIsErrorNotEmptySlice() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult result =
                manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"src/Foo.java\",\"startLine\":99}"));

        String body = lastToolResponse(result).getResponses().getFirst().responseData();
        assertThat(body).contains("error");
        assertThat(body).contains("startLine");
        assertThat(body).doesNotContain("\"lines\":[]");
        assertThat(session.traces().getFirst().outcome()).isEqualTo(LocatingTraceOutcome.ERROR);
        assertThat(manager.hasReads()).isFalse();
    }

    @Test
    void toolErrorDetailHasTypeAndMessageWithoutOutsidePath() throws Exception {
        Path secret = temp.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"../secret.txt\"}"));

        String detail = session.traces().getFirst().detailJson();
        assertThat(session.traces().getFirst().outcome()).isEqualTo(LocatingTraceOutcome.ERROR);
        assertThat(detail).contains("IllegalArgumentException");
        assertThat(detail).contains("path rejected");
        assertThat(detail).doesNotContain(secret.toString());
        assertThat(detail).doesNotContain("TOP-SECRET");
        assertThat(detail).doesNotContain(temp.toString());
    }

    @Test
    void rejectedSubmitDetailNamesTheTriggeredRule() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        manager.executeToolCalls(prompt(), toolCall("c1", "submit", "{\"paths\":[\"src/Missing.java\"]}"));

        String detail = session.traces().getFirst().detailJson();
        assertThat(session.traces().getFirst().outcome()).isEqualTo(LocatingTraceOutcome.ERROR);
        assertThat(detail).contains("does not exist");
        assertThat(detail).doesNotContain("class Foo");
    }

    @Test
    void oversizeDetailIsClippedWithinCheckLimit() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);
        String huge = "x".repeat(10_000);

        manager.executeToolCalls(prompt(), toolCall("c1", "search", "{\"pattern\":\"" + huge + "\"}"));

        String detail = session.traces().getFirst().detailJson();
        assertThat(detail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
        assertThat(detail).contains("truncated");
        JsonMapper.shared().readTree(detail);
    }

    @Test
    void sessionRoundCountIsSeparateFromToolCallCount() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationBudget budget = new LocalizationBudget(25, Duration.ofMinutes(5), T0);
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                workspaceTools(), session, budget, Clock.fixed(T0, ZoneOffset.UTC));

        manager.executeToolCalls(
                prompt(),
                toolCalls(
                        tc("c1", "search", "{\"pattern\":\"Foo\"}"),
                        tc("c2", "list", "{\"path\":\".\"}"),
                        tc("c3", "read", "{\"path\":\"src/Foo.java\"}")));

        assertThat(manager.executeCalls()).isEqualTo(1);
        assertThat(budget.calls()).isEqualTo(3);
        assertThat(session.traces()).hasSize(3);
    }

    @Test
    void searchReadSubmitWritesThreeTraceRowsInOrder() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        manager.executeToolCalls(prompt(), toolCall("c1", "search", "{\"pattern\":\"Foo\"}"));
        manager.executeToolCalls(
                prompt(), toolCall("c2", "read", "{\"path\":\"src/Foo.java\",\"startLine\":1,\"span\":10}"));
        assertThat(manager.hasReads()).isTrue();
        assertThat(manager.readPaths()).containsExactly("src/Foo.java");
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
    void submitOfUnreadPathStillSucceedsAndRecordsSubmittedNotRead() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult submit =
                manager.executeToolCalls(prompt(), toolCall("c1", "submit", "{\"paths\":[\"src/Foo.java\"]}"));

        assertThat(submit.returnDirect()).isTrue();
        assertThat(manager.finish()).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.traces().getFirst().detailJson()).contains("\"submitted_not_read\":1");
    }

    @Test
    void submitDetailCountsReadPathsThatWereNotSubmitted() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationTools tools = workspaceTools();
        Files.writeString(temp.resolve("ws/src/Bar.java"), "class Bar {}");
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                tools,
                session,
                new LocalizationBudget(25, Duration.ofMinutes(5), T0),
                Clock.fixed(T0, ZoneOffset.UTC));
        manager.executeToolCalls(prompt(), toolCall("c1", "read", "{\"path\":\"src/Foo.java\"}"));
        manager.executeToolCalls(prompt(), toolCall("c2", "read", "{\"path\":\"src/Bar.java\"}"));
        manager.executeToolCalls(prompt(), toolCall("c3", "submit", "{\"paths\":[\"src/Foo.java\"]}"));
        String detail = session.traces().getLast().detailJson();
        assertThat(detail).contains("\"read_not_submitted\":1");
        assertThat(detail).contains("\"submitted_not_read\":0");
    }

    @Test
    void directSubmitCommitsTextToolsWithoutExtraToolCalls() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult submit =
                manager.executeToolCalls(prompt(), toolCall("c1", "submit", "{\"paths\":[\"src/Foo.java\"]}"));
        LocatingCoordinator.Result result = manager.finish();

        assertThat(submit.returnDirect()).isTrue();
        assertThat(manager.executeCalls()).isEqualTo(1);
        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.origin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(session.committedSnapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactly("src/Foo.java");
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.SUBMIT);
    }

    @Test
    void missingSubmitPathReturnsErrorThenRetrySucceeds() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult first =
                manager.executeToolCalls(prompt(), toolCall("c1", "submit", "{\"paths\":[\"src/Missing.java\"]}"));
        ToolResponseMessage error = (ToolResponseMessage) first.conversationHistory().getLast();
        assertThat(first.returnDirect()).isFalse();
        assertThat(error.getResponses().getFirst().responseData()).contains("does not exist");

        ToolExecutionResult second =
                manager.executeToolCalls(prompt(), toolCall("c2", "submit", "{\"paths\":[\"src/Foo.java\"]}"));
        assertThat(second.returnDirect()).isTrue();
        assertThat(session.traces()).extracting(LocatingTraceStep::kind)
                .containsExactly(LocatingStepKind.SUBMIT, LocatingStepKind.SUBMIT);
        assertThat(session.traces()).extracting(LocatingTraceStep::outcome)
                .containsExactly(LocatingTraceOutcome.ERROR, LocatingTraceOutcome.OK);
        assertThat(manager.finish()).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
    }

    @Test
    void threeFailedSubmitsTruncateToReadFiles() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        manager.executeToolCalls(prompt(), toolCall("c0", "read", "{\"path\":\"src/Foo.java\"}"));
        ToolExecutionResult third = null;
        for (int i = 1; i <= 3; i++) {
            third = manager.executeToolCalls(
                    prompt(), toolCall("c" + i, "submit", "{\"paths\":[\"src/Missing" + i + ".java\"]}"));
        }
        LocatingCoordinator.Result result = manager.finish();

        assertThat(third.returnDirect()).isTrue();
        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.claim().state()).isEqualTo(RunState.GENERATING);
        assertThat(session.committedSnapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactly("src/Foo.java");
        assertThat(session.traces().stream().filter(step -> step.kind() == LocatingStepKind.SUBMIT))
                .hasSize(3)
                .allMatch(step -> step.outcome() == LocatingTraceOutcome.ERROR);
    }

    @Test
    void submitRejectsMoreThanTwelvePathsAndOversizedPayload() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);
        StringBuilder tooMany = new StringBuilder("{\"paths\":[");
        for (int i = 0; i < 13; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("\"src/p").append(i).append(".java\"");
        }
        tooMany.append("]}");

        ToolExecutionResult count =
                manager.executeToolCalls(prompt(), toolCall("c1", "submit", tooMany.toString()));
        assertThat(((ToolResponseMessage) count.conversationHistory().getLast())
                        .getResponses()
                        .getFirst()
                        .responseData())
                .contains("at most 12");

        Path workspace = temp.resolve("ws");
        StringBuilder paths = new StringBuilder("{\"paths\":[");
        for (int i = 1; i <= 5; i++) {
            Files.writeString(workspace.resolve("src/big" + i + ".txt"), "x".repeat(60_000));
            if (i > 1) {
                paths.append(',');
            }
            paths.append("\"src/big").append(i).append(".txt\"");
        }
        paths.append("]}");
        ToolExecutionResult bytes = manager.executeToolCalls(prompt(), toolCall("c2", "submit", paths.toString()));
        assertThat(((ToolResponseMessage) bytes.conversationHistory().getLast())
                        .getResponses()
                        .getFirst()
                        .responseData())
                .contains("256");
        assertThat(count.returnDirect()).isFalse();
        assertThat(bytes.returnDirect()).isFalse();
    }

    @Test
    void threeFailedSubmitsWithZeroReadsFailsAsLocatingNoContext() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);
        for (int i = 1; i <= 3; i++) {
            manager.executeToolCalls(
                    prompt(), toolCall("c" + i, "submit", "{\"paths\":[\"src/Missing" + i + ".java\"]}"));
        }
        LocatingCoordinator.Result result = manager.finish();

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(((LocatingCoordinator.Result.RunFailed) result).details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.LOCATING_NO_CONTEXT);
        assertThat(session.traces().stream().filter(step -> step.kind() == LocatingStepKind.SUBMIT))
                .hasSize(3)
                .allMatch(step -> step.outcome() == LocatingTraceOutcome.ERROR);
        assertThat(session.traces()).noneMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED);
    }

    @Test
    void emptySubmitFailsAsLocatingNoContextWithOkOutcome() throws Exception {
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = manager(session, 25);

        ToolExecutionResult submit = manager.executeToolCalls(prompt(), toolCall("c1", "submit", "{\"paths\":[]}"));
        LocatingCoordinator.Result result = manager.finish();

        assertThat(submit.returnDirect()).isTrue();
        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(((LocatingCoordinator.Result.RunFailed) result).details().failure().orElseThrow().category())
                .isEqualTo(FailureCategory.LOCATING_NO_CONTEXT);
        assertThat(session.traces()).extracting(LocatingTraceStep::kind).containsExactly(LocatingStepKind.SUBMIT);
        assertThat(session.traces()).extracting(LocatingTraceStep::outcome).containsExactly(LocatingTraceOutcome.OK);
        assertThat(session.traces()).noneMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED);
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

    private static JsonNode schema(List<ToolDefinition> definitions, String name) {
        String raw = definitions.stream()
                .filter(definition -> name.equals(definition.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema();
        return JsonMapper.shared().readTree(raw);
    }

    private static List<String> required(JsonNode schema) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : schema.get("required")) {
            names.add(item.asString());
        }
        return names;
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
        return toolCalls(tc(id, name, args));
    }

    private static ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(calls))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage.ToolCall tc(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private static ToolResponseMessage lastToolResponse(ToolExecutionResult result) {
        return (ToolResponseMessage) result.conversationHistory().getLast();
    }

    private static List<String> toolResponseIds(ToolExecutionResult result) {
        return lastToolResponse(result).getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::id)
                .toList();
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

    private static final class CountingTools implements LocalizationTools {
        private final LocalizationTools inner;
        private int searches;
        private int reads;

        private CountingTools(LocalizationTools inner) {
            this.inner = inner;
        }

        @Override
        public SearchHits search(String pattern, String pathGlob) {
            searches++;
            return inner.search(pattern, pathGlob);
        }

        @Override
        public DirectoryListing list(String path) {
            return inner.list(path);
        }

        @Override
        public FileSlice read(String path, Integer startLine, Integer span) {
            reads++;
            return inner.read(path, startLine, span);
        }

        @Override
        public SubmitDecision validateSubmit(List<String> paths) {
            return inner.validateSubmit(paths);
        }
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

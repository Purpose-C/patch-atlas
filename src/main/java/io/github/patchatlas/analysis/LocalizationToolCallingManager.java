package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.LocatingCoordinator;
import io.github.patchatlas.run.LocatingRunSession;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceOutcome;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.RunFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 定位阶段工具执行器：分派工具、记轨迹、数预算、决定是否终止循环。
 */
public final class LocalizationToolCallingManager implements ToolCallingManager {

    public static final String SPIKE_TOOL = "ping";
    public static final String SEARCH = "search";
    public static final String LIST = "list";
    public static final String READ = "read";
    public static final String SUBMIT = "submit";
    public static final int MAX_PARALLEL_TOOL_CALLS = 8;

    private final AtomicInteger executeCalls = new AtomicInteger();
    private final LocalizationTools tools;
    private final LocatingRunSession session;
    private final LocalizationBudget budget;
    private final Clock clock;
    private final Map<String, String> readContents = new LinkedHashMap<>();
    private List<SourceSnapshot> acceptedSubmit;
    private int submitFailures;
    private int seq;

    public LocalizationToolCallingManager() {
        this.tools = null;
        this.session = null;
        this.budget = null;
        this.clock = Clock.systemUTC();
    }

    public LocalizationToolCallingManager(
            LocalizationTools tools, LocatingRunSession session, LocalizationBudget budget) {
        this(tools, session, budget, Clock.systemUTC());
    }

    public LocalizationToolCallingManager(
            LocalizationTools tools,
            LocatingRunSession session,
            LocalizationBudget budget,
            Clock clock) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.session = Objects.requireNonNull(session, "session");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
        session.beginTrace();
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        Objects.requireNonNull(options, "options");
        if (tools == null) {
            return List.of(pingDefinition());
        }
        return locatingToolDefinitions();
    }

    /** 发给模型的定位工具 schema；ChatClient 回调必须用同一份，不能另写空 schema。 */
    public static List<ToolDefinition> locatingToolDefinitions() {
        return List.of(
                definition(
                        SEARCH,
                        "Search files",
                        "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},\"pathGlob\":{\"type\":\"string\"}},\"required\":[\"pattern\"]}"),
                definition(
                        LIST,
                        "List a directory",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
                definition(
                        READ,
                        "Read a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"startLine\":{\"type\":\"integer\"},\"span\":{\"type\":\"integer\"}},\"required\":[\"path\"]}"),
                definition(
                        SUBMIT,
                        "Submit selected paths and stop locating",
                        "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"paths\"]}"));
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(response, "response");
        int n = executeCalls.incrementAndGet();
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        AssistantMessage assistant = response.getResult().getOutput();
        history.add(assistant);
        List<AssistantMessage.ToolCall> calls = requireBoundedToolCalls(assistant);
        if (tools == null) {
            List<ToolResponseMessage.ToolResponse> spike = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                spike.add(responseOf(call.id(), SPIKE_TOOL, "{\"ok\":true}"));
            }
            history.add(toolResponses(spike));
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(n >= 2)
                    .build();
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean terminate = false;
        for (AssistantMessage.ToolCall call : calls) {
            Instant now = clock.instant();
            if (!budget.remaining(now)) {
                session.appendTrace(LocatingTraceStep.of(
                        seq++,
                        LocatingStepKind.BUDGET_EXHAUSTED,
                        LocatingTraceOutcome.OK,
                        ".",
                        budget.callsExhausted() ? "CALLS" : "CLOCK",
                        LocatingTraceDetails.budget(
                                budget.callsExhausted(), budget.calls(), budget.maxCalls())));
                responses.add(responseOf(call.id(), call.name(), errorJson("budget exhausted")));
                terminate = true;
                continue;
            }
            budget.consume();
            String name = call.name();
            String args = call.arguments();
            if (SUBMIT.equals(name)) {
                CallResult submit = executeSubmit(call, args);
                responses.add(submit.response());
                if (submit.terminate()) {
                    terminate = true;
                }
                continue;
            }
            String body;
            LocatingTraceOutcome outcome = LocatingTraceOutcome.OK;
            String detail;
            try {
                body = dispatch(name, args);
                detail = LocatingTraceDetails.fromToolResult(name, args, body);
            } catch (RuntimeException ex) {
                outcome = LocatingTraceOutcome.ERROR;
                body = errorJson("tool rejected");
                detail = LocatingTraceDetails.error(ex);
            }
            session.appendTrace(LocatingTraceStep.of(
                    seq++, kindOf(name), outcome, subjectOf(name, args), name, detail));
            responses.add(responseOf(call.id(), name, body));
        }
        history.add(toolResponses(responses));
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(terminate)
                .build();
    }

    public int executeCalls() {
        return executeCalls.get();
    }

    public boolean hasReads() {
        return !readContents.isEmpty();
    }

    public List<String> readPaths() {
        return List.copyOf(readContents.keySet());
    }

    public List<SourceSnapshot> readSnapshots() {
        List<SourceSnapshot> snapshots = new ArrayList<>(readContents.size());
        for (Map.Entry<String, String> entry : readContents.entrySet()) {
            snapshots.add(new SourceSnapshot(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(snapshots);
    }

    public List<SourceSnapshot> contextSnapshots() {
        if (acceptedSubmit != null) {
            return List.copyOf(acceptedSubmit);
        }
        return readSnapshots();
    }

    /**
     * 循环结束后按 submit 或已 read 文件提交上下文；零文件则判定位失败。
     */
    public LocatingCoordinator.Result finish() {
        if (session == null) {
            throw new IllegalStateException("spike manager has no session");
        }
        List<SourceSnapshot> snapshots = contextSnapshots();
        if (snapshots.isEmpty()) {
            return new LocatingCoordinator.Result.RunFailed(session.fail(new RunFailure(
                    FailureStage.LOCATING,
                    FailureCategory.LOCATING_NO_CONTEXT,
                    "locating produced no readable context")));
        }
        return new LocatingCoordinator.Result.ContextCommitted(
                session.commitContext(ContextOrigin.TEXT_TOOLS, snapshots));
    }

    static ToolDefinition pingDefinition() {
        return definition(SPIKE_TOOL, "spike tool", "{\"type\":\"object\",\"properties\":{}}");
    }

    private String dispatch(String name, String args) {
        JsonNode node = args == null || args.isBlank()
                ? JsonMapper.shared().createObjectNode()
                : JsonMapper.shared().readTree(args);
        return switch (name) {
            case SEARCH -> {
                String pattern = text(node, "pattern");
                String glob = text(node, "pathGlob");
                yield JsonMapper.shared().writeValueAsString(tools.search(pattern, glob));
            }
            case LIST -> JsonMapper.shared().writeValueAsString(tools.list(text(node, "path")));
            case READ -> {
                String path = text(node, "path");
                Integer start = intOrNull(node, "startLine");
                Integer span = intOrNull(node, "span");
                LocalizationTools.FileSlice slice = tools.read(path, start, span);
                readContents.put(slice.path(), String.join("\n", slice.lines()));
                yield JsonMapper.shared().writeValueAsString(slice);
            }
            default -> throw new IllegalArgumentException("unknown tool");
        };
    }

    private CallResult executeSubmit(AssistantMessage.ToolCall call, String args) {
        LocalizationTools.SubmitDecision decision;
        try {
            decision = tools.validateSubmit(parsePaths(args));
        } catch (RuntimeException ex) {
            decision = LocalizationTools.SubmitDecision.reject("paths must be an array of strings");
        }
        if (!decision.accepted()) {
            submitFailures++;
            session.appendTrace(LocatingTraceStep.of(
                    seq++,
                    LocatingStepKind.SUBMIT,
                    LocatingTraceOutcome.ERROR,
                    subjectOf(SUBMIT, args),
                    SUBMIT,
                    LocatingTraceDetails.submitRejected(decision.error())));
            return new CallResult(
                    responseOf(call.id(), SUBMIT, errorJson(decision.error())), submitFailures >= 3);
        }
        acceptedSubmit = decision.snapshots();
        session.appendTrace(LocatingTraceStep.of(
                seq++,
                LocatingStepKind.SUBMIT,
                LocatingTraceOutcome.OK,
                subjectOf(SUBMIT, args),
                SUBMIT,
                LocatingTraceDetails.submitAccepted(decision.snapshots().size())));
        return new CallResult(responseOf(call.id(), SUBMIT, "{\"ok\":true}"), true);
    }

    private static List<String> parsePaths(String args) {
        JsonNode node = args == null || args.isBlank()
                ? JsonMapper.shared().createObjectNode()
                : JsonMapper.shared().readTree(args);
        JsonNode paths = node.get("paths");
        if (paths == null || paths.isNull()) {
            return List.of();
        }
        if (!paths.isArray()) {
            throw new IllegalArgumentException("paths must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode path : paths) {
            if (path == null || !path.isString()) {
                throw new IllegalArgumentException("paths must be an array of strings");
            }
            values.add(path.asString());
        }
        return List.copyOf(values);
    }

    private static String errorJson(String reason) {
        return JsonMapper.shared()
                .writeValueAsString(JsonMapper.shared().createObjectNode().put("error", reason));
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static LocatingStepKind kindOf(String name) {
        return switch (name) {
            case SEARCH -> LocatingStepKind.SEARCH;
            case LIST -> LocatingStepKind.LIST;
            case READ -> LocatingStepKind.READ;
            case SUBMIT -> LocatingStepKind.SUBMIT;
            default -> LocatingStepKind.SEARCH;
        };
    }

    private static String subjectOf(String name, String args) {
        try {
            JsonNode node = JsonMapper.shared().readTree(args == null ? "{}" : args);
            if (SUBMIT.equals(name)) {
                JsonNode paths = node.get("paths");
                if (paths != null && paths.isArray() && paths.size() > 0 && paths.get(0).isString()) {
                    return paths.get(0).asString();
                }
                return ".";
            }
            String path = text(node, "path");
            if (path != null && !path.isBlank()) {
                return path;
            }
            String glob = text(node, "pathGlob");
            return glob == null || glob.isBlank() ? "." : glob;
        } catch (RuntimeException ex) {
            return ".";
        }
    }

    private static ToolDefinition definition(String name, String description, String schema) {
        return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
    }

    private static ToolResponseMessage.ToolResponse responseOf(String id, String name, String body) {
        return new ToolResponseMessage.ToolResponse(id, name, body);
    }

    private static ToolResponseMessage toolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        return ToolResponseMessage.builder().responses(List.copyOf(responses)).build();
    }

    private static List<AssistantMessage.ToolCall> requireBoundedToolCalls(AssistantMessage assistant) {
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            throw new LocatingToolCallException("tool execution requested without tool calls");
        }
        if (calls.size() > MAX_PARALLEL_TOOL_CALLS) {
            List<String> names = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                names.add(call.name());
            }
            throw new LocatingToolCallException(
                    "parallel tool calls exceed limit: received "
                            + calls.size()
                            + " (max "
                            + MAX_PARALLEL_TOOL_CALLS
                            + ") "
                            + names);
        }
        return List.copyOf(calls);
    }

    private record CallResult(ToolResponseMessage.ToolResponse response, boolean terminate) {}
}

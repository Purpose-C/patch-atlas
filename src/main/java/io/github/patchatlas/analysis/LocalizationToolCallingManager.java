package io.github.patchatlas.analysis;

import java.util.ArrayList;
import java.util.List;
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

/**
 * 定位阶段自控工具执行器。当前只提供 {@code ping}，用来确认
 * {@code ToolCallAdvisor} 会转循环，并在 {@code returnDirect} 时停止。
 */
public final class LocalizationToolCallingManager implements ToolCallingManager {

    public static final String SPIKE_TOOL = "ping";

    private final AtomicInteger executeCalls = new AtomicInteger();

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        Objects.requireNonNull(options, "options");
        return List.of(pingDefinition());
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(response, "response");
        int n = executeCalls.incrementAndGet();
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        AssistantMessage assistant = response.getResult().getOutput();
        history.add(assistant);
        history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        firstToolCallId(assistant), SPIKE_TOOL, "{\"ok\":true}")))
                .build());
        // 第一次回给模型以证明循环会再打 HTTP；第二次 returnDirect 证明能 break。
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(n >= 2)
                .build();
    }

    public int executeCalls() {
        return executeCalls.get();
    }

    static ToolDefinition pingDefinition() {
        return ToolDefinition.builder()
                .name(SPIKE_TOOL)
                .description("spike tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
    }

    private static String firstToolCallId(AssistantMessage assistant) {
        if (assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
            return "call_1";
        }
        return assistant.getToolCalls().getFirst().id();
    }
}

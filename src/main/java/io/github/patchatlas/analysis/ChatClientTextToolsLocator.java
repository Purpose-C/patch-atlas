package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.LocatingCoordinator;
import io.github.patchatlas.run.LocatingRunSession;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 用 ChatClient + ToolCallAdvisor 跑定位文本工具循环。 */
public final class ChatClientTextToolsLocator implements LocatingCoordinator.TextToolsLoop {

    private final ChatModel chatModel;
    private final ChatOptions options;

    public ChatClientTextToolsLocator(ChatModel chatModel) {
        this(chatModel, null);
    }

    public ChatClientTextToolsLocator(ChatModel chatModel, ChatOptions options) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.options = options;
    }

    @Override
    public LocatingCoordinator.Result run(
            ClaimedRun claimed,
            GenerationInput input,
            LocatingRunSession session,
            Path workspace) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(workspace, "workspace");
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                new TextSearchTools(workspace), session, new LocalizationBudget());
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallAdvisor.builder().toolCallingManager(manager).build())
                .defaultToolCallbacks(LocalizationToolCallingManager.locatingToolDefinitions().stream()
                        .map(ChatClientTextToolsLocator::stub)
                        .toArray(ToolCallback[]::new));
        if (options != null) {
            clientBuilder.defaultOptions(options.mutate());
        }
        clientBuilder
                .build()
                .prompt()
                .user(input.issueTitle() + "\n" + input.issueBody())
                .call()
                .chatResponse();
        return manager.finish();
    }

    private static ToolCallback stub(ToolDefinition definition) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String functionInput) {
                throw new AssertionError("Advisor must dispatch via LocalizationToolCallingManager");
            }
        };
    }
}

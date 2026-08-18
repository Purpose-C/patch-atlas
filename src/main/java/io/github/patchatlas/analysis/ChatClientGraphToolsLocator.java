package io.github.patchatlas.analysis;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.LocatingCoordinator;
import io.github.patchatlas.run.LocatingRunSession;
import io.github.patchatlas.run.RunFailure;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 用 ChatClient + ToolCallAdvisor 跑定位图工具循环。建图失败硬失败，不回退到文本工具。 */
public final class ChatClientGraphToolsLocator implements LocatingCoordinator.GraphToolsLoop {

    public static final Duration GRAPH_BUILD_TIMEOUT = Duration.ofMinutes(5);

    private final ChatModel chatModel;
    private final ChatOptions options;
    private final int maxToolCalls;
    private final Duration wallClock;
    private final CodeGraphBuilder builder;
    private final Duration graphBuildTimeout;
    private final Path cacheRoot;

    public ChatClientGraphToolsLocator(
            ChatModel chatModel, ChatOptions options, LocalizationBudget budget, CodeGraphBuilder builder) {
        this(chatModel, options, budget, builder, GRAPH_BUILD_TIMEOUT, null);
    }

    public ChatClientGraphToolsLocator(
            ChatModel chatModel,
            ChatOptions options,
            LocalizationBudget budget,
            CodeGraphBuilder builder,
            Duration graphBuildTimeout,
            Path cacheRoot) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.options = options;
        Objects.requireNonNull(budget, "budget");
        this.maxToolCalls = budget.maxCalls();
        this.wallClock = budget.wallClock();
        this.builder = Objects.requireNonNull(builder, "builder");
        this.graphBuildTimeout = Objects.requireNonNull(graphBuildTimeout, "graphBuildTimeout");
        this.cacheRoot = cacheRoot;
    }

    /** 配置上限的只读视图；每次调用都是新实例，不含已消耗量。 */
    public LocalizationBudget budget() {
        return new LocalizationBudget(maxToolCalls, wallClock, Instant.now());
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
        String revision = input.generatorContext().buggyRevision();
        CodeGraphBuilder effective = builderFor(input);
        boolean cacheHit = effective instanceof CachingCodeGraphBuilder caching
                && caching.hasCachedGraph(revision);
        Instant started = Instant.now();
        CodeGraph graph;
        try {
            graph = buildGraph(effective, workspace, revision);
        } catch (RuntimeException ex) {
            return new LocatingCoordinator.Result.RunFailed(session.fail(new RunFailure(
                    FailureStage.LOCATING,
                    FailureCategory.LOCATING_TOOL_PROTOCOL_ERROR,
                    summarizeBuildFailure(ex))));
        }
        long durationMs = Duration.between(started, Instant.now()).toMillis();
        LocalizationBudget sessionBudget = new LocalizationBudget(maxToolCalls, wallClock, Instant.now());
        WorkspaceFileTools files = new WorkspaceFileTools(workspace);
        GraphDiscoveryTools discovery = new GraphDiscoveryTools(graph, workspace);
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                files, discovery, ContextOrigin.GRAPH_TOOLS, session, sessionBudget);
        manager.recordGraphBuild(durationMs, cacheHit);
        List<ToolDefinition> definitions = new ArrayList<>(discovery.definitions());
        definitions.addAll(LocalizationToolCallingManager.workspaceToolDefinitions());
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallAdvisor.builder().toolCallingManager(manager).build())
                .defaultToolCallbacks(definitions.stream()
                        .map(ChatClientGraphToolsLocator::stub)
                        .toArray(ToolCallback[]::new));
        if (options != null) {
            clientBuilder.defaultOptions(options.mutate());
        }
        clientBuilder
                .build()
                .prompt()
                .system(LocatingPrompt.graphTools())
                .user(input.issueTitle() + "\n" + input.issueBody())
                .call()
                .chatResponse();
        return manager.finish();
    }

    private CodeGraphBuilder builderFor(GenerationInput input) {
        if (cacheRoot == null) {
            return builder;
        }
        return new CachingCodeGraphBuilder(
                builder, cacheRoot, input.generatorContext().repositoryUrl());
    }

    private CodeGraph buildGraph(CodeGraphBuilder effective, Path workspace, String revision) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "code-graph-build");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return executor.submit(() -> effective.build(workspace, revision))
                    .get(graphBuildTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("graph build timed out");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("graph build failed", cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("graph build interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    private static String summarizeBuildFailure(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "graph build failed";
        }
        if (message.contains("timed out")) {
            return "graph build timed out";
        }
        return "graph build failed";
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

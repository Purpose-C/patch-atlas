package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.analysis.CodeGraph.Edge;
import io.github.patchatlas.analysis.CodeGraph.EdgeKind;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.InMemoryLocatingRunSession;
import io.github.patchatlas.run.LocalGitFixture;
import io.github.patchatlas.run.LocatingCoordinator;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunLease;
import io.github.patchatlas.run.RunPurpose;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TempCandidateWorkspaceFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 图工具接进定位：共享 read/submit、建图硬失败、轨迹记下 expand 边。 */
class GraphToolsLocatingTest {

    private static final Instant T0 = Instant.parse("2026-08-17T00:00:00Z");

    @TempDir
    Path temp;

    @Test
    void graphAndTextScaffoldsShareWorkspaceFileToolsAndEachExposeFourTools() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("A.java"), "class A {}");
        TextSearchTools text = new TextSearchTools(workspace);
        WorkspaceFileTools files = new WorkspaceFileTools(workspace);
        GraphDiscoveryTools graph = new GraphDiscoveryTools(new CodeGraph("rev", List.of(), List.of()), workspace);

        assertThat(text.workspaceTools().getClass()).isEqualTo(files.getClass());
        assertThat(text.workspaceTools().getClass()).isEqualTo(WorkspaceFileTools.class);
        LocalizationToolCallingManager textManager = new LocalizationToolCallingManager(
                text, newSession(), new LocalizationBudget());
        LocalizationToolCallingManager graphManager = new LocalizationToolCallingManager(
                files, graph, ContextOrigin.GRAPH_TOOLS, newSession(), new LocalizationBudget());
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        assertThat(textManager.resolveToolDefinitions(options))
                .extracting(ToolDefinition::name)
                .containsExactly("search", "list", "read", "submit");
        assertThat(graphManager.resolveToolDefinitions(options))
                .extracting(ToolDefinition::name)
                .containsExactly("find", "expand", "read", "submit");
    }

    @Test
    void graphBuildFailureIsProtocolErrorAndDoesNotInvokeTextTools() throws Exception {
        LocalGitFixture.Fixture fixture = LocalGitFixture.initWithExistingTest(temp.resolve("git"));
        Path root = Files.createDirectories(temp.resolve("ws-fail"));
        AtomicBoolean textCalled = new AtomicBoolean();
        LocatingCoordinator coordinator = new LocatingCoordinator(
                new TempCandidateWorkspaceFactory(root, LocalGitFixture.fetcher(fixture.originDir())),
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder(),
                (claimed, input, session, workspace) -> {
                    textCalled.set(true);
                    throw new AssertionError("must not fall back to text tools");
                },
                (claimed, input, session, workspace) -> new LocatingCoordinator.Result.RunFailed(session.fail(
                        new RunFailure(
                                FailureStage.LOCATING,
                                FailureCategory.LOCATING_TOOL_PROTOCOL_ERROR,
                                "graph build failed"))));
        InMemoryLocatingRunSession session = newSession();

        LocatingCoordinator.Result result = coordinator.run(
                locatingClaim(), input(fixture.buggySha()), session, RunPurpose.STANDARD, ContextOrigin.GRAPH_TOOLS);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(((LocatingCoordinator.Result.RunFailed) result)
                        .details()
                        .failure()
                        .orElseThrow()
                        .category())
                .isEqualTo(FailureCategory.LOCATING_TOOL_PROTOCOL_ERROR);
        assertThat(textCalled).isFalse();
        assertThat(session.origin()).isNull();
    }

    @Test
    void expandTraceDetailRecordsEdgeKindsAndConfidences() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("trace"));
        Files.writeString(workspace.resolve("Caller.java"), "class Caller {}");
        Node caller = new Node(
                "method:Caller#run", NodeKind.METHOD, "Caller#run", new SourceLocation("Caller.java", 2));
        Node callee = new Node(
                "method:Target#ping", NodeKind.METHOD, "Target#ping", new SourceLocation("Target.java", 4));
        Edge call = new Edge(
                EdgeKind.CALLS,
                ImpactConfidence.CONFIRMED,
                caller,
                callee,
                new SourceLocation("Caller.java", 3),
                null,
                List.of());
        GraphDiscoveryTools discovery =
                new GraphDiscoveryTools(new CodeGraph("rev", List.of(caller, callee), List.of(call)), workspace);
        InMemoryLocatingRunSession session = newSession();
        LocalizationToolCallingManager manager = new LocalizationToolCallingManager(
                new WorkspaceFileTools(workspace),
                discovery,
                ContextOrigin.GRAPH_TOOLS,
                session,
                new LocalizationBudget(),
                Clock.fixed(T0, ZoneOffset.UTC));

        manager.executeToolCalls(
                new Prompt("locate"),
                toolCall("c1", "expand", "{\"entity\":\"" + caller.id() + "\"}"));

        LocatingTraceStep step = session.traces().getFirst();
        assertThat(step.kind()).isEqualTo(LocatingStepKind.EXPAND);
        assertThat(step.detailJson()).contains("CALLS").contains("CONFIRMED");
        assertThat(step.detailJson()).doesNotContain("class Caller");
    }

    @Test
    void promptSkeletonIsSharedAndGraphSectionHasNoSearchOrOracle() {
        String text = LocatingPrompt.textTools();
        String graph = LocatingPrompt.graphTools();
        assertThat(text).isEqualTo(LocatingPrompt.skeleton(LocatingPrompt.TEXT_TOOL_SECTION));
        assertThat(graph).isEqualTo(LocatingPrompt.skeleton(LocatingPrompt.GRAPH_TOOL_SECTION));
        assertThat(text).contains("You are locating source files");
        assertThat(graph).contains("You are locating source files");
        assertThat(text).contains("search").doesNotContain("- find:");
        assertThat(graph).contains("find").contains("expand").doesNotContain("- search:");
        assertThat(text).doesNotContainIgnoringCase("oracle").doesNotContainIgnoringCase("fixedRevision");
        assertThat(graph).doesNotContainIgnoringCase("oracle").doesNotContainIgnoringCase("fixedRevision");
        assertThat(graph).doesNotContainIgnoringCase("human patch");
    }

    private static ChatResponse toolCall(String id, String name, String args) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(id, "function", name, args);
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static InMemoryLocatingRunSession newSession() {
        return new InMemoryLocatingRunSession(locatingClaim());
    }

    private static ClaimedRun locatingClaim() {
        return new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.LIVE,
                RunState.LOCATING,
                1,
                new RunLease(UUID.randomUUID(), "owner", Instant.now().plusSeconds(60)),
                0,
                0,
                Optional.empty());
    }

    private static GenerationInput input(String buggySha) {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        buggySha,
                        "",
                        "21"),
                "t",
                "b",
                List.of());
    }
}

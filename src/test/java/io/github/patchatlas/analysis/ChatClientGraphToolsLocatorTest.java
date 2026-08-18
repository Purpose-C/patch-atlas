package io.github.patchatlas.analysis;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.analysis.CodeGraph.Node;
import io.github.patchatlas.analysis.CodeGraph.NodeKind;
import io.github.patchatlas.analysis.CodeGraph.SourceLocation;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.InMemoryLocatingRunSession;
import io.github.patchatlas.run.LocatingCoordinator;
import io.github.patchatlas.run.LocatingStepKind;
import io.github.patchatlas.run.RunLease;
import io.github.patchatlas.run.RunState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;

class ChatClientGraphToolsLocatorTest {

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path temp;

    @Test
    void graphBuildFailureIsProtocolErrorAndDoesNotCallTheModel() throws Exception {
        Path workspace = workspaceWithFoo();
        ChatClientGraphToolsLocator locator = locator(
                new LocalizationBudget(),
                (ws, revision) -> {
                    throw new IllegalStateException("parse exploded");
                });
        InMemoryLocatingRunSession session = newSession();

        LocatingCoordinator.Result result = locator.run(claimed(), input(), session, workspace);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.RunFailed.class);
        assertThat(((LocatingCoordinator.Result.RunFailed) result)
                        .details()
                        .failure()
                        .orElseThrow()
                        .category())
                .isEqualTo(FailureCategory.LOCATING_TOOL_PROTOCOL_ERROR);
        assertThat(((LocatingCoordinator.Result.RunFailed) result)
                        .details()
                        .failure()
                        .orElseThrow()
                        .summary())
                .contains("graph build failed");
        assertThat(session.origin()).isNull();
        assertThat(wireMock.findAll(postRequestedFor(urlPathMatching(".*/chat/completions")))).isEmpty();
    }

    @Test
    void graphBuildTimeIsRecordedAndNotChargedToLocatingBudget() throws Exception {
        Path workspace = workspaceWithFoo();
        stubSubmit();
        CodeGraphBuilder slow = (ws, revision) -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return emptyGraph();
        };
        ChatClientGraphToolsLocator locator =
                locator(new LocalizationBudget(25, Duration.ofMillis(40), Instant.now()), slow);
        InMemoryLocatingRunSession session = newSession();

        LocatingCoordinator.Result result = locator.run(claimed(), input(), session, workspace);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.origin()).isEqualTo(ContextOrigin.GRAPH_TOOLS);
        assertThat(session.traces())
                .noneMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED);
        assertThat(session.traces())
                .anyMatch(step -> "GRAPH_BUILD".equals(step.reason())
                        && step.detailJson().contains("durationMs")
                        && step.detailJson().contains("\"cacheHit\":false"));
    }

    @Test
    void secondGraphBuildHitsCacheWithoutReparsing() throws Exception {
        Path workspace = workspaceWithFoo();
        stubSubmit();
        Path cache = Files.createDirectories(temp.resolve("graph-cache"));
        CountingBuilder inner = new CountingBuilder(emptyGraph());
        CachingCodeGraphBuilder caching =
                new CachingCodeGraphBuilder(inner, cache, "https://github.com/ex/repo.git");
        ChatClientGraphToolsLocator locator = locator(new LocalizationBudget(), caching);

        locator.run(claimed(), input(), newSession(), workspace);
        InMemoryLocatingRunSession second = newSession();
        LocatingCoordinator.Result result = locator.run(claimed(), input(), second, workspace);

        assertThat(result).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(inner.calls.get()).isEqualTo(1);
        assertThat(second.traces())
                .anyMatch(step -> "GRAPH_BUILD".equals(step.reason())
                        && step.detailJson().contains("\"cacheHit\":true"));
    }

    private ChatClientGraphToolsLocator locator(LocalizationBudget budget, CodeGraphBuilder builder) {
        ChatModel chatModel = OpenAiChatModelFactory.create("sk-test", "gpt-test", wireMock.baseUrl());
        return new ChatClientGraphToolsLocator(
                chatModel, OpenAiChatModelFactory.locatingChatOptions("gpt-test"), budget, builder);
    }

    private Path workspaceWithFoo() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws-" + UUID.randomUUID()));
        Path src = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");
        return workspace;
    }

    private void stubSubmit() {
        wireMock.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(okJson(
                        """
                        {
                          "id": "chatcmpl-1",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "gpt-test",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                  "name": "submit",
                                  "arguments": "{\\"paths\\":[\\"src/Foo.java\\"]}"
                                }
                              }]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                        }
                        """)));
    }

    private static CodeGraph emptyGraph() {
        return new CodeGraph(
                "a".repeat(40),
                List.of(new Node(
                        "file:src/Foo.java",
                        NodeKind.FILE,
                        "src/Foo.java",
                        new SourceLocation("src/Foo.java", 1))),
                List.of());
    }

    private static InMemoryLocatingRunSession newSession() {
        return new InMemoryLocatingRunSession(claimed());
    }

    private static ClaimedRun claimed() {
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

    private static GenerationInput input() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live", "https://github.com/ex/repo.git", null, null, "a".repeat(40), "", "21"),
                "NPE in Foo",
                "class Foo fails",
                List.of());
    }

    private static final class CountingBuilder implements CodeGraphBuilder {
        private final AtomicInteger calls = new AtomicInteger();
        private final CodeGraph graph;

        private CountingBuilder(CodeGraph graph) {
            this.graph = graph;
        }

        @Override
        public CodeGraph build(Path workspace, String revision) {
            calls.incrementAndGet();
            return graph;
        }
    }
}

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
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.ContextOrigin;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChatClientTextToolsLocatorTest {

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path temp;

    @Test
    void secondLocatingSessionStartsWithFullBudget() throws Exception {
        Path workspace = workspaceWithFoo();
        stubSubmit();
        ChatClientTextToolsLocator locator = locator(new LocalizationBudget(1, Duration.ofMinutes(5), Instant.now()));

        LocatingCoordinator.Result first = locator.run(claimed(), input(), newSession(), workspace);
        LocatingCoordinator.Result second = locator.run(claimed(), input(), newSession(), workspace);

        assertThat(first).isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(second)
                .as("second session must not inherit the first session's consumed budget")
                .isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
    }

    @Test
    void locatingRequestIncludesSystemPromptWithoutOracleHints() throws Exception {
        Path workspace = workspaceWithFoo();
        stubSubmit();
        locator(new LocalizationBudget()).run(claimed(), input(), newSession(), workspace);

        String requestBody = wireMock.findAll(postRequestedFor(urlPathMatching(".*/chat/completions")))
                .getFirst()
                .getBodyAsString();
        String system = systemContent(requestBody);

        assertThat(system).isNotBlank();
        assertThat(system).containsIgnoringCase("locat");
        assertThat(system).contains("submit");
        assertThat(system).doesNotContainIgnoringCase("fixedRevision");
        assertThat(system).doesNotContainIgnoringCase("fixed revision");
        assertThat(system).doesNotContainIgnoringCase("oracle");
        assertThat(system).doesNotContainIgnoringCase("human patch");
        assertThat(system).doesNotContainIgnoringCase("human fix");
    }

    @Test
    void locatingStartsAfterAssemblyWallClockHasElapsed() throws Exception {
        Path workspace = workspaceWithFoo();
        stubSubmit();
        Instant assembled = Instant.now().minus(Duration.ofMinutes(10));
        ChatClientTextToolsLocator locator =
                locator(new LocalizationBudget(25, Duration.ofMinutes(5), assembled));
        InMemoryLocatingRunSession session = newSession();

        LocatingCoordinator.Result result = locator.run(claimed(), input(), session, workspace);

        assertThat(result)
                .as("assembly-time deadline must not exhaust a later locating session")
                .isInstanceOf(LocatingCoordinator.Result.ContextCommitted.class);
        assertThat(session.traces())
                .noneMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED);
        assertThat(session.origin()).isEqualTo(ContextOrigin.TEXT_TOOLS);
        assertThat(session.committedSnapshots())
                .extracting(SourceSnapshot::relativePath)
                .containsExactly("src/Foo.java");
    }

    private ChatClientTextToolsLocator locator(LocalizationBudget budget) {
        ChatModel chatModel = OpenAiChatModelFactory.create("sk-test", "gpt-test", wireMock.baseUrl());
        return new ChatClientTextToolsLocator(
                chatModel, OpenAiChatModelFactory.locatingChatOptions("gpt-test"), budget);
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

    private static String systemContent(String requestBody) {
        JsonNode messages = JsonMapper.shared().readTree(requestBody).get("messages");
        StringBuilder system = new StringBuilder();
        if (messages != null) {
            for (JsonNode message : messages) {
                if ("system".equals(text(message, "role"))) {
                    system.append(text(message, "content"));
                }
            }
        }
        return system.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static GenerationInput input() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "live", "https://github.com/ex/repo.git", null, null, "a".repeat(40), "", "21"),
                "NPE in Foo",
                "class Foo fails",
                List.of());
    }
}

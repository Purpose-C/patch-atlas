package io.github.patchatlas.analysis;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 经 ToolCallAdvisor 注入 manager，确认循环会转、且 returnDirect 时 break。
 */
class LocalizationToolCallingManagerSpikeTest {

    private static final String COMPLETIONS = ".*/chat/completions";

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void advisorLoopsThenBreaksOnReturnDirect() {
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("loop")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson(toolCallCompletion("call_1")))
                .willSetStateTo("second"));
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("loop")
                .whenScenarioStateIs("second")
                .willReturn(okJson(toolCallCompletion("call_2")))
                .willSetStateTo("done"));
        wireMock.stubFor(post(urlPathMatching(COMPLETIONS))
                .inScenario("loop")
                .whenScenarioStateIs("done")
                .willReturn(okJson(toolCallCompletion("call_3"))));

        LocalizationToolCallingManager manager = new LocalizationToolCallingManager();
        ChatModel chatModel = OpenAiChatModelFactory.create("sk-test", "gpt-test", wireMock.baseUrl());
        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallAdvisor.builder().toolCallingManager(manager).build())
                .defaultToolCallbacks(pingCallback())
                .build();

        client.prompt().user("use ping").call().chatResponse();

        int httpCalls = wireMock.findAll(postRequestedFor(urlPathMatching(COMPLETIONS))).size();
        assertThat(manager.executeCalls()).isGreaterThanOrEqualTo(1);
        assertThat(httpCalls).isGreaterThanOrEqualTo(2);
        assertThat(httpCalls)
                .as("returnDirect on second execute must prevent a third HTTP call")
                .isEqualTo(2);
        assertThat(manager.executeCalls()).isEqualTo(2);
    }

    private static ToolCallback pingCallback() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return LocalizationToolCallingManager.pingDefinition();
            }

            @Override
            public String call(String functionInput) {
                throw new AssertionError("Advisor must dispatch via LocalizationToolCallingManager");
            }
        };
    }

    private static String toolCallCompletion(String callId) {
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
                            "function": { "name": "ping", "arguments": "{}" }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ],
                  "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                }
                """
                .formatted(callId, callId);
    }
}

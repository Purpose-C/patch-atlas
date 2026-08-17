package io.github.patchatlas.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.model.ChatModel;

class SpringAiHttpContractTest {

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void successfulChatCompletionProducesDraft() {
        wireMock.stubFor(post(urlPathMatching(".*/chat/completions")).willReturn(okJson(chatCompletion(
                "{\"patchText\":\"diff --git a/x b/x\\n+line\\n\",\"targetClass\":\"c.T\",\"targetMethod\":\"m\"}",
                "stop",
                10,
                20,
                30))));

        ChatModel model = OpenAiChatModelFactory.create("sk-test", "gpt-test", wireMock.baseUrl());
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);

        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));

        assertThat(result).isInstanceOf(GenerationResult.GeneratedDraft.class);
        var draft = (GenerationResult.GeneratedDraft) result;
        assertThat(draft.draft().targetTest()).isEqualTo(new TargetTest("c.T", "m"));
        assertThat(draft.usage()).contains(new ModelUsage(10, 20, 30));
    }

    static String chatCompletion(String contentJson, String finishReason, int prompt, int completion, int total) {
        String escaped = contentJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "gpt-test",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "%s" },
                      "finish_reason": "%s"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": %d,
                    "completion_tokens": %d,
                    "total_tokens": %d
                  }
                }
                """
                .formatted(escaped, finishReason, prompt, completion, total);
    }

    static GenerationInput sampleInput() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "a".repeat(40),
                        "",
                        "21"),
                "t",
                "b",
                List.of());
    }
}

package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;

class OpenAiChatModelFactoryTest {

    @Test
    void candidateDraftSchemaConstrainsExactlyThreeStringFields() {
        String schema = OpenAiChatModelFactory.CANDIDATE_DRAFT_JSON_SCHEMA;
        assertThat(schema).contains("\"type\": \"object\"");
        assertThat(schema).contains("\"patchText\"");
        assertThat(schema).contains("\"targetClass\"");
        assertThat(schema).contains("\"targetMethod\"");
        assertThat(schema).contains("\"required\"");
        assertThat(schema).contains("\"additionalProperties\": false");
        // 恰好三字段：无第四 property 名
        assertThat(schema.lines().filter(l -> l.contains("\"type\": \"string\"")).count())
                .isEqualTo(3);
    }

    @Test
    void responseFormatBuilderAcceptsNativeJsonSchema() {
        OpenAiChatModel.ResponseFormat format = OpenAiChatModel.ResponseFormat.builder()
                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(OpenAiChatModelFactory.CANDIDATE_DRAFT_JSON_SCHEMA)
                .build();
        assertThat(format.getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
        assertThat(format.getJsonSchema()).isEqualTo(OpenAiChatModelFactory.CANDIDATE_DRAFT_JSON_SCHEMA);
    }

    @Test
    void benchmarkOptionsFreezeTemperatureAndCompletionLimit() {
        var options = OpenAiChatModelFactory.chatOptions("gpt-4.1-mini-2025-04-14");

        assertThat(options.getTemperature()).isZero();
        assertThat(options.getMaxCompletionTokens())
                .isEqualTo(OpenAiChatModelFactory.MAX_COMPLETION_TOKENS);
        assertThat(options.getMaxCompletionTokens()).isEqualTo(32768);
        assertThat(options.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(300));
        assertThat(options.getModel()).isEqualTo("gpt-4.1-mini-2025-04-14");
    }

    @Test
    void createBuildsChatModelWithoutThrowing() {
        org.springframework.ai.chat.model.ChatModel model =
                OpenAiChatModelFactory.create("test-key", "gpt-4.1-mini-2025-04-14");

        assertThat(model).isNotNull();
    }

    @Test
    void createRejectsBlankApiKey() {
        assertThatThrownBy(() -> OpenAiChatModelFactory.create("", "gpt-4.1-mini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey required");
    }

    @Test
    void createRejectsBlankModel() {
        assertThatThrownBy(() -> OpenAiChatModelFactory.create("test-key", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model required");
    }
}

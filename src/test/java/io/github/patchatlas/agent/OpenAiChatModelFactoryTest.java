package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;

class OpenAiChatModelFactoryTest {

    @Test
    void candidateDraftSchemaConstrainsExactlyOnePatchField() {
        String schema = OpenAiChatModelFactory.CANDIDATE_DRAFT_JSON_SCHEMA;
        assertThat(schema).contains("\"type\": \"object\"");
        assertThat(schema).contains("\"patch\"");
        assertThat(schema).doesNotContain("\"targetClass\"");
        assertThat(schema).doesNotContain("\"targetMethod\"");
        assertThat(schema).doesNotContain("\"patchText\"");
        assertThat(schema).contains("\"required\"");
        assertThat(schema).contains("\"additionalProperties\": false");
        assertThat(schema.lines().filter(l -> l.contains("\"type\": \"string\"")).count())
                .isEqualTo(1);
    }

    @Test
    void generationOptionsRequireSubmitDraftTool() {
        var options = OpenAiChatModelFactory.chatOptions("gpt-test");
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().getFirst().getToolDefinition().name())
                .isEqualTo(SubmitDraftTool.NAME);
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.TEXT);
        assertThat(options.getParallelToolCalls()).isFalse();
    }

    @Test
    void locatingOptionsForceTextAndKeepModel() {
        var options = OpenAiChatModelFactory.locatingChatOptions("agnes-2.5-flash");
        assertThat(options.getModel()).isEqualTo("agnes-2.5-flash");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getResponseFormat()).isNotNull();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.TEXT);
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

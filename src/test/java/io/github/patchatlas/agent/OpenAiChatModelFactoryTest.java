package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

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
}

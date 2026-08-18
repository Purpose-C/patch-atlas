package io.github.patchatlas.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 生成阶段唯一工具：提交候选测试补丁。参数须再经 {@link CandidateDraftParser} 校验。 */
public final class SubmitDraftTool {

    public static final String NAME = "submit_draft";

    public static final String INPUT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "patch": { "type": "string" }
              },
              "required": ["patch"],
              "additionalProperties": false
            }
            """
                    .strip();

    private SubmitDraftTool() {}

    public static ToolDefinition definition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description("Submit a unified diff that adds exactly one JUnit 5 @Test method.")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    public static ToolCallback stub() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition();
            }

            @Override
            public String call(String functionInput) {
                throw new IllegalStateException("submit_draft is parsed locally and must not be executed");
            }
        };
    }
}

package io.github.patchatlas.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Locale;

/**
 * 生成器装配：默认 Fake；显式 OPENAI 时装配 Spring AI {@link ChatModel} + {@link SpringAiTestGenerator}。
 *
 * <p>不依赖 Spring AI OpenAI 自动装配创建 client，避免 FAKE 路径校验 key。
 */
@Configuration
public class GeneratorConfiguration {

    @Bean
    @ConditionalOnMissingBean(TestGenerator.class)
    @ConditionalOnProperty(
            prefix = "patchatlas.generator",
            name = "type",
            havingValue = "FAKE",
            matchIfMissing = true)
    TestGenerator fakeTestGenerator() {
        return FakeTestGenerator.of(new GenerationResult.GenerationCallFailure(
                CallFailureCategory.MODEL_CONFIGURATION_ERROR,
                "no FakeTestGenerator script configured"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "patchatlas.generator", name = "type", havingValue = "OPENAI")
    ChatModel openAiChatModel(
            @Value("${patchatlas.generator.openai.model:}") String model,
            @Value("${patchatlas.generator.openai.base-url:https://api.openai.com}") String baseUrl) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is required for OPENAI generator");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "PATCHATLAS_OPENAI_MODEL / patchatlas.generator.openai.model is required");
        }
        return OpenAiChatModelFactory.create(key, model, baseUrl);
    }

    @Bean
    @ConditionalOnProperty(prefix = "patchatlas.generator", name = "type", havingValue = "OPENAI")
    TestGenerator springAiTestGenerator(
            ChatModel chatModel,
            @Value("${patchatlas.generator.openai.model:}") String model,
            @Value("${patchatlas.generator.openai.vendor:openai}") String vendor) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "PATCHATLAS_OPENAI_MODEL / patchatlas.generator.openai.model is required");
        }
        return new SpringAiTestGenerator(identityForVendor(vendor, model), chatModel);
    }

    public static GeneratorIdentity identityForVendor(String vendor, String model) {
        String v = vendor == null || vendor.isBlank() ? "openai" : vendor.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "openai" -> GeneratorIdentity.openai(model);
            case "agnes" -> GeneratorIdentity.agnes(model);
            default -> throw new IllegalStateException(
                    "patchatlas.generator.openai.vendor must be openai or agnes, got: " + vendor);
        };
    }
}

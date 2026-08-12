package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class SpringAiTestGeneratorTest {

    @Test
    void parsesStrictJsonDraftFromChatModel() {
        String json =
                """
                {"patchText":"diff --git a/x b/x\\n+line\\n","targetClass":"c.T","targetMethod":"m"}
                """;
        ChatModel model = prompt -> response(json, 10, 20, 30);
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);

        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GeneratedDraft.class);
        var draft = (GenerationResult.GeneratedDraft) result;
        assertThat(draft.draft().targetTest()).isEqualTo(new TargetTest("c.T", "m"));
        assertThat(draft.usage()).contains(new ModelUsage(10, 20, 30));
    }

    @Test
    void mapsAuthExceptionToCallFailureWithoutMessageLeak() {
        ChatModel model = prompt -> {
            throw unauthorized();
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        var fail = (GenerationResult.GenerationCallFailure) result;
        assertThat(fail.category()).isEqualTo(CallFailureCategory.MODEL_AUTHENTICATION_ERROR);
        assertThat(fail.summary()).contains("authentication");
        assertThat(fail.summary().toLowerCase()).doesNotContain("sk-");
    }

    @Test
    void stableSummaryStripsSecretsUrlsAndLongTokens() {
        String dirty =
                "api_key=sk-secret123 https://api.openai.com/v1/x token=abcdef0123456789abcdef0123456789";
        String cleaned = SpringAiTestGenerator.sanitizeBounded(dirty, "fallback");
        assertThat(cleaned).doesNotContain("sk-secret123");
        assertThat(cleaned).doesNotContain("https://");
        assertThat(cleaned).contains("[redacted]");
        assertThat(cleaned).contains("[url]");
        assertThat(cleaned.length()).isLessThanOrEqualTo(512);
    }

    @Test
    void includesFeedbackInPromptWhenPresent() {
        StringBuilder captured = new StringBuilder();
        ChatModel model = prompt -> {
            captured.append(prompt.getContents());
            return response(
                    "{\"patchText\":\"p\",\"targetClass\":\"c.T\",\"targetMethod\":\"m\"}", 0, 0, 0);
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationFeedback fb = new GenerationFeedback(
                GenerationFeedbackCategory.STRUCTURED_OUTPUT_INVALID, "bad envelope");
        generator.generate(GenerationRequest.feedbackOnly(sampleInput(), 2, fb));
        assertThat(captured.toString()).contains("feedback.category=STRUCTURED_OUTPUT_INVALID");
        assertThat(captured.toString()).contains("feedback.summary=bad envelope");
        assertThat(captured.toString()).doesNotContain("previousDraft");
    }

    @Test
    void retriesOnceOnTransportUnavailableThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 1) {
                throw new OpenAIIoException("timeout");
            }
            return response(
                    "{\"patchText\":\"p\",\"targetClass\":\"c.T\",\"targetMethod\":\"m\"}", 1, 1, 2);
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        assertThat(generator.generate(GenerationRequest.first(sampleInput(), 1)))
                .isInstanceOf(GenerationResult.GeneratedDraft.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryAuthenticationErrors() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw unauthorized();
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        assertThat(((GenerationResult.GenerationCallFailure) result).category())
                .isEqualTo(CallFailureCategory.MODEL_AUTHENTICATION_ERROR);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidRequestCategoryIsNotTransportRetryable() {
        assertThat(SpringAiTestGenerator.isTransportRetryable(badRequest())).isFalse();
        assertThat(SpringAiTestGenerator.mapException(badRequest()))
                .isEqualTo(CallFailureCategory.MODEL_CONFIGURATION_ERROR);
        assertThat(SpringAiTestGenerator.isTransportRetryable(rateLimit())).isTrue();
        assertThat(SpringAiTestGenerator.mapException(rateLimit()))
                .isEqualTo(CallFailureCategory.MODEL_UNAVAILABLE);
    }

    @Test
    void oversizedResponseMapsToStructuredOutputInvalidWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw new ResponseBodyTooLargeException(CandidateDraftParser.MAX_RESPONSE_BYTES);
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        var fail = (GenerationResult.GenerationCallFailure) result;
        assertThat(fail.category()).isEqualTo(CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(SpringAiTestGenerator.isTransportRetryable(
                        new ResponseBodyTooLargeException(1)))
                .isFalse();
        assertThat(SpringAiTestGenerator.mapException(
                        new ResponseBodyTooLargeException(1)))
                .isEqualTo(CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        // 即使被 OpenAIIoException 包装，仍按超限处理且不重试
        RuntimeException wrapped =
                new OpenAIIoException("io", new ResponseBodyTooLargeException(1));
        assertThat(SpringAiTestGenerator.mapException(wrapped))
                .isEqualTo(CallFailureCategory.STRUCTURED_OUTPUT_INVALID);
        assertThat(SpringAiTestGenerator.isTransportRetryable(wrapped)).isFalse();
    }

    @Test
    void http200RefusalMetadataMapsToModelRefusedNotStructuredInvalid() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of("refusal", "I cannot help with that"))
                .build();
        Generation generation = new Generation(
                message, ChatGenerationMetadata.builder().finishReason("stop").build());
        ChatModel model = prompt -> new ChatResponse(List.of(generation));
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        assertThat(((GenerationResult.GenerationCallFailure) result).category())
                .isEqualTo(CallFailureCategory.MODEL_REFUSED);
    }

    @Test
    void contentFilterFinishReasonMapsToModelRefused() {
        AssistantMessage message = new AssistantMessage("");
        Generation generation = new Generation(
                message,
                ChatGenerationMetadata.builder().finishReason("content_filter").build());
        ChatModel model = prompt -> new ChatResponse(List.of(generation));
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(((GenerationResult.GenerationCallFailure) result).category())
                .isEqualTo(CallFailureCategory.MODEL_REFUSED);
    }

    private static UnauthorizedException unauthorized() {
        return UnauthorizedException.builder().headers(Headers.builder().build()).build();
    }

    private static BadRequestException badRequest() {
        return BadRequestException.builder().headers(Headers.builder().build()).build();
    }

    private static RateLimitException rateLimit() {
        return RateLimitException.builder().headers(Headers.builder().build()).build();
    }

    private static ChatResponse response(String content, int in, int out, int total) {
        Generation generation = new Generation(new AssistantMessage(content));
        if (in == 0 && out == 0 && total == 0) {
            return new ChatResponse(List.of(generation));
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(in, out, total))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private static GenerationInput sampleInput() {
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

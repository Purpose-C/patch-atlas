package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.completions.CompletionUsage;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.run.RunEvents;
import io.github.patchatlas.replay.TargetTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    void retriesHttp429WithBoundedBackoffWithoutChangingLogicalAttempt() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> delays = new ArrayList<>();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() <= 3) {
                throw rateLimit();
            }
            return response(
                    "{\"patchText\":\"p\",\"targetClass\":\"c.T\",\"targetMethod\":\"m\"}", 1, 1, 2);
        };
        SpringAiTestGenerator generator = new SpringAiTestGenerator(
                GeneratorIdentity.openai("gpt-test"),
                model,
                new CandidateDraftParser(),
                delays::add);
        assertThat(generator.generate(GenerationRequest.first(sampleInput(), 1)))
                .isInstanceOf(GenerationResult.GeneratedDraft.class);
        assertThat(calls.get()).isEqualTo(4);
        assertThat(delays).containsExactly(
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20));
    }

    @Test
    void exhaustedHttp429RetriesMapToUnavailable() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw rateLimit();
        };
        SpringAiTestGenerator generator = new SpringAiTestGenerator(
                GeneratorIdentity.openai("gpt-test"),
                model,
                new CandidateDraftParser(),
                delay -> {});
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        assertThat(((GenerationResult.GenerationCallFailure) result).category())
                .isEqualTo(CallFailureCategory.MODEL_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void backoffDelayFollowsFiveTenTwentyFortySequence() {
        assertThat(SpringAiTestGenerator.backoffDelay(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(SpringAiTestGenerator.backoffDelay(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(SpringAiTestGenerator.backoffDelay(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(SpringAiTestGenerator.backoffDelay(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void exhaustedHttp429BackoffWaitsAtLeastSeventySeconds() {
        List<Duration> delays = new ArrayList<>();
        ChatModel model = prompt -> {
            throw rateLimit();
        };
        SpringAiTestGenerator generator = new SpringAiTestGenerator(
                GeneratorIdentity.openai("gpt-test"),
                model,
                new CandidateDraftParser(),
                delays::add);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        Duration total = delays.stream().reduce(Duration.ZERO, Duration::plus);
        assertThat(total).isGreaterThanOrEqualTo(Duration.ofSeconds(70));
    }

    @Test
    void backoffDelayCapsAtSixtySeconds() {
        assertThat(SpringAiTestGenerator.backoffDelay(4)).isEqualTo(Duration.ofSeconds(60));
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

    @Test
    void rejectsOversizedSerializedRequestBeforeCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return response("{}", 0, 0, 0);
        };
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        List<SourceSnapshot> sources = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sources.add(new SourceSnapshot(
                    "src/main/java/p/C" + i + ".java", "x".repeat(SourceSnapshot.MAX_CONTENT_BYTES)));
        }

        GenerationResult result = generator.generate(GenerationRequest.first(input(sources), 1));

        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        var failure = (GenerationResult.GenerationCallFailure) result;
        assertThat(failure.category()).isEqualTo(CallFailureCategory.MODEL_CONFIGURATION_ERROR);
        assertThat(failure.summary()).contains("192 KiB");
        assertThat(calls).hasValue(0);
    }

    @Test
    void recordsFinishReasonAndCompletionDetailsInUsageLog() {
        String body = "{\"patchText\":\"SENTINEL-MODEL-BODY\",\"targetClass\":\"c.T\",\"targetMethod\":\"m\"}";
        ChatModel model = prompt -> responseWithDiagnostics(body, "length", 10, 182, 192, 101L);
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RunEvents.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
            assertThat(result).isInstanceOf(GenerationResult.GeneratedDraft.class);
            CompletionDiagnostics diagnostics = result.completionDiagnostics().orElseThrow();
            assertThat(diagnostics.finishReason()).isEqualTo("length");
            assertThat(diagnostics.reasoningTokens()).isEqualTo("101");
            assertThat(diagnostics.textTokens()).isEqualTo("81");

            RunEvents.generationUsageRecorded(
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    10,
                    182,
                    192,
                    1,
                    diagnostics);
            ch.qos.logback.classic.spi.ILoggingEvent event = appender.list.getLast();
            java.util.Map<String, String> fields = usageFields(event);
            assertThat(fields)
                    .containsEntry("event", "generation.usage.recorded")
                    .containsEntry("finish_reason", "length")
                    .containsEntry("reasoning_tokens", "101")
                    .containsEntry("text_tokens", "81");
            assertThat(event.getFormattedMessage() + fields).doesNotContain("SENTINEL-MODEL-BODY");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void missingFinishReasonAndDetailsRecordUnknownWithoutThrowing() {
        ChatModel model = prompt -> response(
                "{\"patchText\":\"p\",\"targetClass\":\"c.T\",\"targetMethod\":\"m\"}", 10, 20, 30);
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        CompletionDiagnostics diagnostics = result.completionDiagnostics().orElseThrow();
        assertThat(diagnostics.finishReason()).isEqualTo("unknown");
        assertThat(diagnostics.reasoningTokens()).isEqualTo("unknown");
        assertThat(diagnostics.textTokens()).isEqualTo("unknown");
    }

    @Test
    void emptyContentStillRecordsLengthDiagnosticsAndOmitsModelBody() {
        ChatModel model = prompt -> responseWithDiagnostics("", "length", 10, 8192, 8202, 8192L);
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai("gpt-test"), model);
        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result).isInstanceOf(GenerationResult.GenerationCallFailure.class);
        var fail = (GenerationResult.GenerationCallFailure) result;
        assertThat(fail.summary()).isEqualTo("empty content");
        CompletionDiagnostics diagnostics = fail.completionDiagnostics().orElseThrow();
        assertThat(diagnostics.finishReason()).isEqualTo("length");
        assertThat(diagnostics.reasoningTokens()).isEqualTo("8192");
        assertThat(diagnostics.textTokens()).isEqualTo("0");
        assertThat(diagnostics.finishReason() + diagnostics.reasoningTokens() + diagnostics.textTokens())
                .doesNotContain("SENTINEL-MODEL-BODY");
    }

    @Test
    void unsafeFinishReasonIsUnknownAndDoesNotEchoModelBody() {
        CompletionDiagnostics diagnostics = SpringAiTestGenerator.completionDiagnostics(
                responseWithDiagnostics("{}", "stop SENTINEL-MODEL-BODY", 1, 1, 2, null));
        assertThat(diagnostics.finishReason()).isEqualTo("unknown");
        assertThat(diagnostics.finishReason()).doesNotContain("SENTINEL-MODEL-BODY");
    }

    private static java.util.Map<String, String> usageFields(ch.qos.logback.classic.spi.ILoggingEvent event) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (org.slf4j.event.KeyValuePair pair : event.getKeyValuePairs()) {
                map.put(pair.key, String.valueOf(pair.value));
            }
        }
        return map;
    }

    private static ChatResponse responseWithDiagnostics(
            String content, String finishReason, int in, int out, int total, Long reasoningTokens) {
        Generation generation = new Generation(
                new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        CompletionUsage.Builder nativeUsage = CompletionUsage.builder()
                .promptTokens(in)
                .completionTokens(out)
                .totalTokens(total);
        if (reasoningTokens != null) {
            nativeUsage.completionTokensDetails(
                    CompletionUsage.CompletionTokensDetails.builder()
                            .reasoningTokens(reasoningTokens)
                            .build());
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(in, out, total, nativeUsage.build()))
                .build();
        return new ChatResponse(List.of(generation), metadata);
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
        return input(List.of());
    }

    private static GenerationInput input(List<SourceSnapshot> sources) {
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
                sources);
    }
}

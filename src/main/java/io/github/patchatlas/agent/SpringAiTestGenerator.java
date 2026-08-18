package io.github.patchatlas.agent;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.completions.CompletionUsage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 单次模型调用 adapter：构造 prompt → Spring AI {@link ChatModel} → 严格 Draft 解析。
 *
 * <p>异常在边界映射为稳定枚举摘要，不回显供应商正文/URL/凭据。
 * 对 HTTP 429 与其它可恢复传输失败施加有界指数退避（最多 4 次重试，序列 5/10/20/40 秒，
 * 累计上限 75 秒、单次上限 60 秒），退避不消耗逻辑 Generation Attempt。
 */
public final class SpringAiTestGenerator implements TestGenerator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTestGenerator.class);

    static final int MAX_TRANSPORT_RETRIES = 4;
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    private static final Pattern SENSITIVE =
            Pattern.compile("(?i)(api[_-]?key|password|secret|token|authorization)\\s*[=:]\\s*\\S+");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");

    private final GeneratorIdentity identity;
    private final ChatModel chatModel;
    private final CandidateDraftParser draftParser;
    private final Sleeper sleeper;

    public SpringAiTestGenerator(GeneratorIdentity identity, ChatModel chatModel) {
        this(identity, chatModel, new CandidateDraftParser());
    }

    public SpringAiTestGenerator(
            GeneratorIdentity identity, ChatModel chatModel, CandidateDraftParser draftParser) {
        this(identity, chatModel, draftParser, SpringAiTestGenerator::sleepUninterruptibly);
    }

    SpringAiTestGenerator(
            GeneratorIdentity identity,
            ChatModel chatModel,
            CandidateDraftParser draftParser,
            Sleeper sleeper) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (!"openai".equals(identity.provider())
                && !"agnes".equals(identity.provider())
                && !"ollama".equals(identity.provider())) {
            throw new IllegalArgumentException(
                    "SpringAiTestGenerator requires openai, agnes or ollama provider");
        }
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.draftParser = Objects.requireNonNull(draftParser, "draftParser");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public GeneratorIdentity identity() {
        return identity;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!GenerationRequestBudget.fits(request)) {
            return new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.MODEL_CONFIGURATION_ERROR,
                    "serialized model request exceeds 192 KiB");
        }
        String system = buildSystemPrompt();
        String user = buildUserPrompt(request);
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));

        final ChatResponse response;
        try {
            response = callWithBoundedBackoff(prompt);
        } catch (MappedCallFailure mapped) {
            return new GenerationResult.GenerationCallFailure(
                    mapped.category(), stableSummary(mapped.category(), mapped.cause()));
        }

        Optional<ModelUsage> usage = extractUsage(response);
        Optional<CompletionDiagnostics> diagnostics = Optional.of(completionDiagnostics(response));
        if (isModelRefusal(response)) {
            return new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.MODEL_REFUSED,
                    "model refused or content filtered",
                    usage,
                    diagnostics);
        }
        SubmitDraftArguments extracted = extractSubmitDraftArguments(response);
        if (extracted instanceof SubmitDraftArguments.Missing) {
            return new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.STRUCTURED_OUTPUT_INVALID,
                    "model did not call submit_draft",
                    usage,
                    diagnostics);
        }
        if (extracted instanceof SubmitDraftArguments.Unexpected) {
            return new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.STRUCTURED_OUTPUT_INVALID,
                    "expected exactly one submit_draft call",
                    usage,
                    diagnostics);
        }
        String content = ((SubmitDraftArguments.Present) extracted).json();
        if (content.isBlank()) {
            return new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.STRUCTURED_OUTPUT_INVALID,
                    "empty content",
                    usage,
                    diagnostics);
        }
        // 边界再限长（HTTP 层已限制；此处拒绝超大 content 进入 parser 深解析）
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CandidateDraftParser.MAX_RESPONSE_BYTES) {
            return new GenerationResult.GenerationCallFailure(
                    CallFailureCategory.STRUCTURED_OUTPUT_INVALID,
                    "response size out of bounds",
                    usage,
                    diagnostics);
        }

        CandidateDraftParser.ParseResult parsed = draftParser.parse(content);
        return switch (parsed) {
            case CandidateDraftParser.ParseResult.Ok ok ->
                    new GenerationResult.GeneratedDraft(ok.draft(), usage, diagnostics);
            case CandidateDraftParser.ParseResult.Invalid invalid ->
                    new GenerationResult.GenerationCallFailure(
                            CallFailureCategory.STRUCTURED_OUTPUT_INVALID,
                            sanitizeBounded(invalid.reason(), "structured output invalid"),
                            usage,
                            diagnostics);
            case CandidateDraftParser.ParseResult.Rejected rejected ->
                    new GenerationResult.GenerationCallFailure(
                            CallFailureCategory.STRUCTURED_OUTPUT_INVALID,
                            sanitizeBounded(rejected.reason(), "structured output invalid"),
                            usage,
                            diagnostics);
        };
    }

    private ChatResponse callWithBoundedBackoff(Prompt prompt) throws MappedCallFailure {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_TRANSPORT_RETRIES; attempt++) {
            try {
                return chatModel.call(prompt);
            } catch (RuntimeException ex) {
                last = ex;
                CallFailureCategory category = mapException(ex);
                if (category != CallFailureCategory.MODEL_UNAVAILABLE
                        || !isTransportRetryable(ex)
                        || attempt == MAX_TRANSPORT_RETRIES) {
                    throw new MappedCallFailure(category, ex);
                }
                try {
                    Duration delay = backoffDelay(attempt);
                    log.atInfo()
                            .addKeyValue("event", "generation.transport.backoff")
                            .addKeyValue("failed_attempt_index", attempt)
                            .addKeyValue("delay_seconds", delay.toSeconds())
                            .log("transport backoff");
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new MappedCallFailure(CallFailureCategory.MODEL_UNAVAILABLE, interrupted);
                }
            }
        }
        throw new MappedCallFailure(CallFailureCategory.MODEL_UNAVAILABLE, last);
    }

    static Duration backoffDelay(int failedAttemptIndex) {
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), 5L << failedAttemptIndex);
        return Duration.ofSeconds(seconds);
    }

    private static void sleepUninterruptibly(Duration delay) throws InterruptedException {
        Thread.sleep(delay.toMillis());
    }

    static CallFailureCategory mapException(Throwable ex) {
        // 整条 cause 链优先：SDK 可能把超限包装成 IO，仍须 STRUCTURED_OUTPUT_INVALID
        if (hasCause(ex, ResponseBodyTooLargeException.class)) {
            return CallFailureCategory.STRUCTURED_OUTPUT_INVALID;
        }
        Throwable t = ex;
        while (t != null) {
            if (t instanceof UnauthorizedException || t instanceof PermissionDeniedException) {
                return CallFailureCategory.MODEL_AUTHENTICATION_ERROR;
            }
            if (t instanceof BadRequestException
                    || t instanceof NotFoundException
                    || t instanceof UnprocessableEntityException) {
                // 无效请求：不重试，按配置类终态（非 STRUCTURED_OUTPUT）
                return CallFailureCategory.MODEL_CONFIGURATION_ERROR;
            }
            if (t instanceof RateLimitException
                    || t instanceof InternalServerException
                    || t instanceof OpenAIIoException
                    || t instanceof OpenAIRetryableException) {
                return CallFailureCategory.MODEL_UNAVAILABLE;
            }
            if (t instanceof UnexpectedStatusCodeException unexpected) {
                int status = unexpected.statusCode();
                if (status == 401 || status == 403) {
                    return CallFailureCategory.MODEL_AUTHENTICATION_ERROR;
                }
                if (status == 429 || status >= 500) {
                    return CallFailureCategory.MODEL_UNAVAILABLE;
                }
                // 400/404 等：无效请求，不按可恢复 unavailable
                return CallFailureCategory.MODEL_CONFIGURATION_ERROR;
            }
            String name = t.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("contentfilter") || name.contains("refus")) {
                return CallFailureCategory.MODEL_REFUSED;
            }
            t = t.getCause();
        }
        return CallFailureCategory.MODEL_UNAVAILABLE;
    }

    static boolean isTransportRetryable(Throwable ex) {
        if (hasCause(ex, ResponseBodyTooLargeException.class)) {
            return false;
        }
        Throwable t = ex;
        while (t != null) {
            if (t instanceof RateLimitException
                    || t instanceof InternalServerException
                    || t instanceof OpenAIIoException
                    || t instanceof OpenAIRetryableException) {
                return true;
            }
            if (t instanceof UnexpectedStatusCodeException unexpected) {
                int status = unexpected.statusCode();
                return status == 429 || status >= 500;
            }
            if (t instanceof BadRequestException
                    || t instanceof NotFoundException
                    || t instanceof UnauthorizedException
                    || t instanceof PermissionDeniedException
                    || t instanceof UnprocessableEntityException) {
                return false;
            }
            t = t.getCause();
        }
        // 未知运行时：保守不重试，避免放大无效请求
        return false;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        Throwable t = ex;
        while (t != null) {
            if (type.isInstance(t)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * OpenAI HTTP 200 拒答：Spring AI 将 refusal / content_filter 写入 message 或 generation metadata。
     */
    static boolean isModelRefusal(ChatResponse response) {
        if (response == null) {
            return false;
        }
        Generation generation = response.getResult();
        if (generation == null) {
            return false;
        }
        ChatGenerationMetadata genMeta = generation.getMetadata();
        if (genMeta != null) {
            Set<String> filters = genMeta.getContentFilters();
            if (filters != null && !filters.isEmpty()) {
                return true;
            }
            String finish = genMeta.getFinishReason();
            if (finish != null) {
                String f = finish.toLowerCase(Locale.ROOT);
                if (f.contains("content_filter") || f.contains("content-filter")) {
                    return true;
                }
            }
            if (genMeta.containsKey("refusal") && nonBlankMeta(genMeta.get("refusal"))) {
                return true;
            }
        }
        AssistantMessage output = generation.getOutput();
        if (output != null) {
            Map<String, Object> meta = output.getMetadata();
            if (meta != null) {
                if (nonBlankMeta(meta.get("refusal"))) {
                    return true;
                }
                Object finish = meta.get("finishReason");
                if (finish != null) {
                    String f = finish.toString().toLowerCase(Locale.ROOT);
                    if (f.contains("content_filter") || f.contains("content-filter")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean nonBlankMeta(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() && nonBlankMeta(optional.get());
        }
        String s = value.toString();
        return s != null && !s.isBlank() && !"null".equalsIgnoreCase(s) && !"Optional.empty".equals(s);
    }

    private static SubmitDraftArguments extractSubmitDraftArguments(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return new SubmitDraftArguments.Missing();
        }
        List<AssistantMessage.ToolCall> calls = generation.getOutput().getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return new SubmitDraftArguments.Missing();
        }
        if (calls.size() != 1 || !SubmitDraftTool.NAME.equals(calls.getFirst().name())) {
            return new SubmitDraftArguments.Unexpected();
        }
        String arguments = calls.getFirst().arguments();
        return new SubmitDraftArguments.Present(arguments == null ? "" : arguments);
    }

    private sealed interface SubmitDraftArguments
            permits SubmitDraftArguments.Missing,
                    SubmitDraftArguments.Unexpected,
                    SubmitDraftArguments.Present {
        record Missing() implements SubmitDraftArguments {}

        record Unexpected() implements SubmitDraftArguments {}

        record Present(String json) implements SubmitDraftArguments {}
    }

    private static Optional<ModelUsage> extractUsage(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return Optional.empty();
        }
        Usage usage = response.getMetadata().getUsage();
        long in = usage.getPromptTokens() == null ? 0L : usage.getPromptTokens().longValue();
        long out =
                usage.getCompletionTokens() == null ? 0L : usage.getCompletionTokens().longValue();
        long total = usage.getTotalTokens() == null ? in + out : usage.getTotalTokens().longValue();
        if (in == 0 && out == 0 && total == 0) {
            return Optional.empty();
        }
        return Optional.of(new ModelUsage(in, out, total));
    }

    /**
     * 从 {@link ChatResponse} 读取 finish_reason 与 completion 明细。取不到记 {@code unknown}，不抛异常。
     * 不回显模型正文。
     */
    static CompletionDiagnostics completionDiagnostics(ChatResponse response) {
        try {
            String finish = finishReason(response);
            String reasoning = CompletionDiagnostics.UNKNOWN;
            String text = CompletionDiagnostics.UNKNOWN;
            Long reasoningTokens = reasoningTokens(response);
            Integer completionTokens = completionTokens(response);
            if (reasoningTokens != null) {
                reasoning = Long.toString(reasoningTokens);
                if (completionTokens != null
                        && completionTokens >= 0
                        && reasoningTokens >= 0
                        && completionTokens >= reasoningTokens) {
                    text = Long.toString(completionTokens - reasoningTokens);
                }
            }
            return new CompletionDiagnostics(finish, reasoning, text);
        } catch (RuntimeException ignored) {
            return CompletionDiagnostics.unknown();
        }
    }

    private static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return CompletionDiagnostics.UNKNOWN;
        }
        String finish = response.getResult().getMetadata().getFinishReason();
        return finish == null ? CompletionDiagnostics.UNKNOWN : finish;
    }

    private static Integer completionTokens(ChatResponse response) {
        if (response == null
                || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return null;
        }
        return response.getMetadata().getUsage().getCompletionTokens();
    }

    private static Long reasoningTokens(ChatResponse response) {
        if (response == null
                || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return null;
        }
        Object nativeUsage = response.getMetadata().getUsage().getNativeUsage();
        if (!(nativeUsage instanceof CompletionUsage completionUsage)) {
            return null;
        }
        return completionUsage
                .completionTokensDetails()
                .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                .orElse(null);
    }

    /** 稳定、有界、无秘密的摘要；不回显异常 message 原文。 */
    static String stableSummary(CallFailureCategory category, Throwable ex) {
        String base = switch (category) {
            case STRUCTURED_OUTPUT_INVALID -> "structured output invalid";
            case MODEL_CONFIGURATION_ERROR -> "model configuration error";
            case MODEL_AUTHENTICATION_ERROR -> "model authentication error";
            case MODEL_UNAVAILABLE -> "model unavailable";
            case MODEL_REFUSED -> "model refused or content filtered";
        };
        String cls = ex == null ? "" : ex.getClass().getSimpleName();
        String summary = cls.isEmpty() ? base : base + " (" + cls + ")";
        return sanitizeBounded(summary, base);
    }

    static String sanitizeBounded(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.replace('\0', ' ');
        s = SENSITIVE.matcher(s).replaceAll("$1=[redacted]");
        s = URL.matcher(s).replaceAll("[url]");
        s = LONG_HEX.matcher(s).replaceAll("[token]");
        s = s.trim();
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.length() > GenerationResult.GenerationCallFailure.MAX_SUMMARY_CHARS) {
            s = s.substring(0, GenerationResult.GenerationCallFailure.MAX_SUMMARY_CHARS);
        }
        return s;
    }

    static String buildSystemPrompt() {
        return """
                You generate a single Java regression test patch.
                Call submit_draft with key patch set to a git unified diff.
                The patch MUST begin with: diff --git a/<path> b/<path>
                For a new file: new file mode 100644, then --- /dev/null, then +++ b/<path>.
                For a modify: --- a/<path> then +++ b/<path>. Paths must agree.
                Do not emit a plain diff -u that starts with --- without the git header.
                Add exactly one JUnit 5 @Test method. Do not delete lines.
                No markdown fences, no commentary.
                Only add or modify files under src/test/java.
                """;
    }

    static String buildUserPrompt(GenerationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("attemptOrdinal=").append(request.attemptOrdinal()).append('\n');
        sb.append("issueTitle=\n").append(request.generationInput().issueTitle()).append('\n');
        sb.append("issueBody=\n").append(request.generationInput().issueBody()).append('\n');
        sb.append("buggyRevision=")
                .append(request.generationInput().generatorContext().buggyRevision())
                .append('\n');
        sb.append("modulePath=")
                .append(request.generationInput().generatorContext().modulePath())
                .append('\n');
        sb.append("sourceSnapshots=\n");
        request.generationInput()
                .sourceSnapshots()
                .forEach(s -> sb.append("--- ")
                        .append(s.relativePath())
                        .append(" ---\n")
                        .append(s.content())
                        .append('\n'));
        if (request.isCorrection()) {
            sb.append("previousDraft.patchText=\n")
                    .append(request.previousDraft().orElseThrow().patchText())
                    .append('\n');
        }
        if (request.hasFeedback()) {
            sb.append("feedback.category=")
                    .append(request.generationFeedback().orElseThrow().category())
                    .append('\n');
            sb.append("feedback.summary=")
                    .append(request.generationFeedback().orElseThrow().summary())
                    .append('\n');
        }
        return sb.toString();
    }

    private static final class MappedCallFailure extends Exception {
        private final CallFailureCategory category;
        private final Throwable cause;

        MappedCallFailure(CallFailureCategory category, Throwable cause) {
            super(category.name(), cause);
            this.category = category;
            this.cause = cause;
        }

        CallFailureCategory category() {
            return category;
        }

        @Override
        public Throwable getCause() {
            return cause;
        }

        Throwable cause() {
            return cause;
        }
    }
}

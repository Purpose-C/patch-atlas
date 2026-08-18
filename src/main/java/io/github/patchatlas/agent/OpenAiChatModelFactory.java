package io.github.patchatlas.agent;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;

/**
 * 显式构造 Spring AI {@link OpenAiChatModel}（不走自动装配校验 key）。
 *
 * <p>HTTP 响应体在 adapter 边界截断；SDK maxRetries=0，传输重试由 {@link SpringAiTestGenerator} 控制。
 */
public final class OpenAiChatModelFactory {

    /** 与 {@link CandidateDraftParser#MAX_RESPONSE_BYTES} 对齐的响应体上限。 */
    public static final int MAX_HTTP_BODY_BYTES = CandidateDraftParser.MAX_RESPONSE_BYTES;

    public static final String DEFAULT_BASE_URL = "https://api.openai.com";
    /** 单轮最坏约 4–5 分钟；须覆盖 reasoning 占 completion 额度后的完整输出。 */
    static final Duration TIMEOUT = Duration.ofSeconds(300);
    /**
     * 端点探针已接受该上限。原定过低的 completion 额度会在 reasoning 未完成时截断，
     * 产出空内容，那是测量误差而非能力信号。
     */
    public static final int MAX_COMPLETION_TOKENS = 32768;

    /**
     * Candidate Draft 的原生 JSON Schema（strict）。
     *
     * <p>Spring AI 固定 name=json_schema、strict=true；此处只提供 schema 本体。
     * 供应商层约束不能替代 {@link CandidateDraftParser} 本地校验。
     */
    static final String CANDIDATE_DRAFT_JSON_SCHEMA =
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

    private OpenAiChatModelFactory() {}

    public static ChatModel create(String apiKey, String modelName) {
        return create(apiKey, modelName, DEFAULT_BASE_URL);
    }

    /**
     * 仅使用原生 JSON Schema；兼容网关降级为 JSON_OBJECT 不在当前支持范围。
     *
     * @param baseUrl OpenAI 或兼容端点根（可含 {@code /v1}）
     */
    public static ChatModel create(String apiKey, String modelName, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey required");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("model required");
        }
        String resolvedBase = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
        if (resolvedBase.endsWith("/")) {
            resolvedBase = resolvedBase.substring(0, resolvedBase.length() - 1);
        }

        OpenAiHttpClientBuilderCustomizer bodyLimit = builder -> builder.interceptor(bodyLimitInterceptor());

        OpenAIClient syncClient = OpenAiSetup.setupSyncClient(
                resolvedBase,
                apiKey,
                null,
                null,
                null,
                null,
                false,
                false,
                modelName,
                TIMEOUT,
                0, // 传输重试由 SpringAiTestGenerator 做一次
                null,
                Map.of(),
                ObservationRegistry.NOOP,
                null,
                List.of(bodyLimit));

        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                resolvedBase,
                apiKey,
                null,
                null,
                null,
                null,
                false,
                false,
                modelName,
                TIMEOUT,
                0,
                null,
                Map.of(),
                ObservationRegistry.NOOP,
                null,
                List.of(bodyLimit));

        OpenAiChatOptions options = chatOptions(modelName);

        return OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .options(options)
                .build();
    }

    /** 定位循环专用：关闭并行、强制 TEXT，避免合并进 Candidate Draft 的 JSON Schema。 */
    public static OpenAiChatOptions locatingChatOptions() {
        return locatingChatOptions(null);
    }

    public static OpenAiChatOptions locatingChatOptions(String modelName) {
        OpenAiChatModel.ResponseFormat text = OpenAiChatModel.ResponseFormat.builder()
                .type(OpenAiChatModel.ResponseFormat.Type.TEXT)
                .build();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .parallelToolCalls(false)
                .responseFormat(text);
        if (modelName != null && !modelName.isBlank()) {
            builder.model(modelName);
        }
        return builder.build();
    }

    static OpenAiChatOptions chatOptions(String modelName) {
        OpenAiChatModel.ResponseFormat jsonSchema = OpenAiChatModel.ResponseFormat.builder()
                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(CANDIDATE_DRAFT_JSON_SCHEMA)
                .build();

        return OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                .timeout(TIMEOUT)
                .maxRetries(0)
                .responseFormat(jsonSchema)
                .build();
    }

    static Interceptor bodyLimitInterceptor() {
        return chain -> {
            Response response = chain.proceed(chain.request());
            ResponseBody body = response.body();
            if (body == null) {
                return response;
            }
            long declared = body.contentLength();
            if (declared > MAX_HTTP_BODY_BYTES) {
                body.close();
                throw new ResponseBodyTooLargeException(MAX_HTTP_BODY_BYTES);
            }
            BufferedSource source = body.source();
            // 对 chunked/未知长度：最多缓冲上限+1 字节
            if (!source.request(MAX_HTTP_BODY_BYTES + 1L)) {
                return response;
            }
            if (source.getBuffer().size() > MAX_HTTP_BODY_BYTES) {
                body.close();
                throw new ResponseBodyTooLargeException(MAX_HTTP_BODY_BYTES);
            }
            return response;
        };
    }
}

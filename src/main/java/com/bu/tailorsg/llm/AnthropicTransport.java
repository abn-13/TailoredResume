package com.bu.tailorsg.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.bu.tailorsg.config.TailorSgProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real {@link LlmTransport} backed by the official Anthropic Java SDK (non-streaming Messages API).
 *
 * <p>Structured output: the response format is constrained with {@code output_config.format}
 * (a JSON Schema). We use that rather than forced tool use because current models
 * (e.g. Sonnet 5.5) reject {@code tool_choice: any/tool}.
 */
@Component
public class AnthropicTransport implements LlmTransport, DisposableBean {

    /** The only place the key is read. Spec: env var ANTHROPIC_API_KEY, nothing else. */
    static final String API_KEY_ENV = "ANTHROPIC_API_KEY";

    private final JsonMapper jsonMapper;
    /** Null when no key is set; the app still runs and every LLM feature reports "not configured". */
    private final AnthropicClient client;

    @Autowired
    public AnthropicTransport(TailorSgProperties props, JsonMapper jsonMapper) {
        this(props, jsonMapper, System.getenv(API_KEY_ENV), null);
    }

    /** Test seam: lets a test point the real SDK client at a local fake server via {@code baseUrl}. */
    AnthropicTransport(TailorSgProperties props, JsonMapper jsonMapper, String apiKey, String baseUrl) {
        this.jsonMapper = jsonMapper;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            return;
        }
        // builder().apiKey(..) rather than fromEnv(): fromEnv() also picks up other credential sources
        // (auth tokens, CLI profiles), and this app is meant to use the env var only.
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(props.llm().timeout())
                .maxRetries(props.llm().maxRetries());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public LlmReply send(LlmRequest request) {
        if (client == null) {
            throw new LlmException(API_KEY_ENV + " is not set. Export it and restart the app.");
        }

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model()) // String overload: works for any model ID, typed constants lag releases
                .maxTokens(request.maxTokens())
                .system(request.system())
                .addUserMessageOfBlockParams(request.userBlocks().stream().map(AnthropicTransport::toSdkBlock).toList())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(request.effort()))
                        .format(JsonOutputFormat.builder().schema(toSdkSchema(request.apiSchema())).build())
                        .build());
        if (request.refusalFallback()) {
            // Server-side refusal fallback ("default" routing). If a safety classifier declines the request,
            // the API retries it on a fallback model instead of returning stop_reason=refusal.
            builder.putAdditionalHeader("anthropic-beta", REFUSAL_FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        MessageCreateParams params = builder.build();

        Message message;
        // Most specific first. The SDK has already retried 429/5xx/network errors maxRetries times.
        try {
            message = client.messages().create(params);
        } catch (UnauthorizedException e) {
            throw new LlmException("The API rejected " + API_KEY_ENV + " (401). Check the key.", e);
        } catch (PermissionDeniedException e) {
            throw new LlmException("This API key has no access to model " + request.model() + " (403).", e);
        } catch (NotFoundException e) {
            throw new LlmException("Model '" + request.model() + "' not found (404). Check tailorsg.models.* in application.yml.", e);
        } catch (RateLimitException e) {
            throw new LlmException("Rate limited by the API (429) even after retries. Try again shortly.", e);
        } catch (BadRequestException e) {
            throw new LlmException("The API rejected the request (400): " + e.getMessage(), e);
        } catch (AnthropicServiceException e) {
            throw new LlmException("Anthropic API error (HTTP " + e.statusCode() + "): " + e.getMessage(), e);
        } catch (AnthropicIoException e) {
            throw new LlmException("Network error talking to the Anthropic API: " + e.getMessage(), e);
        }

        // With adaptive thinking the content may also hold thinking blocks; we only want the text.
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(com.anthropic.models.messages.TextBlock::text) // qualified: LlmTransport.TextBlock is in scope here
                .collect(Collectors.joining());
        String stopReason = message.stopReason().map(StopReason::asString).orElse("unknown");
        return new LlmReply(text, stopReason, message.model().asString(), toUsage(message.usage()));
    }

    static final String REFUSAL_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    // Our block types are qualified (LlmTransport.TextBlock) because the SDK has its own TextBlock.
    private static ContentBlockParam toSdkBlock(LlmTransport.UserBlock block) {
        return switch (block) {
            case LlmTransport.TextBlock t -> {
                TextBlockParam.Builder text = TextBlockParam.builder().text(t.text());
                if (t.cacheable()) {
                    text.cacheControl(CacheControlEphemeral.builder().build());
                }
                yield ContentBlockParam.ofText(text.build());
            }
            case LlmTransport.ImageBlock i -> ContentBlockParam.ofImage(Base64ImageSource.builder()
                    .mediaType(Base64ImageSource.MediaType.of(i.mediaType())).data(i.base64()).build());
        };
    }

    private static TokenUsage toUsage(Usage usage) {
        return new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
    }

    /** Our schemas are Jackson 3 trees; the SDK wants a map of JsonValue (its own Jackson 2 world). */
    private JsonOutputFormat.Schema toSdkSchema(JsonNode schema) {
        Map<String, Object> asMap = jsonMapper.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        asMap.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close(); // releases OkHttp's connection pool and threads on shutdown
        }
    }
}

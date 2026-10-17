package com.bu.tailorsg.llm;

import com.bu.tailorsg.llm.LlmTransport.LlmReply;
import com.bu.tailorsg.llm.LlmTransport.LlmRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Anthropic SDK client against a tiny local HTTP server (JDK built-in), so we can
 * check the exact JSON we send and how we read the reply, without an API key or network.
 */
class AnthropicTransportTest {

    private static final String OK_RESPONSE = """
            {"id": "msg_test", "type": "message", "role": "assistant", "model": "claude-haiku-5-5",
             "content": [
               {"type": "thinking", "thinking": "", "signature": "sig"},
               {"type": "text", "text": "{\\"ok\\": true, \\"nonce\\": \\"a1b2c3\\", \\"message\\": \\"Hi\\"}"}
             ],
             "stop_reason": "end_turn", "stop_sequence": null,
             "usage": {"input_tokens": 42, "output_tokens": 17,
                       "cache_creation_input_tokens": 3, "cache_read_input_tokens": 5}}""";

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> betaHeader = new AtomicReference<>();
    private int status = 200;
    private String responseBody = OK_RESPONSE;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            betaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicTransport transport() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AnthropicTransport(TestFixtures.props(), TestFixtures.JSON, "test-key", baseUrl);
    }

    private static LlmRequest pingRequest() {
        JsonNode schema = new ApiSchemaSanitizer().sanitize(
                TestFixtures.schemas().rawSchema("schema/llm/ping.schema.json"));
        return new LlmRequest("ping", "claude-haiku-5-5", "medium", 8000, "SYSTEM TEXT",
                LlmTransport.texts(List.of("block one", "block two")), schema, false, false);
    }

    @Test
    void sendsStructuredOutputRequestInTheApiShape() {
        transport().send(pingRequest());

        JsonNode body = TestFixtures.JSON.readTree(requestBody.get());
        assertThat(apiKeyHeader.get()).isEqualTo("test-key");
        assertThat(body.path("model").stringValue("")).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(8000);
        assertThat(body.path("system").stringValue("")).isEqualTo("SYSTEM TEXT");
        // Both user blocks go into ONE user message, in order
        assertThat(body.at("/messages/0/role").stringValue("")).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/text").stringValue("")).isEqualTo("block one");
        assertThat(body.at("/messages/0/content/1/text").stringValue("")).isEqualTo("block two");
        // Structured output + effort live under output_config
        assertThat(body.at("/output_config/effort").stringValue("")).isEqualTo("medium");
        assertThat(body.at("/output_config/format/type").stringValue("")).isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema/properties/nonce/type").stringValue("")).isEqualTo("string");
        assertThat(body.at("/output_config/format/schema/additionalProperties").booleanValue()).isFalse();
        // No forced tool use and no thinking override (both are 400s or unwanted on current models)
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("fallbacks")).isFalse();
    }

    @Test
    void writerRequestsOptIntoRefusalFallbackAndCacheTheFirstBlock() {
        JsonNode schema = new ApiSchemaSanitizer().sanitize(TestFixtures.schemas().rawSchema("schema/llm/ping.schema.json"));
        transport().send(new LlmRequest("tailor", "claude-sonnet-5-5", "high", 16000, "S",
                List.of(new LlmTransport.TextBlock("MASTER", true), new LlmTransport.ImageBlock("image/png", "aGk=")), schema, true,
                false));

        JsonNode body = TestFixtures.JSON.readTree(requestBody.get());
        assertThat(body.path("fallbacks").stringValue("")).isEqualTo("default");
        assertThat(betaHeader.get()).isEqualTo(AnthropicTransport.REFUSAL_FALLBACK_BETA);
        assertThat(body.at("/messages/0/content/0/cache_control/type").stringValue("")).isEqualTo("ephemeral");
        assertThat(body.at("/messages/0/content/1/type").stringValue("")).isEqualTo("image");
        assertThat(body.at("/messages/0/content/1/source/media_type").stringValue("")).isEqualTo("image/png");
    }

    @Test
    void promptsThatDisableThinkingSendThinkingDisabled() {
        LlmRequest base = pingRequest();
        transport().send(new LlmRequest("master-import-entries", base.model(), base.effort(), base.maxTokens(),
                base.system(), base.userBlocks(), base.apiSchema(), false, true));

        JsonNode body = TestFixtures.JSON.readTree(requestBody.get());
        assertThat(body.at("/thinking/type").stringValue("")).isEqualTo("disabled");
        assertThat(body.at("/output_config/effort").stringValue("")).isEqualTo("medium"); // effort still applies
    }

    @Test
    void readsTextUsageAndStopReasonAndSkipsThinkingBlocks() {
        LlmReply reply = transport().send(pingRequest());

        assertThat(reply.text()).isEqualTo("{\"ok\": true, \"nonce\": \"a1b2c3\", \"message\": \"Hi\"}");
        assertThat(reply.stopReason()).isEqualTo("end_turn");
        assertThat(reply.modelUsed()).isEqualTo("claude-haiku-5-5");
        assertThat(reply.usage()).isEqualTo(new TokenUsage(42, 17, 3, 5));
    }

    @Test
    void unknownModelBecomesAReadableError() {
        status = 404;
        responseBody = """
                {"type": "error", "error": {"type": "not_found_error", "message": "model: claude-nope"}}""";

        assertThatThrownBy(() -> transport().send(pingRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining("tailorsg.models");
    }

    @Test
    void rejectedKeyBecomesAReadableErrorWithoutTheKey() {
        status = 401;
        responseBody = """
                {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}""";

        assertThatThrownBy(() -> transport().send(pingRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void withoutAKeyTheTransportIsNotConfiguredAndSaysWhy() {
        AnthropicTransport noKey = new AnthropicTransport(TestFixtures.props(), TestFixtures.JSON, "  ", null);

        assertThat(noKey.isConfigured()).isFalse();
        assertThatThrownBy(() -> noKey.send(pingRequest())).hasMessageContaining("ANTHROPIC_API_KEY");
    }
}

package com.bu.tailorsg.llm;

import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.LlmReply;
import com.bu.tailorsg.llm.LlmTransport.LlmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The validate-then-retry-once contract of {@link ClaudeClient}, using a scripted fake transport
 * (no network, no API key) and the real ping prompt and schema from src/main/resources.
 */
class ClaudeClientTest {

    private static final String VALID = """
            {"ok": true, "nonce": "a1b2c3", "message": "Hello from Claude"}""";
    /** Breaks the schema's local-only rules: nonce pattern and message maxLength. */
    private static final String INVALID = """
            {"ok": true, "nonce": "NOT-HEX", "message": "%s"}""".formatted("x".repeat(120));
    private static final TokenUsage USAGE = new TokenUsage(100, 50, 0, 0);

    private ScriptedTransport transport;
    private LlmUsageMeter meter;
    private ClaudeClient client;

    @BeforeEach
    void setUp() {
        transport = new ScriptedTransport();
        meter = new LlmUsageMeter(TestFixtures.props());
        client = new ClaudeClient(transport, TestFixtures.prompts(), TestFixtures.schemas(),
                new ApiSchemaSanitizer(), meter, TestFixtures.props(), TestFixtures.JSON);
    }

    @Test
    void validFirstAnswerIsReturnedAfterOneAttempt() {
        transport.willReply(VALID, "end_turn");

        LlmResult result = client.callJson("ping", List.of("Nonce: a1b2c3"));

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.json().path("nonce").stringValue("")).isEqualTo("a1b2c3");
        assertThat(result.promptVersion()).isEqualTo(1);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void requestUsesRoleSettingsAndSanitisedSchema() {
        transport.willReply(VALID, "end_turn");

        client.callJson("ping", List.of("Nonce: a1b2c3"));

        LlmRequest sent = transport.requests.getFirst();
        assertThat(sent.model()).isEqualTo("claude-haiku-5-5"); // ping.md declares role EXTRACTOR
        assertThat(sent.effort()).isEqualTo("medium");
        assertThat(sent.maxTokens()).isEqualTo(8000);
        assertThat(sent.system()).contains("connectivity check");
        // Keywords the API ignores are stripped from what we send ...
        assertThat(sent.apiSchema().path("properties").path("nonce").has("pattern")).isFalse();
        assertThat(sent.apiSchema().path("properties").path("message").has("maxLength")).isFalse();
        assertThat(sent.apiSchema().path("additionalProperties").booleanValue()).isFalse();
    }

    @Test
    void invalidThenValidRetriesOnceWithTheErrorsAppended() {
        transport.willReply(INVALID, "end_turn");
        transport.willReply(VALID, "end_turn");

        LlmResult result = client.callJson("ping", List.of("Nonce: a1b2c3"));

        assertThat(result.attempts()).isEqualTo(2);
        List<String> retryBlocks = texts(transport.requests.get(1).userBlocks());
        assertThat(retryBlocks).hasSize(2);
        assertThat(retryBlocks.getFirst()).isEqualTo("Nonce: a1b2c3");
        assertThat(retryBlocks.get(1))
                .contains("<previous_response>", "NOT-HEX", "<validation_errors>")
                .contains("nonce"); // the error names the failing field
    }

    @Test
    void stillInvalidAfterRetryFailsVisiblyWithTheErrors() {
        transport.willReply(INVALID, "end_turn");
        transport.willReply(INVALID, "end_turn");

        assertThatThrownBy(() -> client.callJson("ping", List.of("Nonce: a1b2c3")))
                .isInstanceOfSatisfying(LlmValidationException.class, e -> {
                    assertThat(e.errors()).hasSizeGreaterThanOrEqualTo(2); // pattern + maxLength
                    assertThat(e.lastResponseText()).contains("NOT-HEX");
                });
        assertThat(transport.requests).hasSize(ClaudeClient.MAX_ATTEMPTS);
    }

    @Test
    void nonJsonTextCountsAsAValidationFailureAndIsRetried() {
        transport.willReply("Sure! Here is your JSON:", "end_turn");
        transport.willReply(VALID, "end_turn");

        LlmResult result = client.callJson("ping", List.of("Nonce: a1b2c3"));

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(texts(transport.requests.get(1).userBlocks()).get(1)).contains("not valid JSON");
    }

    @Test
    void refusalFailsImmediatelyWithoutRetry() {
        transport.willReply("", "refusal");

        assertThatThrownBy(() -> client.callJson("ping", List.of("Nonce: a1b2c3")))
                .isInstanceOf(LlmException.class)
                .isNotInstanceOf(LlmValidationException.class)
                .hasMessageContaining("refusal");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void truncatedOutputFailsImmediatelyAndNamesTheSettingToRaise() {
        transport.willReply("{\"ok\": tr", "max_tokens");

        assertThatThrownBy(() -> client.callJson("ping", List.of("Nonce: a1b2c3")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("tailorsg.llm.extractor.max-tokens");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void usageAndCostAreSummedAcrossAttempts() {
        transport.willReply(INVALID, "end_turn");
        transport.willReply(VALID, "end_turn");

        LlmResult result = client.callJson("ping", List.of("Nonce: a1b2c3"));

        assertThat(result.usage()).isEqualTo(new TokenUsage(200, 100, 0, 0));
        // Haiku 5.5: 200 * $0.10/M + 100 * $0.50/M = $0.00007
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.00007"));
        assertThat(meter.snapshot().calls()).isEqualTo(2); // both attempts are billed, so both are metered
    }

    private static List<String> texts(List<LlmTransport.UserBlock> blocks) {
        return blocks.stream().map(b -> ((LlmTransport.TextBlock) b).text()).toList();
    }

    /** Fake transport: returns queued replies in order and records every request it receives. */
    private static final class ScriptedTransport implements LlmTransport {
        private final Deque<LlmReply> replies = new ArrayDeque<>();
        private final List<LlmRequest> requests = new ArrayList<>();

        void willReply(String text, String stopReason) {
            replies.add(new LlmReply(text, stopReason, "claude-haiku-5-5", USAGE));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public LlmReply send(LlmRequest request) {
            requests.add(request);
            if (replies.isEmpty()) {
                throw new AssertionError("Unexpected extra LLM call");
            }
            return replies.removeFirst();
        }
    }
}

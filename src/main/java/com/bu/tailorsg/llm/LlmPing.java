package com.bu.tailorsg.llm;

import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * End-to-end connectivity check: one tiny call to the extractor model through the full
 * {@link ClaudeClient} pipeline (prompt file, structured output, schema validation, cost meter).
 *
 * <p>A random nonce must come back unchanged, which proves the answer belongs to this request
 * and was not cached or invented.
 */
@Service
public class LlmPing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClaudeClient claude;

    public LlmPing(ClaudeClient claude) {
        this.claude = claude;
    }

    public PingResult ping() {
        if (!claude.isConfigured()) {
            return PingResult.failure(Status.NOT_CONFIGURED,
                    AnthropicTransport.API_KEY_ENV + " is not set. Export it and restart the app.", 0);
        }
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes); // 6 lowercase hex chars, matches the schema pattern

        long start = System.nanoTime();
        try {
            LlmResult result = claude.callJson("ping", List.of("Nonce: " + nonce));
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            String echoed = result.json().path("nonce").stringValue("");
            if (!nonce.equals(echoed)) {
                return PingResult.failure(Status.ERROR,
                        "Model answered but echoed nonce '" + echoed + "' instead of '" + nonce + "'", latencyMs);
            }
            return new PingResult(Status.OK, result.model(), result.json().path("message").stringValue(""),
                    result.attempts(), result.usage(), LlmUsageMeter.usd(result.costUsd()), latencyMs, null);
        } catch (LlmException e) {
            return PingResult.failure(Status.ERROR, e.getMessage(), (System.nanoTime() - start) / 1_000_000);
        }
    }

    public enum Status { OK, NOT_CONFIGURED, ERROR }

    /** Result of a ping, shaped for both the JSON endpoint and the Settings page. */
    public record PingResult(Status status, String model, String message, int attempts,
                             TokenUsage usage, String costUsd, long latencyMs, String error) {

        static PingResult failure(Status status, String error, long latencyMs) {
            return new PingResult(status, null, null, 0, TokenUsage.ZERO, null, latencyMs, error);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }
}

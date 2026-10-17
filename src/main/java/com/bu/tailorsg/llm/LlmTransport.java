package com.bu.tailorsg.llm;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The single seam between our code and the Anthropic SDK.
 *
 * <p>{@link ClaudeClient} holds the logic worth testing (validation, retry, cost tracking) and
 * talks only to this interface, so unit tests can swap in a scripted fake without network calls.
 * The real implementation is {@link AnthropicTransport}.
 */
public interface LlmTransport {

    /** True when an API key is available and real calls can be made. */
    boolean isConfigured();

    /** Sends one Messages API request. Throws {@link LlmException} on transport/API failure. */
    LlmReply send(LlmRequest request);

    /**
     * @param promptId        which prompt file this is (logging, and the demo transport's dispatch)
     * @param model           Claude model ID
     * @param effort          low | medium | high | xhigh | max
     * @param maxTokens       output cap (includes thinking tokens)
     * @param system          system prompt
     * @param userBlocks      user-content blocks, sent in order (e.g. MASTER, JOB, OPTIONS, or screenshots)
     * @param apiSchema       JSON Schema already sanitised for the API's structured-output mode
     * @param refusalFallback opt in to the server-side refusal fallback (writer model only)
     * @param thinkingDisabled send {@code thinking: disabled} instead of the model's default adaptive thinking
     *                         (Haiku 5.5 only, at effort high or below; Sonnet 5.5 answers 400)
     */
    record LlmRequest(String promptId, String model, String effort, long maxTokens, String system,
                      List<UserBlock> userBlocks, JsonNode apiSchema, boolean refusalFallback,
                      boolean thinkingDisabled) {}

    /** One piece of user content: text, or an image (screenshots of a job posting). */
    sealed interface UserBlock permits TextBlock, ImageBlock {}

    /** @param cacheable mark as a prompt-cache breakpoint (put stable content, like the master resume, first) */
    record TextBlock(String text, boolean cacheable) implements UserBlock {
        public static TextBlock of(String text) {
            return new TextBlock(text, false);
        }
    }

    /** @param mediaType image/png, image/jpeg, image/webp or image/gif */
    record ImageBlock(String mediaType, String base64) implements UserBlock {}

    static List<UserBlock> texts(List<String> texts) {
        return texts.stream().<UserBlock>map(TextBlock::of).toList();
    }

    /**
     * @param text       concatenated text blocks (the JSON, when structured output worked)
     * @param stopReason e.g. end_turn, max_tokens, refusal
     * @param modelUsed  the model that actually answered, as reported by the API
     */
    record LlmReply(String text, String stopReason, String modelUsed, TokenUsage usage) {}
}

package com.bu.tailorsg.llm;

/**
 * Token counts for one API call (or a sum of calls).
 *
 * @param input      uncached input tokens
 * @param output     output tokens, including thinking
 * @param cacheWrite input tokens written to the prompt cache
 * @param cacheRead  input tokens served from the prompt cache
 */
public record TokenUsage(long input, long output, long cacheWrite, long cacheRead) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    /** Total prompt size as the API bills it (used for Haiku's long-context price tier). */
    public long promptTokens() {
        return input + cacheWrite + cacheRead;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, output + other.output,
                cacheWrite + other.cacheWrite, cacheRead + other.cacheRead);
    }
}

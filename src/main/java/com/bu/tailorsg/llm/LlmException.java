package com.bu.tailorsg.llm;

/**
 * An LLM call failed in a way the user should see (missing key, API error, refusal, truncation).
 * Messages are written to be shown in the UI as-is and never contain the API key.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}

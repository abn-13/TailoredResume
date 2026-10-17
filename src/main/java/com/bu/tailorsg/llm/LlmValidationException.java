package com.bu.tailorsg.llm;

import java.util.List;

/**
 * The model's output still failed schema validation after the one allowed retry.
 * Carries the errors and the last raw text so the UI can show exactly what went wrong.
 */
public class LlmValidationException extends LlmException {

    private final List<String> errors;
    private final String lastResponseText;

    public LlmValidationException(String promptId, List<String> errors, String lastResponseText) {
        super("Response for prompt '" + promptId + "' failed schema validation after retry: "
                + String.join("; ", errors));
        this.errors = List.copyOf(errors);
        this.lastResponseText = lastResponseText;
    }

    public List<String> errors() {
        return errors;
    }

    public String lastResponseText() {
        return lastResponseText;
    }
}

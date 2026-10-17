package com.bu.tailorsg.llm;

import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.config.TailorSgProperties.RoleSettings;
import com.bu.tailorsg.llm.LlmTransport.LlmReply;
import com.bu.tailorsg.llm.LlmTransport.LlmRequest;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.llm.LlmTransport.UserBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The one entry point for LLM calls in TailorSG. Every call follows the same contract:
 *
 * <ol>
 *   <li>Load the versioned system prompt; it names its model role and output JSON Schema.</li>
 *   <li>Send it with the schema as structured output (sanitised for the API).</li>
 *   <li>Parse the reply and validate it against the <em>full</em> schema locally.</li>
 *   <li>If invalid, retry exactly once with the errors appended. If still invalid, throw
 *       {@link LlmValidationException}: fail visibly, never pass bad data on.</li>
 * </ol>
 *
 * <p>Refusals and truncated output ({@code max_tokens}) are not retried, because the same
 * request would most likely fail again the same way.
 */
@Service
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    static final int MAX_ATTEMPTS = 2; // first try + one retry with validation feedback

    private final LlmTransport transport;
    private final PromptLibrary prompts;
    private final JsonSchemaService schemas;
    private final ApiSchemaSanitizer sanitizer;
    private final LlmUsageMeter meter;
    private final TailorSgProperties props;
    private final JsonMapper jsonMapper;

    public ClaudeClient(LlmTransport transport, PromptLibrary prompts, JsonSchemaService schemas,
                        ApiSchemaSanitizer sanitizer, LlmUsageMeter meter, TailorSgProperties props,
                        JsonMapper jsonMapper) {
        this.transport = transport;
        this.prompts = prompts;
        this.schemas = schemas;
        this.sanitizer = sanitizer;
        this.meter = meter;
        this.props = props;
        this.jsonMapper = jsonMapper;
    }

    public boolean isConfigured() {
        return transport.isConfigured();
    }

    /**
     * Runs prompt {@code promptId} with the given user-content blocks and returns schema-valid JSON.
     *
     * @param userBlocks separate text blocks (e.g. MASTER JSON, JOB JSON, OPTIONS), kept apart so
     *                   the model sees clear boundaries and stable blocks can be cached later
     * @throws LlmValidationException if the output is still invalid after one retry
     * @throws LlmException           for API/transport errors, refusals or truncation
     */
    public LlmResult callJson(String promptId, List<String> userBlocks) {
        return call(promptId, LlmTransport.texts(userBlocks));
    }

    /**
     * Same as {@link #callJson}, with full control over blocks: images (screenshots) and a cacheable first
     * block (the master resume, reused across tailoring calls).
     */
    public LlmResult call(String promptId, List<UserBlock> userBlocks) {
        PromptTemplate prompt = prompts.get(promptId);
        String model = props.models().forRole(prompt.role());
        RoleSettings settings = props.llm().forRole(prompt.role());
        JsonNode apiSchema = sanitizer.sanitize(schemas.rawSchema(prompt.schemaPath()));

        TokenUsage totalUsage = TokenUsage.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        List<UserBlock> blocks = userBlocks;
        List<String> errors = List.of();
        String lastText = "";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmReply reply = transport.send(new LlmRequest(
                    promptId, model, settings.effort(), settings.maxTokens(), prompt.system(), blocks, apiSchema,
                    settings.refusalFallback()));
            totalUsage = totalUsage.plus(reply.usage());
            totalCost = totalCost.add(meter.record(promptId, prompt.version(), reply.modelUsed(), reply.usage()));
            failOnUnusableStop(promptId, reply, settings, prompt.role());

            lastText = reply.text();
            errors = new ArrayList<>();
            JsonNode json = parse(lastText, errors);
            if (json != null) {
                errors.addAll(schemas.validate(prompt.schemaPath(), json));
            }
            if (errors.isEmpty()) {
                return new LlmResult(json, promptId, prompt.version(), reply.modelUsed(), attempt, totalUsage, totalCost);
            }

            log.warn("Prompt {} attempt {}/{} failed validation: {}", promptId, attempt, MAX_ATTEMPTS, errors);
            blocks = withValidationFeedback(userBlocks, lastText, errors);
        }
        throw new LlmValidationException(promptId, errors, lastText);
    }

    private JsonNode parse(String text, List<String> errors) {
        if (text == null || text.isBlank()) {
            errors.add("Response was empty; expected a JSON object");
            return null;
        }
        try {
            return jsonMapper.readTree(text);
        } catch (JacksonException e) {
            errors.add("Response is not valid JSON: " + e.getOriginalMessage());
            return null;
        }
    }

    private static void failOnUnusableStop(String promptId, LlmReply reply, RoleSettings settings, ModelRole role) {
        switch (reply.stopReason()) {
            case "refusal" -> throw new LlmException(
                    "The model declined prompt '" + promptId + "' (stop_reason=refusal). Nothing was saved.");
            case "max_tokens" -> throw new LlmException(
                    "Output for prompt '" + promptId + "' was cut off at " + settings.maxTokens()
                            + " tokens. Raise tailorsg.llm." + role.name().toLowerCase() + ".max-tokens.");
            case "model_context_window_exceeded" -> throw new LlmException(
                    "Input for prompt '" + promptId + "' is larger than the model's context window.");
            default -> { /* end_turn and friends: carry on */ }
        }
    }

    /** The original blocks plus one block explaining what was wrong with the previous answer. */
    static List<UserBlock> withValidationFeedback(List<UserBlock> original, String previous, List<String> errors) {
        List<UserBlock> blocks = new ArrayList<>(original);
        blocks.add(TextBlock.of("""
                Your previous response did not pass validation against the required JSON schema.

                <previous_response>
                %s
                </previous_response>

                <validation_errors>
                %s
                </validation_errors>

                Return a corrected response that fixes every error above. Output only the JSON object."""
                .formatted(previous, "- " + String.join("\n- ", errors))));
        return blocks;
    }

    /**
     * A schema-valid LLM result plus the metadata we store alongside generated content.
     *
     * @param attempts 1 if the first answer was valid, 2 if the retry was needed
     * @param usage    tokens summed across attempts
     * @param costUsd  estimated cost summed across attempts
     */
    public record LlmResult(JsonNode json, String promptId, int promptVersion, String model,
                            int attempts, TokenUsage usage, BigDecimal costUsd) {}
}

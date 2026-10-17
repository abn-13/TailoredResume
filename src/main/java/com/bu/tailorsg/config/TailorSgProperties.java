package com.bu.tailorsg.config;

import com.bu.tailorsg.llm.ModelRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Typed view of everything under {@code tailorsg.*} in application.yml.
 *
 * <p>Records keep the config immutable once bound. {@code @Validated} makes the app fail at
 * startup (not at the first LLM call) if a required key is missing or malformed.
 *
 * @param models  model IDs per role, so they can be changed without a rebuild
 * @param llm     request settings per role, plus client-level timeout/retries
 * @param pricing USD per million tokens, keyed by model ID (used for the running cost estimate)
 * @param storage where generated files are written
 */
@Validated
@ConfigurationProperties(prefix = "tailorsg")
public record TailorSgProperties(
        @Valid @NotNull Models models,
        @Valid @NotNull Llm llm,
        @NotNull Map<String, @Valid ModelPrice> pricing,
        @Valid @NotNull Storage storage) {

    public record Models(@NotBlank String writer, @NotBlank String extractor) {

        public String forRole(ModelRole role) {
            return switch (role) {
                case WRITER -> writer;
                case EXTRACTOR -> extractor;
            };
        }
    }

    /**
     * @param timeout    per-request HTTP timeout for the Anthropic client
     * @param maxRetries SDK-level retries for 429 / 5xx / network errors. This is separate from
     *                   our own single "schema validation failed" retry in ClaudeClient.
     */
    public record Llm(
            @NotNull Duration timeout,
            @Min(0) int maxRetries,
            @Valid @NotNull RoleSettings writer,
            @Valid @NotNull RoleSettings extractor) {

        public RoleSettings forRole(ModelRole role) {
            return switch (role) {
                case WRITER -> writer;
                case EXTRACTOR -> extractor;
            };
        }
    }

    /**
     * @param effort    low | medium | high | xhigh | max. Set explicitly because default effort
     *                  differs per model (Haiku 5.5 defaults to medium, Sonnet 5.5 to high).
     * @param maxTokens output cap per response, including thinking tokens
     * @param refusalFallback opt in to the API's server-side refusal fallback (writer: Sonnet 5.5 supports
     *                        the "default" form). Set false if your account rejects the beta header.
     */
    public record RoleSettings(@NotBlank String effort, @Positive long maxTokens, boolean refusalFallback) {}

    /**
     * Prices in USD per million tokens. {@code longContext} is optional: Haiku 5.5 bills
     * prompts above a token threshold at higher rates; other current models do not.
     */
    public record ModelPrice(
            @NotNull BigDecimal input,
            @NotNull BigDecimal output,
            @NotNull BigDecimal cacheWrite,
            @NotNull BigDecimal cacheRead,
            @Valid LongContext longContext) {}

    public record LongContext(
            @Positive long thresholdTokens,
            @NotNull BigDecimal input,
            @NotNull BigDecimal output,
            @NotNull BigDecimal cacheWrite,
            @NotNull BigDecimal cacheRead) {}

    public record Storage(@NotBlank String exportsDir) {}
}

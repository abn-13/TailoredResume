package com.bu.tailorsg.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * A system prompt loaded from {@code src/main/resources/prompts/{id}.md}.
 *
 * <p>Each file starts with a small front-matter block so the prompt carries its own
 * version, model role and output schema. Bump {@code version} whenever the text changes;
 * applications record which version produced them (Phase 9).
 *
 * <pre>
 * ---
 * version: 1
 * role: EXTRACTOR
 * schema: schema/llm/ping.schema.json
 * thinking: disabled        (optional; default: the model's adaptive thinking)
 * ---
 * (system prompt text)
 * </pre>
 *
 * <p>{@code thinking: disabled} is for long, mechanical outputs on the extractor model. Thinking tokens count
 * toward {@code max-tokens} even when nobody sees them: resume import spent about 6,600 of them per call
 * at effort low, which pushed a long resume past the limit (measured 2026-10-17).
 *
 * @param id               file name without .md
 * @param version          prompt version from front matter
 * @param role             which configured model runs it
 * @param schemaPath       classpath location of the JSON Schema the response must match
 * @param system           the system prompt text (everything after the front matter)
 * @param thinkingDisabled true when the front matter says {@code thinking: disabled}
 */
public record PromptTemplate(String id, int version, ModelRole role, String schemaPath, String system,
                             boolean thinkingDisabled) {

    private static final String FENCE = "---";

    /** Parses a prompt file. Kept deliberately tiny: flat {@code key: value} lines only. */
    static PromptTemplate parse(String id, String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith(FENCE + "\n")) {
            throw new IllegalStateException("Prompt '" + id + "' must start with a '---' front-matter block");
        }
        int end = text.indexOf("\n" + FENCE + "\n", FENCE.length());
        if (end < 0) {
            throw new IllegalStateException("Prompt '" + id + "' front matter is not closed with '---'");
        }

        Map<String, String> meta = new HashMap<>();
        for (String line : text.substring(FENCE.length() + 1, end).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalStateException("Prompt '" + id + "' has a malformed front-matter line: " + line);
            }
            meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }

        String body = text.substring(end + FENCE.length() + 2).strip();
        if (body.isEmpty()) {
            throw new IllegalStateException("Prompt '" + id + "' has no system text after the front matter");
        }
        return new PromptTemplate(
                id,
                Integer.parseInt(required(meta, "version", id)),
                ModelRole.valueOf(required(meta, "role", id).toUpperCase()),
                required(meta, "schema", id),
                body,
                thinkingDisabled(meta.getOrDefault("thinking", "adaptive"), id));
    }

    private static boolean thinkingDisabled(String value, String id) {
        return switch (value) {
            case "adaptive" -> false;
            case "disabled" -> true;
            default -> throw new IllegalStateException(
                    "Prompt '" + id + "' has thinking: " + value + " (expected adaptive or disabled)");
        };
    }

    private static String required(Map<String, String> meta, String key, String id) {
        String value = meta.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Prompt '" + id + "' is missing front-matter key '" + key + "'");
        }
        return value;
    }
}

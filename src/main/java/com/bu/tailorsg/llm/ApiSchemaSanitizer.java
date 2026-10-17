package com.bu.tailorsg.llm;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one of our full JSON Schemas into the subset the Claude API accepts for structured outputs
 * ({@code output_config.format}).
 *
 * <p>Why: the API answers <b>400</b> to any keyword it does not support (numeric bounds, string length,
 * most array constraints, regex features...) and requires {@code additionalProperties: false} on every
 * object. Rather than maintaining two schema files, we keep one strict schema, send a relaxed copy to the
 * API, and enforce the strict one locally with {@link JsonSchemaService}.
 *
 * <p>It is an <em>allowlist</em>: only keywords the structured-outputs docs list as supported survive.
 * Anything else (including {@code title}, {@code pattern} and our {@code errorMessage} extension) is
 * removed, so a new keyword in one of our schemas can never break API calls.
 *
 * <p>The walk is schema-aware: it only filters keywords where a schema is expected, so a property that
 * happens to be <em>named</em> "pattern" or "title" is left alone.
 *
 * <p>Not handled here: the API's per-request limits of 24 optional properties and 16 union-typed
 * properties, and its unpublished limit on compiled grammar size ("The compiled grammar is too large").
 * Schemas sent to the API are written to stay within them; {@code LlmSchemaLimitsTest} checks all three
 * offline, and {@code LlmSchemaCompileLiveTest} checks against the real API when a key is set.
 */
@Component
public class ApiSchemaSanitizer {

    /** Keywords documented as supported (platform.claude.com structured outputs, checked 2026-10-16). */
    static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "items",
            "enum", "const", "anyOf", "allOf", "$ref", "$defs", "definitions",
            "description", "format", "default", "minItems");

    /** String formats the API understands. Other formats are dropped (and still checked locally). */
    static final Set<String> SUPPORTED_FORMATS = Set.of(
            "date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid");

    /** Keywords whose value is a map of name -> schema. */
    private static final List<String> SCHEMA_MAPS = List.of("properties", "$defs", "definitions");
    /** Keywords whose value is an array of schemas. */
    private static final List<String> SCHEMA_ARRAYS = List.of("anyOf", "allOf");

    /** Returns a sanitised deep copy; the input is never modified. */
    public JsonNode sanitize(JsonNode schema) {
        JsonNode copy = schema.deepCopy();
        walk(copy);
        return copy;
    }

    private void walk(JsonNode node) {
        if (!(node instanceof ObjectNode schema)) {
            return; // boolean schemas (true/false) need no changes
        }

        // The API supports anyOf but not oneOf. anyOf is looser; local validation still enforces oneOf.
        JsonNode oneOf = schema.remove("oneOf");
        if (oneOf != null && !schema.has("anyOf")) {
            schema.set("anyOf", oneOf);
        }

        List<String> unsupported = new ArrayList<>();
        schema.propertyNames().forEach(key -> {
            if (!SUPPORTED_KEYWORDS.contains(key)) {
                unsupported.add(key);
            }
        });
        unsupported.forEach(schema::remove);

        JsonNode format = schema.get("format");
        if (format != null && !(format.isString() && SUPPORTED_FORMATS.contains(format.stringValue()))) {
            schema.remove("format");
        }
        JsonNode minItems = schema.get("minItems");
        if (minItems != null && !(minItems.isIntegralNumber() && minItems.asInt() <= 1)) {
            schema.remove("minItems"); // only 0 or 1 is supported
        }
        if (isObjectSchema(schema)) {
            schema.put("additionalProperties", false);
        }

        for (String key : SCHEMA_MAPS) {
            JsonNode map = schema.get(key);
            if (map != null && map.isObject()) {
                map.values().forEach(this::walk);
            }
        }
        for (String key : SCHEMA_ARRAYS) {
            JsonNode array = schema.get(key);
            if (array != null && array.isArray()) {
                array.values().forEach(this::walk);
            }
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            walk(items);
        }
    }

    private static boolean isObjectSchema(ObjectNode schema) {
        JsonNode type = schema.get("type");
        boolean typedObject = type != null && (
                isText(type, "object") || (type.isArray() && type.values().stream().anyMatch(t -> isText(t, "object"))));
        return typedObject || schema.has("properties");
    }

    // Jackson 3's asString() can throw on non-string nodes, so check the node type first.
    private static boolean isText(JsonNode node, String expected) {
        return node.isString() && expected.equals(node.stringValue());
    }
}

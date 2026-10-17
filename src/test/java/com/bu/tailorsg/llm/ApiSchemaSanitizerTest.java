package com.bu.tailorsg.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSchemaSanitizerTest {

    private final ApiSchemaSanitizer sanitizer = new ApiSchemaSanitizer();

    private static JsonNode json(String text) {
        return TestFixtures.JSON.readTree(text);
    }

    @Test
    void stripsUnsupportedKeywordsAtEveryDepth() {
        JsonNode schema = json("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "count": { "type": "integer", "minimum": 1, "maximum": 5 },
                    "tags": { "type": "array", "minItems": 2, "items": { "type": "string", "maxLength": 20 } },
                    "choice": { "anyOf": [ { "type": "string", "pattern": "^a" }, { "type": "null" } ] }
                  },
                  "$defs": { "id": { "type": "string", "minLength": 3 } }
                }""");

        JsonNode out = sanitizer.sanitize(schema);

        assertThat(out.has("$schema")).isFalse();
        assertThat(out.at("/properties/count").has("minimum")).isFalse();
        assertThat(out.at("/properties/count").has("maximum")).isFalse();
        assertThat(out.at("/properties/tags").has("minItems")).isFalse();
        assertThat(out.at("/properties/tags/items").has("maxLength")).isFalse();
        assertThat(out.at("/properties/choice/anyOf/0").has("pattern")).isFalse();
        assertThat(out.at("/$defs/id").has("minLength")).isFalse();
        // Supported structure survives
        assertThat(out.at("/properties/count/type").stringValue("")).isEqualTo("integer");
    }

    @Test
    void isAnAllowlistSoUndocumentedKeywordsNeverReachTheApi() {
        JsonNode out = sanitizer.sanitize(json("""
                { "title": "Thing", "type": "object", "x-custom": 1, "examples": [{}],
                  "properties": { "n": { "type": "array", "minItems": 1, "contains": {"type": "string"} } } }"""));

        assertThat(out.has("title")).isFalse();
        assertThat(out.has("x-custom")).isFalse();
        assertThat(out.has("examples")).isFalse();
        assertThat(out.at("/properties/n").has("contains")).isFalse();
        assertThat(out.at("/properties/n/minItems").asInt()).isEqualTo(1); // 0 or 1 is supported
    }

    @Test
    void leavesPropertiesThatAreMerelyNamedLikeKeywords() {
        JsonNode schema = json("""
                { "type": "object",
                  "properties": { "pattern": { "type": "string" }, "title": { "type": "string" } } }""");

        JsonNode out = sanitizer.sanitize(schema);

        assertThat(out.at("/properties").has("pattern")).isTrue();
        assertThat(out.at("/properties").has("title")).isTrue();
    }

    @Test
    void forcesAdditionalPropertiesFalseOnEveryObject() {
        JsonNode schema = json("""
                { "type": "object", "additionalProperties": true,
                  "properties": { "inner": { "properties": { "a": { "type": "string" } } } } }""");

        JsonNode out = sanitizer.sanitize(schema);

        assertThat(out.path("additionalProperties").booleanValue()).isFalse();
        assertThat(out.at("/properties/inner/additionalProperties").booleanValue()).isFalse();
        assertThat(out.at("/properties/inner/additionalProperties").isBoolean()).isTrue();
    }

    @Test
    void convertsOneOfToAnyOf() {
        JsonNode out = sanitizer.sanitize(json("""
                { "oneOf": [ { "type": "string" }, { "type": "integer" } ] }"""));

        assertThat(out.has("oneOf")).isFalse();
        assertThat(out.path("anyOf").size()).isEqualTo(2);
    }

    @Test
    void keepsSupportedFormatsAndDropsOthers() {
        JsonNode out = sanitizer.sanitize(json("""
                { "type": "object", "properties": {
                    "email": { "type": "string", "format": "email" },
                    "phone": { "type": "string", "format": "phone-e164" } } }"""));

        assertThat(out.at("/properties/email/format").stringValue("")).isEqualTo("email");
        assertThat(out.at("/properties/phone").has("format")).isFalse();
    }

    @Test
    void stripsOurCustomErrorMessageExtension() {
        JsonNode out = sanitizer.sanitize(json("""
                { "type": "string", "pattern": "^a", "errorMessage": { "pattern": "must start with a" } }"""));

        assertThat(out.has("errorMessage")).isFalse();
    }

    @Test
    void neverModifiesTheInput() {
        JsonNode schema = json("""
                { "type": "object", "properties": { "n": { "type": "integer", "minimum": 1 } } }""");
        String before = schema.toString();

        sanitizer.sanitize(schema);

        assertThat(schema.toString()).isEqualTo(before);
    }
}

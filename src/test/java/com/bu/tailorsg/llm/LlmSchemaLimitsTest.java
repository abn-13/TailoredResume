package com.bu.tailorsg.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structured outputs reject a request (HTTP 400) whose schemas have more than 24 optional properties or
 * more than 16 union-typed properties (anyOf or type arrays), or whose compiled grammar is too large. This
 * walks every schema an LLM prompt uses, after sanitising, so a schema change that would break live calls
 * fails here instead. {@link LlmSchemaCompileLiveTest} checks the same schemas against the real API.
 */
class LlmSchemaLimitsTest {

    static final int MAX_OPTIONAL = 24;
    static final int MAX_UNIONS = 16;

    /**
     * The grammar size limit is not published, so this budget was measured against the live API on
     * 2026-10-17 with variants of the import schema. Counting properties after expanding local {@code $ref}s
     * (as a grammar compiler must), every variant with up to 68 compiled and every variant with 80 or more was
     * rejected with "The compiled grammar is too large". The original single import schema had 96. A schema
     * over this budget might still compile, but nothing that large has been seen to: split it instead (see
     * ResumeImportService.PROMPTS).
     */
    static final int MAX_EXPANDED_PROPERTIES = 68;

    /** Every prompt file in src/main/resources/prompts, so a new prompt can never skip this check. */
    static java.util.stream.Stream<String> allPrompts() throws java.io.IOException {
        return java.util.Arrays.stream(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath:prompts/*.md"))
                .map(r -> r.getFilename().replaceFirst("\\.md$", ""));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("allPrompts")
    void promptSchemaFitsTheApiLimits(String promptId) {
        PromptTemplate prompt = TestFixtures.prompts().get(promptId);
        JsonNode schema = new ApiSchemaSanitizer().sanitize(TestFixtures.schemas().rawSchema(prompt.schemaPath()));

        Counts counts = new Counts();
        walk(schema, counts);

        assertThat(counts.optional).as("optional properties in " + promptId).isLessThanOrEqualTo(MAX_OPTIONAL);
        assertThat(counts.unions).as("union-typed properties in " + promptId).isLessThanOrEqualTo(MAX_UNIONS);
        assertThat(counts.externalRefs).as("external $refs in " + promptId).isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("allPrompts")
    void promptSchemaStaysWithinTheMeasuredGrammarBudget(String promptId) {
        PromptTemplate prompt = TestFixtures.prompts().get(promptId);

        assertThat(expandedProperties(sanitized(prompt.schemaPath())))
                .as("properties after expanding $refs in " + promptId).isLessThanOrEqualTo(MAX_EXPANDED_PROPERTIES);
    }

    /** Regression (2026-10-17): sent whole, the import schema got "The compiled grammar is too large". */
    @Test
    void theBudgetRejectsTheSingleImportSchemaTheApiRejected() throws java.io.IOException {
        JsonNode wholeImport = sanitized("schema/llm/master-import.schema.json");

        assertThat(expandedProperties(wholeImport)).isEqualTo(96).isGreaterThan(MAX_EXPANDED_PROPERTIES);
        assertThat(allPrompts()).as("no prompt may send the whole import schema again")
                .noneMatch(id -> TestFixtures.prompts().get(id).schemaPath().equals("schema/llm/master-import.schema.json"));
    }

    private static JsonNode sanitized(String schemaPath) {
        return new ApiSchemaSanitizer().sanitize(TestFixtures.schemas().rawSchema(schemaPath));
    }

    /** Properties in the schema with every local $ref expanded in place: what the grammar has to encode. */
    static int expandedProperties(JsonNode root) {
        return expandedProperties(root, root);
    }

    private static int expandedProperties(JsonNode schema, JsonNode root) {
        if (!schema.isObject()) {
            return 0;
        }
        JsonNode ref = schema.get("$ref");
        if (ref != null) {
            return expandedProperties(root.at(ref.stringValue("").substring(1)), root); // "#/$defs/x" -> "/$defs/x"
        }
        int count = expandedProperties(schema.path("items"), root);
        for (JsonNode property : schema.path("properties").values()) {
            count += 1 + expandedProperties(property, root);
        }
        for (String combinator : List.of("anyOf", "allOf")) {
            for (JsonNode option : schema.path(combinator).values()) {
                count += expandedProperties(option, root);
            }
        }
        return count;
    }

    private static final class Counts {
        int optional;
        int unions;
        int externalRefs;
    }

    private static void walk(JsonNode schema, Counts counts) {
        if (!schema.isObject()) {
            return;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            Set<String> required = new HashSet<>();
            schema.path("required").values().forEach(r -> required.add(r.stringValue("")));
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                if (!required.contains(property.getKey())) {
                    counts.optional++;
                }
                JsonNode type = property.getValue().get("type");
                if (property.getValue().has("anyOf") || (type != null && type.isArray())) {
                    counts.unions++;
                }
                walk(property.getValue(), counts);
            }
        }
        JsonNode ref = schema.get("$ref");
        if (ref != null && !ref.stringValue("").startsWith("#")) {
            counts.externalRefs++;
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            walk(items, counts);
        }
        schema.path("$defs").values().forEach(def -> walk(def, counts));
        schema.path("anyOf").values().forEach(option -> walk(option, counts));
    }
}

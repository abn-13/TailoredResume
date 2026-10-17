package com.bu.tailorsg.llm;

import org.junit.jupiter.params.ParameterizedTest;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structured outputs reject a request (HTTP 400) whose schemas have more than 24 optional properties or
 * more than 16 union-typed properties (anyOf or type arrays). This walks every schema an LLM prompt uses,
 * after sanitising, so a schema change that would break live calls fails here instead.
 */
class LlmSchemaLimitsTest {

    static final int MAX_OPTIONAL = 24;
    static final int MAX_UNIONS = 16;

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

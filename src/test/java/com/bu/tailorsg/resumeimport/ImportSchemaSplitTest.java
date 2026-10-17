package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.llm.JsonSchemaService;
import com.bu.tailorsg.llm.ModelRole;
import com.bu.tailorsg.llm.PromptLibrary;
import com.bu.tailorsg.llm.PromptTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import runs as two requests because the whole schema was too large for the API's grammar compiler
 * (see {@link ResumeImportService#PROMPTS}). These checks make sure the split loses nothing: the two part
 * schemas cover every section of master-import.schema.json exactly once, unchanged, so the merged output
 * is exactly what {@link ImportMapper} has always received.
 */
class ImportSchemaSplitTest {

    private static final DefaultResourceLoader RESOURCES = new DefaultResourceLoader();
    private final PromptLibrary prompts = new PromptLibrary(RESOURCES);
    private final JsonSchemaService schemas = new JsonSchemaService(RESOURCES, ImportPipelineUnitTest.JSON);
    private final JsonNode whole = schemas.rawSchema(ResumeImportService.MERGED_SCHEMA);

    @Test
    void thePartsCoverEverySectionOnceAndUnchanged() {
        Set<String> covered = new HashSet<>();
        for (JsonNode part : parts()) {
            for (String section : part.path("properties").propertyNames()) {
                assertThat(part.path("properties").get(section)).as(section)
                        .isEqualTo(whole.path("properties").get(section));
                if (!section.equals("warnings")) {
                    assertThat(covered.add(section)).as(section + " is in only one part").isTrue();
                }
            }
        }
        covered.add("warnings");

        assertThat(covered).containsExactlyInAnyOrderElementsOf(whole.path("properties").propertyNames());
    }

    @Test
    void everyPartFieldIsRequiredAndEveryRefResolvesToTheSameDefinition() {
        for (JsonNode part : parts()) {
            assertThat(names(part.path("required")))
                    .containsExactlyInAnyOrderElementsOf(part.path("properties").propertyNames());
            for (String ref : refs(part, new ArrayList<>())) {
                String pointer = ref.substring(1); // "#/$defs/highlight" -> "/$defs/highlight"
                assertThat(part.at(pointer).isMissingNode()).as(ref + " resolves").isFalse();
                assertThat(part.at(pointer)).as(ref).isEqualTo(whole.at(pointer));
            }
        }
    }

    /**
     * Thinking off is a regression guard (2026-10-17): with adaptive thinking, even at effort low, a
     * 3-page resume's entries call needed about 17,600 output tokens (11,000 of JSON plus 6,600 of
     * thinking) and was cut off at the 16,000 limit. Without thinking it needed 11,400, with the same
     * extraction quality.
     */
    @Test
    void bothPromptsUseTheExtractorModelTheirOwnPartSchemaAndNoThinking() {
        assertThat(ResumeImportService.PROMPTS).containsExactly("master-import-profile", "master-import-entries");
        for (String id : ResumeImportService.PROMPTS) {
            PromptTemplate prompt = prompts.get(id);
            assertThat(prompt.role()).isEqualTo(ModelRole.EXTRACTOR);
            assertThat(prompt.schemaPath()).isEqualTo("schema/llm/" + id + ".schema.json");
            assertThat(prompt.thinkingDisabled()).as(id + " thinking: disabled").isTrue();
        }
    }

    @Test
    void theFixtureSplitByThePartSchemasMergesBackToTheSameOutput() {
        JsonNode full = ImportPipelineUnitTest.JSON.readTree(ImportPipelineUnitTest.fixture("llm-output.json"));
        List<JsonNode> sliced = ResumeImportService.PROMPTS.stream()
                .map(id -> slice(full, schemas.rawSchema(prompts.get(id).schemaPath())))
                .toList();
        for (int i = 0; i < sliced.size(); i++) {
            String partSchema = prompts.get(ResumeImportService.PROMPTS.get(i)).schemaPath();
            assertThat(schemas.validate(partSchema, sliced.get(i))).as(partSchema).isEmpty();
        }

        JsonNode merged = ResumeImportService.merge(sliced);

        assertThat(merged).isEqualTo(full);
        assertThat(schemas.validate(ResumeImportService.MERGED_SCHEMA, merged)).isEmpty();
    }

    /** The sections of {@code full} that {@code partSchema} owns; warnings go to every part. */
    static JsonNode slice(JsonNode full, JsonNode partSchema) {
        ObjectNode out = ImportPipelineUnitTest.JSON.createObjectNode();
        for (String section : partSchema.path("properties").propertyNames()) {
            out.set(section, full.get(section));
        }
        return out;
    }

    private List<JsonNode> parts() {
        return ResumeImportService.PROMPTS.stream().map(id -> schemas.rawSchema(prompts.get(id).schemaPath())).toList();
    }

    private static List<String> names(JsonNode array) {
        return array.values().stream().map(n -> n.stringValue("")).toList();
    }

    private static List<String> refs(JsonNode node, List<String> found) {
        if (node.isObject() && node.has("$ref")) {
            found.add(node.get("$ref").stringValue(""));
        }
        node.values().forEach(child -> refs(child, found));
        return found;
    }
}

package com.bu.tailorsg.llm;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads JSON Schema files from the classpath and validates JSON against them
 * (networknt json-schema-validator, draft 2020-12).
 *
 * <p>This is the deterministic gate for every LLM response: the API's structured-output mode
 * constrains the shape, but it ignores keywords like {@code maxLength} or {@code pattern}
 * (see {@link ApiSchemaSanitizer}), so the full schema is enforced here.
 */
@Component
public class JsonSchemaService {

    private final ResourceLoader resourceLoader;
    private final JsonMapper jsonMapper;
    // Draft 2020-12 is the default dialect. Its meta-schema ships inside the library, so no network access.
    // "errorMessage" lets a schema replace raw messages ("does not match the regex pattern ^[0-9]{4}...")
    // with readable ones ("use YYYY-MM, e.g. 2024-05").
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().errorMessageKeyword("errorMessage").build()));
    private final Map<String, JsonNode> rawSchemas = new ConcurrentHashMap<>();
    private final Map<String, Schema> compiledSchemas = new ConcurrentHashMap<>();

    public JsonSchemaService(ResourceLoader resourceLoader, JsonMapper jsonMapper) {
        this.resourceLoader = resourceLoader;
        this.jsonMapper = jsonMapper;
    }

    /** The schema document itself, e.g. to send (sanitised) to the API. Callers must not mutate it. */
    public JsonNode rawSchema(String classpathLocation) {
        return rawSchemas.computeIfAbsent(classpathLocation, this::read);
    }

    /**
     * Validates {@code instance} against the schema.
     *
     * @return one human-readable line per violation (e.g. {@code "$.nonce: does not match ..."});
     *         empty when valid
     */
    public List<String> validate(String classpathLocation, JsonNode instance) {
        return compiled(classpathLocation).validate(instance).stream().map(Error::toString).toList();
    }

    /**
     * Same validation, with the parts kept separate so callers can show or filter them.
     * Paths are JSON Pointers ({@code /work/0/id}; {@code ""} is the root).
     */
    public List<SchemaViolation> violations(String classpathLocation, JsonNode instance) {
        return compiled(classpathLocation).validate(instance).stream()
                .map(e -> new SchemaViolation(e.getInstanceLocation().toString(), e.getMessage(),
                        e.getKeyword(), e.getProperty()))
                .toList();
    }

    /**
     * @param property for "required" / "additionalProperties" errors, the property concerned; otherwise null
     */
    public record SchemaViolation(String path, String message, String keyword, String property) {}

    private Schema compiled(String classpathLocation) {
        return compiledSchemas.computeIfAbsent(classpathLocation,
                // Passing a SchemaLocation as well as the content lets relative $refs resolve later.
                path -> registry.getSchema(SchemaLocation.of("classpath:" + path), rawSchema(path)));
    }

    private JsonNode read(String classpathLocation) {
        Resource resource = resourceLoader.getResource("classpath:" + classpathLocation);
        if (!resource.exists()) {
            throw new IllegalArgumentException("No schema file at " + classpathLocation);
        }
        try (InputStream in = resource.getInputStream()) {
            return jsonMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read schema " + classpathLocation, e);
        }
    }
}

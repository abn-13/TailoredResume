package com.bu.tailorsg.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prompt front-matter parsing and schema validation, using the real ping files. */
class PromptAndSchemaTest {

    @Test
    void pingPromptDeclaresVersionRoleAndSchema() {
        PromptTemplate ping = TestFixtures.prompts().get("ping");

        assertThat(ping.version()).isEqualTo(1);
        assertThat(ping.role()).isEqualTo(ModelRole.EXTRACTOR);
        assertThat(ping.schemaPath()).isEqualTo("schema/llm/ping.schema.json");
        assertThat(ping.system()).startsWith("You are the connectivity check").doesNotContain("---");
    }

    @Test
    void frontMatterMustBePresentAndComplete() {
        assertThatThrownBy(() -> PromptTemplate.parse("x", "No front matter"))
                .hasMessageContaining("must start with");
        assertThatThrownBy(() -> PromptTemplate.parse("x", "---\nversion: 1\nrole: WRITER\n---\nText"))
                .hasMessageContaining("'schema'");
        assertThatThrownBy(() -> PromptTemplate.parse("x", "---\nversion: 1\nrole: WRITER\nschema: s.json\n---\n"))
                .hasMessageContaining("no system text");
    }

    @Test
    void frontMatterToleratesWindowsLineEndings() {
        PromptTemplate p = PromptTemplate.parse("x", "---\r\nversion: 2\r\nrole: writer\r\nschema: s.json\r\n---\r\nHello\r\n");

        assertThat(p.version()).isEqualTo(2);
        assertThat(p.role()).isEqualTo(ModelRole.WRITER);
        assertThat(p.system()).isEqualTo("Hello");
    }

    @Test
    void missingPromptFileIsAClearError() {
        assertThatThrownBy(() -> TestFixtures.prompts().get("does-not-exist"))
                .hasMessageContaining("prompts/does-not-exist.md");
    }

    @Test
    void schemaAcceptsValidPingAndReportsEachViolationWithItsPath() {
        JsonSchemaService schemas = TestFixtures.schemas();
        String path = "schema/llm/ping.schema.json";

        assertThat(schemas.validate(path, TestFixtures.JSON.readTree("""
                {"ok": true, "nonce": "0f9a3c", "message": "Hi"}"""))).isEmpty();

        List<String> errors = schemas.validate(path, TestFixtures.JSON.readTree("""
                {"ok": false, "nonce": "XYZ", "extra": 1}"""));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("ok"));      // const true
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("nonce"));   // pattern
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("message")); // required
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("extra"));   // additionalProperties
    }
}

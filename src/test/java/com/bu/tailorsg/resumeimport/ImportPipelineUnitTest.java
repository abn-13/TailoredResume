package com.bu.tailorsg.resumeimport;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic parts of resume import, without Spring or an LLM: redaction, mapping the model's
 * output, evidence linking and the provenance check. Fixtures: fixtures/import/resume.txt (the
 * "uploaded" text) and fixtures/import/llm-output.json (what the extractor model would return).
 */
class ImportPipelineUnitTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static String fixture(String name) {
        try {
            return new DefaultResourceLoader().getResource("classpath:fixtures/import/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- redaction

    @Test
    void redactorRemovesPersonalDataLinesAndDoesNotRepeatThemInNotes() {
        PersonalDataRedactor.Redacted redacted = new PersonalDataRedactor().redact(fixture("resume.txt"));

        assertThat(redacted.text()).doesNotContain("S1234567D", "1 January 1995")
                .contains("Alex Lim", "Example Logistics Pte Ltd", "Languages: Java, Python, SQL");
        assertThat(redacted.notes()).hasSize(2)
                .allSatisfy(n -> assertThat(n).doesNotContain("S1234567D", "1995").contains("not stored or sent"));
    }

    // ---------------------------------------------------------------- mapper

    @Test
    void mapperDropsEmptiesAndExplainsEveryAssumption() {
        ImportMapper.Mapped mapped = new ImportMapper().map(JSON.readTree(fixture("llm-output.json")));
        JsonNode doc = mapped.document();

        assertThat(doc.at("/basics/phone").stringValue()).isEqualTo("+6591234567");
        assertThat(doc.at("/basics/url").stringValue()).isEqualTo("https://github.com/alexlim-example");
        assertThat(doc.at("/basics/location/countryCode").stringValue()).isEqualTo("SG");
        assertThat(doc.at("/basics/summaryVariants/0/text").stringValue()).startsWith("Backend developer");
        assertThat(doc.at("/basics").has("workAuthorization")).isFalse();
        assertThat(doc.at("/work/0").has("endDate")).isFalse();          // "" = current role
        assertThat(doc.at("/work/0").has("summary")).isFalse();          // "" dropped
        assertThat(doc.at("/work/1").has("employmentType")).isFalse();
        assertThat(doc.at("/education/0/startDate").stringValue()).isEqualTo("2015-01");
        assertThat(doc.has("projects")).isFalse();                         // [] dropped

        assertThat(mapped.warnings())
                .anySatisfy(w -> assertThat(w).contains("assumed Singapore (+65)"))
                .anySatisfy(w -> assertThat(w).contains("Added https://"))
                .anySatisfy(w -> assertThat(w).contains("only the year", "startDate (2015)"))
                .anySatisfy(w -> assertThat(w).contains("only the year", "endDate (2019)"));
    }

    @Test
    void mapperFlagsPastRolesWithoutEndDateAndUnreadableDates() {
        JsonNode llm = JSON.readTree("""
                {"basics": {}, "work": [
                   {"name": "A", "position": "Now", "startDate": "2025-01", "endDate": ""},
                   {"name": "B", "position": "Before", "startDate": "Summer 2020", "endDate": ""}]}""");

        ImportMapper.Mapped mapped = new ImportMapper().map(llm);

        assertThat(mapped.warnings())
                .anySatisfy(w -> assertThat(w).contains("\"Before, B\" has no end date"))
                .anySatisfy(w -> assertThat(w).contains("could not read startDate \"Summer 2020\""))
                .noneSatisfy(w -> assertThat(w).contains("\"Now, A\" has no end date"));
    }

    // ---------------------------------------------------------------- evidence

    @Test
    void evidenceComesFromHighlightSkillsOrWholeWordMentions() {
        ObjectNode doc = (ObjectNode) JSON.readTree("""
                {"work": [{"highlights": [
                    {"text": "Built JavaScript dashboards", "skills": []},
                    {"text": "Wrote a Python ETL job", "skills": []},
                    {"text": "Shipped a feature to go live early", "skills": ["Go"]}]}],
                 "skills": [{"name": "Java"}, {"name": "Python"}, {"name": "Go"}, {"name": "Rust"}]}""");

        int evidenced = new EvidenceLinker().link(doc);

        assertThat(doc.at("/work/0/highlights/0/id").stringValue()).isEqualTo("NEW-1");
        assertThat(doc.at("/skills/0").has("evidenceIds")).isFalse();                 // Java is not "JavaScript"
        assertThat(doc.at("/skills/1/evidenceIds/0").stringValue()).isEqualTo("NEW-2"); // text mention
        assertThat(doc.at("/skills/2/evidenceIds").size()).isEqualTo(1);               // "Go" only via skills list
        assertThat(doc.at("/skills/3").has("evidenceIds")).isFalse();
        assertThat(evidenced).isEqualTo(2);
    }

    @Test
    void shortSkillNamesAreNotMatchedInOrdinaryWords() {
        JsonNode highlight = JSON.readTree("{\"text\": \"Helped the team go live\", \"skills\": []}");

        assertThat(EvidenceLinker.demonstrates(highlight, "Go")).isFalse();
        assertThat(EvidenceLinker.demonstrates(highlight, "team")).isTrue();
    }

    // ---------------------------------------------------------------- verifier

    @Test
    void verifierListsOnlyWhatIsNotInTheUploadedText() {
        String source = new PersonalDataRedactor().redact(fixture("resume.txt")).text();
        ObjectNode doc = new ImportMapper().map(JSON.readTree(fixture("llm-output.json"))).document();

        List<String> notes = new ImportVerifier().verify(doc, source);

        assertThat(notes).singleElement().asString().contains("Skill \"Kubernetes\" was not found");
    }

    @Test
    void verifierCatchesInventedValuesAndToleratesExtractionNoise() {
        String source = "Software Engineer at Example Co\nImproved throughput by 30% using con-\ncurrent workers";
        JsonNode doc = JSON.readTree("""
                {"basics": {"name": "Someone Else"},
                 "work": [{"name": "Example Co", "position": "Senior Software Engineer",
                   "highlights": [
                     {"text": "Improved throughput by 30% using concurrent workers",
                      "metrics": [{"value": "30", "label": "throughput"}, {"value": "45", "label": "invented"}]}]}]}""");

        List<String> notes = new ImportVerifier().verify(doc, source);

        assertThat(notes).hasSize(3)
                .anySatisfy(n -> assertThat(n).contains("Name \"Someone Else\""))
                .anySatisfy(n -> assertThat(n).contains("Job title", "Senior Software Engineer"))
                .anySatisfy(n -> assertThat(n).contains(": 45 was not found"));
    }
}

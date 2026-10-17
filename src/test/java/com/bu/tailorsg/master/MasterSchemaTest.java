package com.bu.tailorsg.master;

import com.bu.tailorsg.llm.JsonSchemaService;
import com.bu.tailorsg.llm.JsonSchemaService.SchemaViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.bu.tailorsg.master.MasterFixtures.set;
import static org.assertj.core.api.Assertions.assertThat;

/** The JSON Schema on its own: what it accepts and what it rejects (and where). */
class MasterSchemaTest {

    private final JsonSchemaService schemas = new JsonSchemaService(new DefaultResourceLoader(), MasterFixtures.JSON);

    private List<SchemaViolation> violations(ObjectNode doc) {
        return schemas.violations(MasterResumeService.SCHEMA, doc);
    }

    @Test
    void sampleIsValid() {
        assertThat(violations(MasterFixtures.sample())).isEmpty();
    }

    @Test
    void currentRoleMayHaveNullOrMissingEndDate() {
        ObjectNode doc = MasterFixtures.sample();
        assertThat(doc.at("/work/0/endDate").isNull()).isTrue();
        assertThat(violations(MasterFixtures.remove(doc, "/work/0/endDate"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            phone without country code        | /basics/phone                        | "91234567"           | /basics/phone
            month 13                           | /work/0/startDate                    | "2024-13"            | /work/0/startDate
            date written out                   | /work/1/endDate                      | "Dec 2024"           | /work/1/endDate
            priority above 5                   | /work/0/highlights/0/priority        | 6                    | /work/0/highlights/0/priority
            unknown employment type            | /work/0/employmentType               | "Freelance"          | /work/0/employmentType
            unknown skill category             | /skills/0/category                   | "Misc"               | /skills/0/category
            evidence that is not a highlight   | /skills/0/evidenceIds/0              | "work-1"             | /skills/0/evidenceIds/0
            email without domain               | /basics/email                        | "jordan@"            | /basics/email
            profile url without scheme         | /basics/profiles/1/url               | "github.com/jordan"  | /basics/profiles/1/url
            blank name                         | /basics/name                         | "   "                | /basics/name
            unknown top-level key (typo)       | /skill                               | []                   | ''
            unknown highlight key (typo)       | /work/0/highlights/0/metric          | []                   | /work/0/highlights/0
            metric missing its label           | /work/0/highlights/0/metrics/0       | {"value": "35"}      | /work/0/highlights/0/metrics/0
            lowercase country code             | /basics/location/countryCode         | "sg"                 | /basics/location/countryCode
            unknown work-authorisation status  | /basics/workAuthorization/status     | "VISA"               | /basics/workAuthorization/status
            """)
    void rejectsAndPointsAtTheProblem(String description, String pointer, String value, String expectedPath) {
        List<SchemaViolation> found = violations(set(MasterFixtures.sample(), pointer, value));

        assertThat(found).as(description).isNotEmpty();
        assertThat(found).extracting(SchemaViolation::path).as(description).contains(expectedPath);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            /work/0/startDate | "May 2026"   | use YYYY-MM, e.g. 2024-05
            /work/1/endDate   | "2024/12"    | or null for a current role
            /basics/phone     | "9123 4567"  | use E.164
            /basics/name      | " "          | must not be blank
            /basics/url       | "example.dev"| must be a full URL
            """)
    void patternErrorsReadAsInstructionsNotRegexes(String pointer, String value, String expected) {
        assertThat(violations(set(MasterFixtures.sample(), pointer, value)))
                .singleElement().satisfies(v -> {
                    assertThat(v.message()).contains(expected).doesNotContain("regex");
                });
    }

    @Test
    void basicsNeedNameAndEmail() {
        ObjectNode doc = MasterFixtures.sample();
        MasterFixtures.remove(doc, "/basics/email");

        assertThat(violations(doc)).anySatisfy(v -> {
            assertThat(v.keyword()).isEqualTo("required");
            assertThat(v.property()).isEqualTo("email");
        });
    }
}

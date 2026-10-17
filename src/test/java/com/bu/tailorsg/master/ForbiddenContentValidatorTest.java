package com.bu.tailorsg.master;

import com.bu.tailorsg.master.ValidationError.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.bu.tailorsg.master.MasterFixtures.set;
import static org.assertj.core.api.Assertions.assertThat;

/** P6: data that must never be stored, by key and by value pattern, plus look-alikes that must pass. */
class ForbiddenContentValidatorTest {

    private final ForbiddenContentValidator validator = new ForbiddenContentValidator();

    private static ObjectNode withHighlightText(String text) {
        return set(MasterFixtures.sample(), "/work/0/highlights/0/text", MasterFixtures.JSON.writeValueAsString(text));
    }

    @Test
    void sampleIsClean() {
        assertThat(validator.check(MasterFixtures.sample())).isEmpty();
    }

    @ParameterizedTest(name = "key {0}")
    @CsvSource({
            "/basics/dateOfBirth, '\"1995-01-01\"'",
            "/basics/date_of_birth, '\"1995-01-01\"'",
            "/basics/NRIC, '\"S1234567D\"'",
            "/basics/photo, '\"me.jpg\"'",
            "/basics/image, '\"https://example.com/me.jpg\"'",
            "/basics/Marital-Status, '\"single\"'",
            "/basics/religion, '\"x\"'",
            "/basics/location/address, '\"1 Example Road\"'",
            "/basics/location/postalCode, '\"123456\"'",
            "/work/0/salary, 5000",
            "/expectedSalary, 6000",
            "/referees, '[]'"
    })
    void rejectsForbiddenKeysAnywhere(String pointer, String value) {
        List<ValidationError> errors = validator.check(set(MasterFixtures.sample(), pointer, value));

        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo(pointer);
            assertThat(e.kind()).isEqualTo(Kind.PRIVACY);
            assertThat(e.message()).contains("P6");
        });
    }

    @ParameterizedTest(name = "value: {0}")
    @CsvSource(delimiter = '|', textBlock = """
            Verified identity with NRIC S1234567D for onboarding | NRIC/FIN
            DOB: 01/02/1995                                      | date of birth
            Date of birth 1 Feb 1995                             | date of birth
            Age: 29                                              | date of birth
            29 years old engineer                                | date of birth
            Marital status: Single                               | personal-particulars
            Religion - none                                      | personal-particulars
            Blk 123 Ang Mo Kio Avenue 3 #05-123 Singapore 560123 | residential address
            #05-123, 560123                                      | residential address
            """)
    void rejectsForbiddenValuePatterns(String text, String expectedLabel) {
        List<ValidationError> errors = validator.check(withHighlightText(text));

        assertThat(errors).isNotEmpty().allSatisfy(e -> {
            assertThat(e.path()).isEqualTo("/work/0/highlights/0/text");
            assertThat(e.kind()).isEqualTo(Kind.PRIVACY);
        });
        assertThat(errors).anySatisfy(e -> assertThat(e.message()).contains(expectedLabel));
    }

    @ParameterizedTest(name = "allowed: {0}")
    @ValueSource(strings = {
            "Built image-processing scripts in Adobe Photoshop and Python",  // "dob" inside Adobe, "image" as a word
            "Cut storage 20 GB by compressing logs",                          // "age 20" inside "storage 20"
            "Usage rose 45% after the redesign",                              // "age 45" inside "Usage"
            "Built a single-page application with React",                     // "single" outside the header
            "Processed 120000 records per night",                             // 6 digits, no address marker
            "Raced against the deadline to ship the release"                  // "race" inside a word
    })
    void allowsLookAlikes(String text) {
        assertThat(validator.check(withHighlightText(text))).isEmpty();
    }

    @Test
    void maritalWordsAreOnlyRejectedInTheHeader() {
        assertThat(validator.check(set(MasterFixtures.sample(), "/basics/label", "\"Software engineer, married\"")))
                .singleElement().satisfies(e -> assertThat(e.path()).isEqualTo("/basics/label"));
    }
}

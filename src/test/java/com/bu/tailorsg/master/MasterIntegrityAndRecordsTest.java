package com.bu.tailorsg.master;

import com.bu.tailorsg.master.MasterResume.Experience;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bu.tailorsg.master.MasterFixtures.set;
import static org.assertj.core.api.Assertions.assertThat;

/** The typed records, and the cross-field integrity rules that run on them. */
class MasterIntegrityAndRecordsTest {

    private final MasterIntegrityValidator integrity = new MasterIntegrityValidator();

    private static MasterResume typed(JsonNode doc) {
        return MasterFixtures.JSON.treeToValue(doc, MasterResume.class);
    }

    @Test
    void recordsExposeTheSample() {
        MasterResume resume = typed(MasterFixtures.sample());

        assertThat(resume.basics().name()).isEqualTo("Jordan Tan");
        assertThat(resume.basics().workAuthorization().status())
                .isEqualTo(MasterResume.WorkAuthorization.Status.EP_REQUIRED);
        assertThat(resume.work()).extracting(Experience::isCurrent).containsExactly(true, false);
        assertThat(resume.highlightsById()).hasSize(11).containsKeys("work-1.h1", "proj-2.h2", "vol-1.h1");
        assertThat(resume.work().get(1).highlights().get(2).metrics()).isEmpty(); // missing list becomes empty
    }

    @Test
    void recordsCoverEverySchemaFieldSoNothingIsLostInARoundTrip() {
        JsonNode original = MasterFixtures.sample();

        JsonNode roundTripped = MasterFixtures.JSON.valueToTree(typed(original));

        assertThat(withoutNullsAndEmpties(roundTripped)).isEqualTo(withoutNullsAndEmpties(original));
    }

    @Test
    void sampleHasNoIntegrityErrorsAndFlagsTheUnevidencedSkill() {
        MasterIntegrityValidator.Outcome outcome = integrity.check(typed(MasterFixtures.sample()));

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.notes()).singleElement().asString().contains("Kubernetes", "unevidenced");
    }

    @Test
    void evidenceMustPointAtAnExistingHighlight() {
        ObjectNode doc = set(MasterFixtures.sample(), "/skills/0/evidenceIds/1", "\"work-1.h9\"");

        assertThat(integrity.check(typed(doc)).errors()).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("/skills/0/evidenceIds/1");
            assertThat(e.message()).contains("work-1.h9");
        });
    }

    @Test
    void endDateCannotBeBeforeStartDate() {
        ObjectNode doc = set(MasterFixtures.sample(), "/education/1/endDate", "\"2018-06\"");

        assertThat(integrity.check(typed(doc)).errors()).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("/education/1/endDate");
            assertThat(e.message()).contains("before it starts");
        });
    }

    /** Removes null fields and empty arrays recursively, mirroring @JsonInclude(NON_EMPTY). */
    private static JsonNode withoutNullsAndEmpties(JsonNode node) {
        JsonNode copy = node.deepCopy();
        prune(copy);
        return copy;
    }

    private static void prune(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> drop = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : object.properties()) {
                prune(e.getValue());
                if (e.getValue().isNull() || (e.getValue().isArray() && e.getValue().isEmpty())) {
                    drop.add(e.getKey());
                }
            }
            drop.forEach(object::remove);
        } else if (node instanceof ArrayNode array) {
            array.values().forEach(MasterIntegrityAndRecordsTest::prune);
        }
    }
}

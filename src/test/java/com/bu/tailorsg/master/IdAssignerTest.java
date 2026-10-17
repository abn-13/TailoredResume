package com.bu.tailorsg.master;

import com.bu.tailorsg.master.IdAssigner.Result;
import com.bu.tailorsg.master.ValidationError.Kind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.bu.tailorsg.master.MasterFixtures.remove;
import static com.bu.tailorsg.master.MasterFixtures.set;
import static org.assertj.core.api.Assertions.assertThat;

/** ID rules (spec 2.1): server-assigned, stable, never reused. */
class IdAssignerTest {

    private final IdAssigner assigner = new IdAssigner();

    /** Counters as they would be right after saving the sample. */
    private static Map<String, Long> sampleCounters() {
        Map<String, Long> counters = new HashMap<>(Map.of(
                "sum", 2L, "work", 2L, "proj", 2L, "edu", 2L, "vol", 1L, "skill", 13L, "cert", 1L, "award", 1L, "extra", 1L));
        counters.putAll(Map.of("work-1.h", 3L, "work-2.h", 3L, "proj-1.h", 2L, "proj-2.h", 2L, "vol-1.h", 1L));
        return counters;
    }

    private Result assignAgainstSample(ObjectNode candidate) {
        return assigner.assign(candidate, IdAssigner.collectIds(MasterFixtures.sample()), sampleCounters(), Set.of());
    }

    @Test
    void unchangedSampleKeepsEveryIdAndIssuesNone() {
        Result result = assignAgainstSample(MasterFixtures.sample());

        assertThat(result.errors()).isEmpty();
        assertThat(result.assignedIds()).isEmpty();
        assertThat(result.document()).isEqualTo(MasterFixtures.sample());
    }

    @Test
    void newItemAndItsHighlightsGetNextIdsWithIdFirst() {
        ObjectNode doc = set(MasterFixtures.sample(), "/work/-", """
                {"name": "New Co", "position": "Engineer", "startDate": "2025-01",
                 "highlights": [{"text": "Did one thing"}, {"text": "Did another"}]}""");

        Result result = assignAgainstSample(doc);

        assertThat(result.errors()).isEmpty();
        assertThat(result.assignedIds()).containsExactly("work-3", "work-3.h1", "work-3.h2");
        assertThat(result.document().at("/work/2").propertyNames().iterator().next()).isEqualTo("id");
        assertThat(result.document().at("/work/2/highlights/1/id").stringValue()).isEqualTo("work-3.h2");
        assertThat(result.counterUpdates()).containsEntry("work", 3L).containsEntry("work-3.h", 2L);
    }

    @Test
    void newHighlightInExistingItemContinuesThatItemsCounter() {
        ObjectNode doc = set(MasterFixtures.sample(), "/work/0/highlights/-", "{\"text\": \"Added later\"}");

        assertThat(assignAgainstSample(doc).assignedIds()).containsExactly("work-1.h4");
    }

    @Test
    void deletedHighlightNumberIsSkippedNotReused() {
        // work-1.h4 was issued once and later deleted: the counter remembers it
        Map<String, Long> counters = sampleCounters();
        counters.put("work-1.h", 4L);
        ObjectNode doc = set(MasterFixtures.sample(), "/work/0/highlights/-", "{\"text\": \"Added later\"}");

        Result result = assigner.assign(doc, IdAssigner.collectIds(MasterFixtures.sample()), counters, Set.of());

        assertThat(result.assignedIds()).containsExactly("work-1.h5");
    }

    @Test
    void placeholdersAreReplacedAndEvidenceReferencesFollow() {
        ObjectNode doc = set(MasterFixtures.sample(), "/work/0/highlights/-", """
                {"id": "NEW-1", "text": "Wrote Kubernetes manifests for the refund service"}""");
        set(doc, "/skills/10/evidenceIds", "[\"NEW-1\"]"); // skill-11 Kubernetes

        Result result = assignAgainstSample(doc);

        assertThat(result.errors()).isEmpty();
        assertThat(result.document().at("/work/0/highlights/3/id").stringValue()).isEqualTo("work-1.h4");
        assertThat(result.document().at("/skills/10/evidenceIds/0").stringValue()).isEqualTo("work-1.h4");
    }

    @Test
    void unknownPlaceholderReferenceIsAnError() {
        ObjectNode doc = set(MasterFixtures.sample(), "/skills/10/evidenceIds", "[\"NEW-7\"]");

        assertThat(assignAgainstSample(doc).errors()).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("/skills/10/evidenceIds/0");
            assertThat(e.kind()).isEqualTo(Kind.IDS);
        });
    }

    @Test
    void placeholderUsedTwiceIsAnError() {
        ObjectNode doc = set(MasterFixtures.sample(), "/awards/-", "{\"id\": \"NEW-1\", \"title\": \"A\"}");
        set(doc, "/awards/-", "{\"id\": \"NEW-1\", \"title\": \"B\"}");

        assertThat(assignAgainstSample(doc).errors()).anySatisfy(e -> assertThat(e.message()).contains("more than once"));
    }

    @Test
    void retiredIdCannotBeTypedBackIn() {
        // Current version no longer has work-2, but the counter shows it was issued
        Set<String> current = IdAssigner.collectIds(remove(MasterFixtures.sample(), "/work/1"));
        ObjectNode candidate = MasterFixtures.sample(); // still contains work-2

        Result result = assigner.assign(candidate, current, sampleCounters(), Set.of());

        assertThat(result.errors()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("/work/1/id");
            assertThat(e.message()).contains("retired");
        });
    }

    @Test
    void retiredIdsAreAllowedWhenRestoringTheVersionTheyCameFrom() {
        Set<String> current = IdAssigner.collectIds(remove(MasterFixtures.sample(), "/work/1"));
        JsonNode old = MasterFixtures.sample();

        Result result = assigner.assign(old, current, sampleCounters(), IdAssigner.collectIds(old));

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void neverIssuedExplicitIdIsAcceptedAndLaterIdsSkipPastIt() {
        ObjectNode doc = set(MasterFixtures.sample(), "/awards/-", "{\"id\": \"award-9\", \"title\": \"Typed by hand\"}");
        set(doc, "/awards/-", "{\"title\": \"Needs an id\"}");

        Result result = assignAgainstSample(doc);

        assertThat(result.errors()).isEmpty();
        assertThat(result.assignedIds()).containsExactly("award-10");
        assertThat(result.counterUpdates()).containsEntry("award", 10L);
    }

    @Test
    void highlightCannotMoveToAnotherItem() {
        ObjectNode doc = MasterFixtures.sample();
        JsonNode moved = doc.at("/work/0/highlights/0").deepCopy();
        remove(doc, "/work/0/highlights/0");
        set(doc, "/work/1/highlights/-", MasterFixtures.text(moved));

        assertThat(assignAgainstSample(doc).errors()).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("/work/1/highlights/3/id");
            assertThat(e.message()).contains("belongs to work-1");
        });
    }

    @Test
    void idWithAnotherSectionsPrefixIsRejected() {
        ObjectNode doc = set(MasterFixtures.sample(), "/projects/0/id", "\"work-7\"");

        assertThat(assignAgainstSample(doc).errors()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("/projects/0/id");
            assertThat(e.message()).contains("proj-<number>");
        });
    }

    @Test
    void duplicateIdIsRejected() {
        ObjectNode doc = set(MasterFixtures.sample(), "/skills/1/id", "\"skill-1\"");

        assertThat(assignAgainstSample(doc).errors()).anySatisfy(e -> assertThat(e.message()).contains("Duplicate id 'skill-1'"));
    }

    @Test
    void malformedSectionsAreLeftForTheSchemaToReport() {
        ObjectNode doc = set(MasterFixtures.sample(), "/work", "\"not a list\"");
        set(doc, "/projects/0/id", "42");

        Result result = assignAgainstSample(doc);

        assertThat(result.errors()).isEmpty();
        assertThat(result.document().at("/work").stringValue()).isEqualTo("not a list");
    }

    @Test
    void inputIsNeverModified() {
        ObjectNode doc = set(MasterFixtures.sample(), "/work/-", "{\"name\": \"X\", \"position\": \"Y\", \"startDate\": \"2025-01\"}");
        String before = MasterFixtures.text(doc);

        assignAgainstSample(doc);

        assertThat(MasterFixtures.text(doc)).isEqualTo(before);
    }
}

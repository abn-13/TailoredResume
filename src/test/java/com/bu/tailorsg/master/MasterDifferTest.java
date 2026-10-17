package com.bu.tailorsg.master;

import com.bu.tailorsg.master.MasterDiffer.Change;
import com.bu.tailorsg.master.MasterDiffer.Type;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.bu.tailorsg.master.MasterFixtures.remove;
import static com.bu.tailorsg.master.MasterFixtures.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MasterDifferTest {

    private final MasterDiffer differ = new MasterDiffer();

    private List<Change> diff(ObjectNode after) {
        return differ.diff(MasterFixtures.sample(), after);
    }

    @Test
    void serverStampedMetaIsIgnored() {
        ObjectNode after = set(MasterFixtures.sample(), "/meta/version", "7");
        set(after, "/meta/updatedAt", "\"2027-01-01T00:00:00Z\"");

        assertThat(diff(after)).isEmpty();
    }

    @Test
    void fieldChangeIsAddressedById() {
        List<Change> changes = diff(set(MasterFixtures.sample(), "/work/0/highlights/1/text", "\"Built refund APIs\""));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(Type.CHANGED);
            assertThat(c.path()).isEqualTo("/work/work-1/highlights/work-1.h2/text");
            assertThat(c.after().stringValue()).isEqualTo("Built refund APIs");
        });
    }

    @Test
    void insertingAJobAtTheTopIsOneAdditionNotAPileOfChanges() {
        ObjectNode after = MasterFixtures.sample();
        ((tools.jackson.databind.node.ArrayNode) after.get("work")).insert(0, MasterFixtures.JSON.readTree(
                "{\"id\": \"work-3\", \"name\": \"New Co\", \"position\": \"Engineer\", \"startDate\": \"2027-02\"}"));

        assertThat(diff(after)).extracting(Change::type, Change::path)
                .containsExactly(tuple(Type.ADDED, "/work/work-3"));
    }

    @Test
    void removedItemIsReportedWithItsOldContent() {
        List<Change> changes = diff(remove(MasterFixtures.sample(), "/certificates/0"));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(Type.REMOVED);
            assertThat(c.path()).isEqualTo("/certificates/cert-1");
            assertThat(c.before().path("name").stringValue()).contains("AWS");
        });
    }

    @Test
    void reorderingIsReportedOnce() {
        ObjectNode after = MasterFixtures.sample();
        var work = after.at("/work").deepCopy();
        set(after, "/work/0", MasterFixtures.text(work.get(1)));
        set(after, "/work/1", MasterFixtures.text(work.get(0)));

        assertThat(diff(after)).extracting(Change::type, Change::path)
                .containsExactly(tuple(Type.REORDERED, "/work"));
    }

    @Test
    void stringListsReportIndividualValues() {
        List<Change> changes = diff(set(MasterFixtures.sample(), "/work/0/highlights/0/skills",
                "[\"Java\", \"Redis\", \"Grafana\"]")); // removed Spring Boot, added Grafana

        assertThat(changes).extracting(Change::type, c -> c.before() == null ? c.after().stringValue() : c.before().stringValue())
                .containsExactly(tuple(Type.REMOVED, "Spring Boot"), tuple(Type.ADDED, "Grafana"));
    }

    @Test
    void listsOfObjectsWithoutIdsAreShownWhole() {
        List<Change> changes = diff(set(MasterFixtures.sample(), "/work/0/highlights/0/metrics/0/value", "\"40\""));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(Type.CHANGED);
            assertThat(c.path()).isEqualTo("/work/work-1/highlights/work-1.h1/metrics");
        });
    }
}

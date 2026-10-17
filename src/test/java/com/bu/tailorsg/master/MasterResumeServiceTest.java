package com.bu.tailorsg.master;

import com.bu.tailorsg.master.MasterDiffer.Type;
import com.bu.tailorsg.master.MasterResumeService.SaveResult;
import com.bu.tailorsg.master.ValidationError.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.bu.tailorsg.master.MasterFixtures.remove;
import static com.bu.tailorsg.master.MasterFixtures.set;
import static com.bu.tailorsg.master.MasterFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole save pipeline against a real (in-memory) database. {@code @Transactional} on a test class
 * rolls back after each test, so every test starts with an empty master resume.
 */
@SpringBootTest
@Transactional
class MasterResumeServiceTest {

    @Autowired
    private MasterResumeService service;

    @Autowired
    private IdCounterRepository counters;

    private SaveResult saveSample() {
        return service.save(MasterFixtures.sampleText(), "sample");
    }

    private JsonNode currentJson() {
        return service.current().orElseThrow().json();
    }

    @Test
    void firstSaveCreatesVersionOneAndRecordsCounters() {
        SaveResult result = saveSample();

        assertThat(result.versionNo()).isEqualTo(1);
        assertThat(result.unchanged()).isFalse();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("Kubernetes"));
        assertThat(currentJson().at("/meta/version").asInt()).isEqualTo(1);
        assertThat(counters.findById("work")).hasValueSatisfying(c -> assertThat(c.getLastValue()).isEqualTo(2));
        assertThat(counters.findById("work-1.h")).hasValueSatisfying(c -> assertThat(c.getLastValue()).isEqualTo(3));
    }

    @Test
    void validateRunsEverythingButSavesNothing() {
        SaveResult result = service.validate(MasterFixtures.sampleText());

        assertThat(result.versionNo()).isEqualTo(1);
        assertThat(service.current()).isEmpty();
    }

    @Test
    void savingIdenticalContentDoesNotCreateAVersion() {
        saveSample();

        SaveResult again = service.save(text(currentJson()), "no-op");

        assertThat(again.unchanged()).isTrue();
        assertThat(again.versionNo()).isEqualTo(1);
        assertThat(service.history()).hasSize(1);
    }

    @Test
    void metaIsServerOwnedAndDefaultsAreFilledIn() {
        SaveResult result = service.save(text(set(MasterFixtures.minimal(), "/meta", "{\"version\": 99}")), null);

        JsonNode meta = result.document().get("meta");
        assertThat(meta.get("version").asInt()).isEqualTo(1);
        assertThat(meta.get("locale").stringValue()).isEqualTo("en-SG");
        assertThat(meta.get("spelling").stringValue()).isEqualTo("british");
        assertThat(meta.get("updatedAt").stringValue()).endsWith("Z");
        assertThat(result.document().propertyNames().iterator().next()).isEqualTo("meta");
    }

    @Test
    void newItemsWithoutIdsGetTheNextIds() {
        saveSample();
        ObjectNode doc = (ObjectNode) currentJson();
        set(doc, "/work/-", """
                {"name": "Later Co", "position": "Engineer", "startDate": "2027-02",
                 "highlights": [{"text": "Shipped the first release"}]}""");

        SaveResult result = service.save(text(doc), "added a job");

        assertThat(result.versionNo()).isEqualTo(2);
        assertThat(result.assignedIds()).containsExactly("work-3", "work-3.h1");
        assertThat(currentJson().at("/work/2/id").stringValue()).isEqualTo("work-3");
    }

    @Test
    void aDeletedIdIsNeverHandedOutAgain() {
        saveSample();
        service.save(text(remove((ObjectNode) currentJson(), "/certificates/0")), "removed cert-1");

        // Typing the old ID back in is rejected ...
        ObjectNode retyped = set((ObjectNode) currentJson(), "/certificates/-",
                "{\"id\": \"cert-1\", \"name\": \"Other cert\", \"issuer\": \"X\"}");
        assertThatThrownBy(() -> service.save(text(retyped), null))
                .isInstanceOfSatisfying(MasterValidationException.class, e ->
                        assertThat(e.errors()).anySatisfy(err -> assertThat(err.message()).contains("retired")));

        // ... and a new certificate gets cert-2, not cert-1
        ObjectNode added = set((ObjectNode) currentJson(), "/certificates/-", "{\"name\": \"Other cert\", \"issuer\": \"X\"}");
        assertThat(service.save(text(added), null).assignedIds()).containsExactly("cert-2");
    }

    @Test
    void restoreBringsBackAnOldVersionIncludingItsIds() {
        saveSample();
        service.save(text(remove((ObjectNode) currentJson(), "/certificates/0")), "removed cert-1");

        SaveResult restored = service.restore(1);

        assertThat(restored.versionNo()).isEqualTo(3);
        assertThat(currentJson().at("/certificates/0/id").stringValue()).isEqualTo("cert-1"); // retired, but allowed here
        assertThat(service.history().getFirst().getChangeNote()).isEqualTo("Restored from version 1");
    }

    @Test
    void deletingAHighlightThatASkillCitesIsRefused() {
        saveSample();

        assertThatThrownBy(() -> service.save(text(remove((ObjectNode) currentJson(), "/work/1")), "removed work-2"))
                .isInstanceOfSatisfying(MasterValidationException.class, e ->
                        assertThat(e.errors()).allSatisfy(err -> {
                            assertThat(err.kind()).isEqualTo(Kind.INTEGRITY);
                            assertThat(err.message()).contains("work-2.h");
                        }));
    }

    @Test
    void errorsFromEveryStageAreReportedTogetherWithoutDuplicates() {
        ObjectNode doc = MasterFixtures.sample();
        set(doc, "/basics/dateOfBirth", "\"1995-01-01\"");   // privacy (and, silently, additionalProperties)
        set(doc, "/basics/phone", "\"91234567\"");            // schema
        set(doc, "/projects/0/id", "\"work-9\"");             // ids (and, silently, nothing else)

        assertThatThrownBy(() -> service.save(text(doc), null))
                .isInstanceOfSatisfying(MasterValidationException.class, e -> {
                    assertThat(e.errors()).extracting(ValidationError::kind)
                            .contains(Kind.PRIVACY, Kind.SCHEMA, Kind.IDS);
                    assertThat(e.errors()).filteredOn(err -> err.path().contains("dateOfBirth")).hasSize(1);
                });
        assertThat(service.current()).isEmpty();
    }

    @Test
    void syntaxErrorsGiveLineAndColumn() {
        assertThatThrownBy(() -> service.save("{\n  \"basics\": {\n    \"name\": \"A\",,\n  }\n}", null))
                .isInstanceOfSatisfying(MasterValidationException.class, e ->
                        assertThat(e.errors()).singleElement().satisfies(err -> {
                            assertThat(err.kind()).isEqualTo(Kind.SYNTAX);
                            assertThat(err.message()).startsWith("Line 3, column");
                        }));
    }

    @Test
    void duplicateKeysAreRejectedInsteadOfSilentlyDropped() {
        String json = "{\"basics\": {\"name\": \"A\", \"name\": \"B\", \"email\": \"a@example.com\"}}";

        assertThatThrownBy(() -> service.save(json, null))
                .isInstanceOfSatisfying(MasterValidationException.class, e ->
                        assertThat(e.errors().getFirst().message()).contains("Duplicate", "name"));
    }

    @Test
    void sampleCanOnlyBeLoadedIntoAnEmptyDatabase() {
        assertThat(service.loadSample().versionNo()).isEqualTo(1);

        assertThatThrownBy(() -> service.loadSample()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void historyIsNewestFirstAndDiffComparesVersions() {
        saveSample();
        service.save(text(set((ObjectNode) currentJson(), "/basics/label", "\"Backend Engineer\"")), "new label");

        assertThat(service.history()).extracting(MasterResumeVersionRepository.VersionSummary::getVersionNo)
                .containsExactly(2, 1);
        assertThat(service.diff(1, 2)).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(Type.CHANGED);
            assertThat(c.path()).isEqualTo("/basics/label");
        });
        assertThatThrownBy(() -> service.diff(1, 9)).isInstanceOf(VersionNotFoundException.class);
    }

    @Test
    void longChangeNotesAreTrimmed() {
        service.save(MasterFixtures.sampleText(), "x".repeat(500));

        assertThat(service.history().getFirst().getChangeNote()).hasSize(MasterResumeService.MAX_NOTE_LENGTH);
    }
}

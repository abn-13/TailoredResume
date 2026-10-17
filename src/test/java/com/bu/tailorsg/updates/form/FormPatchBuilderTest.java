package com.bu.tailorsg.updates.form;

import com.bu.tailorsg.json.JsonPatch;
import com.bu.tailorsg.updates.form.FormPatchBuilder.Built;
import com.bu.tailorsg.updates.form.FormPatchBuilder.FieldProblem;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Form to JSON Patch, against the fake sample master (work-1 started 2026-05, work-2 2021-07). */
class FormPatchBuilderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FormPatchBuilder builder = new FormPatchBuilder();

    private static JsonNode sample() {
        try {
            return JSON.readTree(new DefaultResourceLoader().getResource("classpath:samples/master-sample.json")
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EntryForm.Work job(String start) {
        EntryForm.Work form = new EntryForm.Work();
        form.setName("Later Co");
        form.setPosition("Engineer");
        form.setStartDate(start);
        return form;
    }

    @Test
    void workIsInsertedNewestFirstAndBulletsAreCleaned() {
        EntryForm.Work form = job("2024-01");
        form.setEndDate("2025-12");
        form.setHighlights("• Built the thing\n\n- Shipped it\n3) Measured it\n   ");

        Built built = builder.build(form, sample());

        assertThat(built.patch()).hasSize(1);
        JsonNode op = built.patch().get(0);
        assertThat(op.path("op").stringValue()).isEqualTo("add");
        assertThat(op.path("path").stringValue()).isEqualTo("/work/1"); // between 2026-05 and 2021-07
        assertThat(op.at("/value/highlights").values().stream().map(h -> h.path("text").stringValue()).toList())
                .containsExactly("Built the thing", "Shipped it", "Measured it");
        assertThat(op.at("/value").has("id")).isFalse(); // the server assigns IDs on apply
        assertThat(op.at("/value").has("location")).isFalse(); // blank fields are left out
        assertThat(built.summary()).isEqualTo("Add work: Engineer at Later Co (2024-01 to 2025-12)");
    }

    @Test
    void newestJobGoesFirstAndOldestIsAppended() {
        assertThat(builder.build(job("2027-01"), sample()).patch().get(0).path("path").stringValue()).isEqualTo("/work/0");
        assertThat(builder.build(job("2019-01"), sample()).patch().get(0).path("path").stringValue()).isEqualTo("/work/-");
    }

    @Test
    void endBeforeStartIsAFieldProblem() {
        EntryForm.Work form = job("2024-01");
        form.setEndDate("2023-01");

        assertThatThrownBy(() -> builder.build(form, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> assertThat(p.field()).isEqualTo("endDate"));
    }

    @Test
    void missingListIsCreatedRatherThanAppendedTo() {
        JsonNode master = JSON.readTree("{\"basics\": {\"name\": \"A\", \"email\": \"a@example.com\"}}");
        EntryForm.Award form = new EntryForm.Award();
        form.setTitle("Dean's List");

        JsonNode op = builder.build(form, master).patch().get(0);

        assertThat(op.path("path").stringValue()).isEqualTo("/awards");
        assertThat(op.path("value").isArray()).isTrue();
    }

    @Test
    void skillRejectsDuplicatesCaseInsensitivelyAndUnknownEvidence() {
        EntryForm.Skill dup = new EntryForm.Skill();
        dup.setName("  java ");
        dup.setCategory("Languages");
        assertThatThrownBy(() -> builder.build(dup, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> {
                    assertThat(p.field()).isEqualTo("name");
                    assertThat(p.getMessage()).contains("Java (skill-1)");
                });

        EntryForm.Skill badEvidence = new EntryForm.Skill();
        badEvidence.setName("Grafana");
        badEvidence.setCategory("Tools");
        badEvidence.setEvidenceIds(List.of("work-1.h9"));
        assertThatThrownBy(() -> builder.build(badEvidence, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> assertThat(p.field()).isEqualTo("evidenceIds"));
    }

    @Test
    void highlightIsGuardedAndBecomesEvidenceForExistingAndNewSkills() {
        EntryForm.Highlight form = new EntryForm.Highlight();
        form.setParentId("work-1");
        form.setText("Added Grafana alerts on Redis memory, cutting incidents by 50%");
        form.setMetricValue("50");
        form.setMetricUnit("%");
        form.setMetricLabel("incident reduction");
        form.setSkills(List.of("Redis"));
        form.getNewSkills().get(0).setName("Grafana");
        form.getNewSkills().get(0).setCategory("Tools");
        form.setPriority(2);

        Built built = builder.build(form, sample());

        assertThat(built.patch().values().stream().map(op -> op.path("op").stringValue() + " " + op.path("path").stringValue()).toList())
                .containsExactly(
                        "test /work/0/id",
                        "add /work/0/highlights/-",
                        "test /skills/6/id",              // skill-7 Redis
                        "add /skills/6/evidenceIds/-",
                        "add /skills/-");                 // new skill Grafana
        JsonNode highlight = built.patch().get(1).path("value");
        assertThat(highlight.path("id").stringValue()).isEqualTo("NEW-1");
        assertThat(highlight.at("/metrics/0/value").stringValue()).isEqualTo("50");
        assertThat(highlight.path("skills").toString()).isEqualTo("[\"Redis\",\"Grafana\"]");
        assertThat(built.patch().get(4).at("/value/evidenceIds/0").stringValue()).isEqualTo("NEW-1");
        assertThat(built.summary()).startsWith("Add highlight to work-1 (Software Engineering Intern, Example Fintech Pte Ltd)");

        // and the patch really applies to the master it was built from
        JsonNode patched = JsonPatch.apply(built.patch(), sample());
        assertThat(patched.at("/work/0/highlights").size()).isEqualTo(4);
        assertThat(patched.at("/skills/6/evidenceIds/1").stringValue()).isEqualTo("NEW-1");
    }

    @Test
    void highlightFieldProblems() {
        EntryForm.Highlight noParent = new EntryForm.Highlight();
        noParent.setParentId("work-9");
        noParent.setText("x");
        assertThatThrownBy(() -> builder.build(noParent, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> assertThat(p.field()).isEqualTo("parentId"));

        EntryForm.Highlight metricNoLabel = new EntryForm.Highlight();
        metricNoLabel.setParentId("work-1");
        metricNoLabel.setText("x");
        metricNoLabel.setMetricValue("50");
        assertThatThrownBy(() -> builder.build(metricNoLabel, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> assertThat(p.field()).isEqualTo("metricLabel"));

        EntryForm.Highlight existingAsNew = new EntryForm.Highlight();
        existingAsNew.setParentId("work-1");
        existingAsNew.setText("x");
        existingAsNew.getNewSkills().get(1).setName("Docker");
        existingAsNew.getNewSkills().get(1).setCategory("Tools");
        assertThatThrownBy(() -> builder.build(existingAsNew, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> assertThat(p.field()).isEqualTo("newSkills[1].name"));

        EntryForm.Highlight noCategory = new EntryForm.Highlight();
        noCategory.setParentId("work-1");
        noCategory.setText("x");
        noCategory.getNewSkills().get(0).setName("Grafana");
        assertThatThrownBy(() -> builder.build(noCategory, sample()))
                .isInstanceOfSatisfying(FieldProblem.class, p -> assertThat(p.field()).isEqualTo("newSkills[0].category"));
    }

    @Test
    void commaListsAreTrimmedAndDeduplicated() {
        assertThat(FormPatchBuilder.commaList(" Java, Spring Boot ,, Java ,")).containsExactly("Java", "Spring Boot");
        assertThat(FormPatchBuilder.commaList("  ")).isEmpty();
    }
}

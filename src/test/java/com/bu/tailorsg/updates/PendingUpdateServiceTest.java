package com.bu.tailorsg.updates;

import com.bu.tailorsg.json.JsonPatchException;
import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import com.bu.tailorsg.master.MasterDiffer.Type;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.ValidationError.Kind;
import com.bu.tailorsg.updates.PendingUpdate.Source;
import com.bu.tailorsg.updates.PendingUpdate.Status;
import com.bu.tailorsg.updates.PendingUpdateService.InvalidProposalException;
import com.bu.tailorsg.updates.PendingUpdateService.Preview;
import com.bu.tailorsg.updates.PendingUpdateService.Proposal;
import com.bu.tailorsg.updates.form.EntryForm;
import com.bu.tailorsg.updates.form.FormPatchBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Propose, preview, edit, apply and reject, against a real (in-memory, rolled back) database. */
@SpringBootTest
@Transactional
class PendingUpdateServiceTest {

    @Autowired private PendingUpdateService updates;
    @Autowired private MasterResumeService master;
    @Autowired private FormPatchBuilder builder;
    @Autowired private JsonMapper json;

    @BeforeEach
    void loadSample() {
        master.loadSample();
    }

    private PendingUpdate proposeHighlight(String text) {
        EntryForm.Highlight form = new EntryForm.Highlight();
        form.setParentId("work-1");
        form.setText(text);
        form.setSkills(List.of("Redis"));
        FormPatchBuilder.Built built = builder.build(form, master.currentDocumentOrEmpty());
        return updates.create(new Proposal(Source.FORM, "{}", built.patch(), built.summary(), List.of()), true);
    }

    @Test
    void proposeThenApplyCreatesAVersionWithRealIds() {
        PendingUpdate update = proposeHighlight("Added Redis eviction alerts for the payment cache");

        Preview preview = updates.preview(update);
        assertThat(preview.canApply()).isTrue();
        assertThat(preview.baseVersionNo()).isEqualTo(1);
        assertThat(preview.assignedIds()).containsExactly("work-1.h4");
        assertThat(preview.changes()).extracting(c -> c.type() + " " + c.path())
                .contains("ADDED /work/work-1/highlights/work-1.h4", "ADDED /skills/skill-7/evidenceIds");

        assertThat(updates.apply(update.getId()).versionNo()).isEqualTo(2);
        PendingUpdate applied = updates.get(update.getId());
        assertThat(applied.getStatus()).isEqualTo(Status.APPLIED);
        assertThat(applied.getAppliedVersionNo()).isEqualTo(2);
        JsonNode current = master.current().orElseThrow().json();
        assertThat(current.at("/skills/6/evidenceIds").toString()).contains("work-1.h4");
        assertThat(master.history().getFirst().getChangeNote()).startsWith("Update #" + update.getId() + ": Add highlight");
    }

    @Test
    void appliedOrRejectedUpdatesCannotChangeAgain() {
        PendingUpdate update = proposeHighlight("Added Redis eviction alerts");
        updates.reject(update.getId());

        assertThat(updates.get(update.getId()).getStatus()).isEqualTo(Status.REJECTED);
        assertThatThrownBy(() -> updates.apply(update.getId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> updates.replacePatch(update.getId(), "[]")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleGuardsStopAPatchFromEditingTheWrongEntry() {
        PendingUpdate update = proposeHighlight("Added Redis eviction alerts");
        // Meanwhile the master changes: the two jobs swap places, so /work/0 is no longer work-1
        ObjectNode doc = (ObjectNode) master.current().orElseThrow().json().deepCopy();
        ArrayNode work = (ArrayNode) doc.get("work");
        JsonNode first = work.remove(0);
        work.add(first);
        master.saveDocument(doc, "reordered");

        Preview preview = updates.preview(update);

        assertThat(preview.canApply()).isFalse();
        assertThat(preview.patchError()).contains("test /work/0/id", "expected \"work-1\" but found \"work-2\"");
        assertThatThrownBy(() -> updates.apply(update.getId())).isInstanceOf(JsonPatchException.class);
        assertThat(updates.get(update.getId()).getStatus()).isEqualTo(Status.PROPOSED);
    }

    @Test
    void formProposalsMustBeValidIncludingPrivacyRules() {
        assertThatThrownBy(() -> proposeHighlight("Verified identity with NRIC S1234567D"))
                .isInstanceOfSatisfying(InvalidProposalException.class, e ->
                        assertThat(e.preview().errors()).anySatisfy(err -> assertThat(err.kind()).isEqualTo(Kind.PRIVACY)));
        assertThat(updates.list(null)).isEmpty();
    }

    @Test
    void importsAreKeptEvenWhenInvalidSoTheyCanBeRepaired() {
        JsonNode patch = json.readTree("[{\"op\": \"replace\", \"path\": \"/basics/phone\", \"value\": \"9123 4567\"}]");

        PendingUpdate update = updates.create(new Proposal(Source.IMPORT, "text", patch, "import", List.of("check phone")), false);

        Preview preview = updates.preview(update);
        assertThat(preview.canApply()).isFalse();
        assertThat(preview.errors()).anySatisfy(e -> assertThat(e.path()).isEqualTo("/basics/phone"));
        assertThat(updates.notesOf(update)).containsExactly("check phone");
    }

    @Test
    void editingThePatchRepairsAnUpdate() {
        JsonNode broken = json.readTree("[{\"op\": \"replace\", \"path\": \"/basics/phone\", \"value\": \"9123 4567\"}]");
        PendingUpdate update = updates.create(new Proposal(Source.IMPORT, "text", broken, "import", List.of()), false);

        Preview fixed = updates.replacePatch(update.getId(),
                "[{\"op\": \"replace\", \"path\": \"/basics/phone\", \"value\": \"+6591234567\"}]");

        assertThat(fixed.canApply()).isTrue();
        assertThat(fixed.changes()).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(Type.CHANGED);
            assertThat(c.path()).isEqualTo("/basics/phone");
        });
        assertThatThrownBy(() -> updates.replacePatch(update.getId(), "[{\"op\": "))
                .isInstanceOf(JsonSyntaxException.class);
        assertThatThrownBy(() -> updates.replacePatch(update.getId(), "{\"op\": \"add\"}"))
                .isInstanceOf(JsonPatchException.class).hasMessageContaining("array");
    }

    @Test
    void previewOnlyListsNotesTheChangeIntroduces() {
        EntryForm.Skill form = new EntryForm.Skill();
        form.setName("Terraform");
        form.setCategory("Cloud/DevOps");
        FormPatchBuilder.Built built = builder.build(form, master.currentDocumentOrEmpty());

        Preview preview = updates.preview(built.patch());

        assertThat(preview.newNotes()).singleElement().asString().contains("Terraform", "unevidenced");
    }

    @Test
    void noOpPatchCannotBeApplied() {
        JsonNode patch = json.readTree("[{\"op\": \"test\", \"path\": \"/basics/name\", \"value\": \"Jordan Tan\"}]");

        Preview preview = updates.preview(patch);

        assertThat(preview.canApply()).isFalse();
        assertThat(preview.blockedReason()).contains("changes nothing");
    }
}

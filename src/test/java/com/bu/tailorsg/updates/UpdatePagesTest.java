package com.bu.tailorsg.updates;

import com.bu.tailorsg.master.MasterResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Add Info forms, the review page and its actions, and the readable master view, with real templates. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UpdatePagesTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private MasterResumeService master;
    @Autowired private PendingUpdateService updates;

    private MvcTestResult submitWork(String position) {
        return mvc.post().uri("/add-info/work")
                .param("name", "Later Co").param("position", position).param("startDate", "2027-02")
                .param("highlights", "Shipped the first release").exchange();
    }

    private long latestUpdateId() {
        return updates.list(null).getFirst().getId();
    }

    @Test
    void addInfoAsksForAMasterResumeFirst() {
        assertThat(mvc.get().uri("/add-info")).hasStatusOk().bodyText()
                .contains("Create your master resume first").doesNotContain("Propose this change");
    }

    @Test
    void highlightFormOffersParentsAndSkills() {
        master.loadSample();

        assertThat(mvc.get().uri("/add-info?type=highlight")).hasStatusOk().bodyText()
                .contains("work-1 · Software Engineering Intern, Example Fintech Pte Ltd", "proj-2 · Hawker Queue Estimator")
                .contains("<option value=\"Kubernetes\">Kubernetes</option>", "newSkills[2].name");
    }

    @Test
    void fieldErrorsAreShownNextToTheirInputs() {
        master.loadSample();

        assertThat(submitWork("")).hasStatusOk().bodyText().contains("Job title is required");
        assertThat(mvc.post().uri("/add-info/skill").param("name", "java").param("category", "Languages"))
                .hasStatusOk().bodyText().contains("You already have the skill Java (skill-1).");
        assertThat(mvc.post().uri("/add-info/highlight").param("parentId", "work-1")
                .param("text", "Verified customers with NRIC S1234567D"))
                .hasStatusOk().bodyText().contains("nothing was proposed", "PRIVACY");
        assertThat(updates.list(null)).isEmpty();
    }

    @Test
    void validFormRedirectsToItsReviewPageWhichCanApply() {
        master.loadSample();

        assertThat(submitWork("Engineer")).hasStatus3xxRedirection()
                .hasRedirectedUrl("/updates/" + latestUpdateId())
                .flash().containsKey("flash");
        long id = latestUpdateId();

        assertThat(mvc.get().uri("/updates/" + id)).hasStatusOk().bodyText()
                .contains("Add work: Engineer at Later Co", "proposed", "work › work-3", "Apply to master resume")
                .doesNotContain("disabled=\"disabled\"");

        assertThat(mvc.post().uri("/updates/" + id + "/apply")).hasRedirectedUrl("/updates/" + id)
                .flash().containsEntry("flash", "Applied. Your master resume is now version 2; new IDs: work-3, work-3.h1.");
        assertThat(mvc.get().uri("/updates/" + id)).bodyText().contains("applied", "applied as");
        assertThat(mvc.get().uri("/updates")).bodyText().contains("Add work: Engineer at Later Co", "as v2");
    }

    @Test
    void patchEditorPreviewsWithoutSavingAndRejectWorks() {
        master.loadSample();
        submitWork("Engineer");
        long id = latestUpdateId();

        assertThat(mvc.post().uri("/updates/" + id + "/preview")
                .param("patch", "[{\"op\":\"replace\",\"path\":\"/basics/label\",\"value\":\"Backend Engineer\"}]"))
                .hasStatusOk().bodyText()
                .contains("not saved", "basics › label", "Backend Engineer").doesNotContain("<html");
        assertThat(mvc.post().uri("/updates/" + id + "/preview").param("patch", "[{\"op\":"))
                .hasStatusOk().bodyText().contains("does not apply", "Line 1");

        assertThat(mvc.post().uri("/updates/" + id + "/reject")).hasRedirectedUrl("/updates/" + id);
        assertThat(mvc.get().uri("/updates/" + id)).bodyText().contains("rejected").doesNotContain("Apply to master resume");
    }

    @Test
    void readableViewShowsTheSampleWithIdsAndEvidence() {
        master.loadSample();

        assertThat(mvc.get().uri("/master/view")).hasStatusOk().bodyText()
                .contains("Jordan Tan", "Software Engineering Intern", "May 2026 - Present", "Jul 2021 - Dec 2024")
                .contains("work-1.h1", "35% (p95 latency reduction)", "no evidence", "shown by default");
    }
}

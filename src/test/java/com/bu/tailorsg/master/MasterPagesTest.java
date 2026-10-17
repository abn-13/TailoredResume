package com.bu.tailorsg.master;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import static com.bu.tailorsg.master.MasterFixtures.set;
import static com.bu.tailorsg.master.MasterFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/** Master Resume pages and htmx fragments render, with real templates. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MasterPagesTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MasterResumeService service;

    @Test
    void emptyEditorOffersTheSample() {
        assertThat(mvc.get().uri("/master")).hasStatusOk().bodyText()
                .contains("No master resume yet", "Load sample (fake data)", "id=\"master-json\"");
    }

    @Test
    void loadSampleRedirectsBackWithAFlashMessage() {
        assertThat(mvc.post().uri("/master/sample"))
                .hasStatus3xxRedirection()
                .hasRedirectedUrl("/master")
                .flash().containsKey("flash");

        assertThat(mvc.get().uri("/master")).hasStatusOk().bodyText()
                .contains("Version <span>1</span>", "Jordan Tan")
                .doesNotContain("Load sample (fake data)");
    }

    @Test
    void validateFragmentListsErrorsWithKindAndPath() {
        String json = text(set(MasterFixtures.sample(), "/basics/phone", "\"91234567\""));

        assertThat(mvc.post().uri("/master/validate").param("json", json).header("HX-Request", "true"))
                .hasStatusOk().bodyText()
                .contains("1 problem. Nothing was saved.", "SCHEMA", "/basics/phone")
                .doesNotContain("<html");
    }

    @Test
    void saveFragmentSwapsInStoredJsonWithNewIds() {
        service.loadSample();
        String json = text(set(MasterFixtures.sample(), "/awards/-", "{\"title\": \"Dean's List\"}"));

        assertThat(mvc.post().uri("/master/save").param("json", json).param("note", "added award"))
                .hasStatusOk().bodyText()
                .contains("Saved as version <span>2</span>", "award-2")
                .contains("id=\"master-json\"", "hx-swap-oob=\"true\"", "id=\"master-version\"");
    }

    @Test
    void historyVersionAndDiffPagesRender() {
        service.loadSample();
        service.save(text(set(MasterFixtures.sample(), "/basics/label", "\"Backend Engineer\"")), "new label");

        assertThat(mvc.get().uri("/master/history")).hasStatusOk().bodyText()
                .contains("v2", "v1", "new label", "current");
        assertThat(mvc.get().uri("/master/versions/1")).hasStatusOk().bodyText()
                .contains("Version <span>1</span>", "Restore as a new version");
        assertThat(mvc.get().uri("/master/diff")).hasStatusOk().bodyText()
                .contains("Changes from v<span>1</span> to v<span>2</span>", "basics › label", "Backend Engineer");
        assertThat(mvc.get().uri("/master/versions/9")).hasStatus(404);
    }

    @Test
    void restoreRedirectsWithFlash() {
        service.loadSample();
        service.save(text(set(MasterFixtures.sample(), "/basics/label", "\"Backend Engineer\"")), "new label");

        assertThat(mvc.post().uri("/master/versions/1/restore"))
                .hasRedirectedUrl("/master")
                .flash().containsEntry("flash", "Restored version 1 as new version 3.");
    }
}

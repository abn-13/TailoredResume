package com.bu.tailorsg.updates;

import com.bu.tailorsg.master.MasterResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UpdatesApiControllerTest {

    private static final String WORK = """
            {"type": "work", "name": "Later Co", "position": "Engineer", "startDate": "2027-02",
             "highlights": "Shipped the first release"}""";

    @Autowired private MockMvcTester mvc;
    @Autowired private MasterResumeService master;
    @Autowired private PendingUpdateService updates;

    private MvcTestResult postForm(String body) {
        return mvc.post().uri("/api/updates/form").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Test
    void formNeedsAMasterResumeFirst() {
        assertThat(postForm(WORK)).hasStatus(409)
                .bodyJson().extractingPath("$.detail").asString().contains("Create your master resume first");
    }

    @Test
    void formBecomesAProposedUpdateWithAPreview() {
        master.loadSample();

        assertThat(postForm(WORK)).hasStatus(201)
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo("PROPOSED");
                    assertThat(json).extractingPath("$.sourceType").isEqualTo("FORM");
                    assertThat(json).extractingPath("$.summary").asString().startsWith("Add work: Engineer at Later Co");
                    assertThat(json).extractingPath("$.preview.canApply").isEqualTo(true);
                    assertThat(json).extractingPath("$.preview.changes[0].path").isEqualTo("/work/work-3");
                    assertThat(json).extractingPath("$.preview.assignedIds").asArray().contains("work-3", "work-3.h1");
                });
    }

    @Test
    void invalidFormsExplainEachField() {
        master.loadSample();

        assertThat(postForm("{\"type\": \"work\", \"name\": \"X\", \"startDate\": \"May 2026\"}")).hasStatus(422)
                .bodyJson().extractingPath("$.fieldErrors[*].field").asArray().contains("position", "startDate");
        assertThat(postForm("{\"type\": \"skill\", \"name\": \"Java\", \"category\": \"Languages\"}")).hasStatus(422)
                .bodyJson().extractingPath("$.fieldErrors[0].message").asString().contains("skill-1");
        assertThat(postForm("{\"type\": \"pet\", \"name\": \"Rex\"}")).hasStatus(400);
    }

    @Test
    void listGetEditApplyAndReject() {
        master.loadSample();
        postForm(WORK);
        long id = updates.list(null).getFirst().getId();

        assertThat(mvc.get().uri("/api/updates?status=PROPOSED")).hasStatusOk()
                .bodyJson().extractingPath("$[0].id").isEqualTo((int) id);

        // Edit: change the new job's title through the patch
        String edited = """
                [{"op": "add", "path": "/work/0", "value": {"name": "Later Co", "position": "Staff Engineer", "startDate": "2027-02"}}]""";
        assertThat(mvc.patch().uri("/api/updates/" + id).contentType(MediaType.APPLICATION_JSON).content(edited))
                .hasStatusOk()
                .bodyJson().extractingPath("$.patch[0].value.position").isEqualTo("Staff Engineer");
        assertThat(mvc.patch().uri("/api/updates/" + id).contentType(MediaType.APPLICATION_JSON).content("[{\"op\":"))
                .hasStatus(422);

        assertThat(mvc.post().uri("/api/updates/" + id + "/apply")).hasStatusOk()
                .bodyJson().extractingPath("$.masterVersionNo").isEqualTo(2);
        assertThat(mvc.post().uri("/api/updates/" + id + "/apply")).hasStatus(409);
        assertThat(mvc.post().uri("/api/updates/" + id + "/reject")).hasStatus(409);
        assertThat(mvc.get().uri("/api/updates/" + id)).hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo("APPLIED");
                    assertThat(json).extractingPath("$.preview").isNull();
                });
        assertThat(mvc.get().uri("/api/updates/999")).hasStatus(404);
    }
}

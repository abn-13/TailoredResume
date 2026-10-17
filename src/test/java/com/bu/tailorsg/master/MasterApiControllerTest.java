package com.bu.tailorsg.master;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import static com.bu.tailorsg.master.MasterFixtures.set;
import static com.bu.tailorsg.master.MasterFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/** /api/master endpoints end to end (MockMvc runs in the test's thread, so the rollback covers it). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MasterApiControllerTest {

    @Autowired
    private MockMvcTester mvc;

    private void loadSample() {
        assertThat(mvc.post().uri("/api/master/sample")).hasStatusOk();
    }

    @Test
    void getBeforeAnythingIsSavedIsA404Problem() {
        assertThat(mvc.get().uri("/api/master"))
                .hasStatus(404)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.detail").asString().contains("no master resume");
    }

    @Test
    void sampleThenGetReturnsTheDocument() {
        loadSample();

        assertThat(mvc.get().uri("/api/master")).hasStatusOk()
                .bodyJson().extractingPath("$.basics.name").isEqualTo("Jordan Tan");
        assertThat(mvc.post().uri("/api/master/sample")).hasStatus(409); // never over real data
    }

    @Test
    void putValidDocumentCreatesANewVersionAndReportsNewIds() {
        loadSample();
        ObjectNode doc = set(MasterFixtures.sample(), "/awards/-", "{\"title\": \"Dean's List\"}");

        assertThat(mvc.put().uri("/api/master?note=added award").contentType(MediaType.APPLICATION_JSON).content(text(doc)))
                .hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.versionNo").isEqualTo(2);
                    assertThat(json).extractingPath("$.assignedIds[0]").isEqualTo("award-2");
                });
        assertThat(mvc.get().uri("/api/master/versions")).hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$[0].versionNo").isEqualTo(2);
                    assertThat(json).extractingPath("$[0].changeNote").isEqualTo("added award");
                });
    }

    @Test
    void putInvalidDocumentIsA422WithEveryErrorAndItsPath() {
        ObjectNode doc = set(MasterFixtures.sample(), "/basics/nric", "\"S1234567D\"");
        set(doc, "/work/0/startDate", "\"May 2026\"");

        assertThat(mvc.put().uri("/api/master").contentType(MediaType.APPLICATION_JSON).content(text(doc)))
                .hasStatus(422)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Master resume rejected");
                    assertThat(json).extractingPath("$.errors[0].kind").isEqualTo("PRIVACY");
                    assertThat(json).extractingPath("$.errors[0].path").isEqualTo("/basics/nric");
                    assertThat(json).extractingPath("$.errors[1].path").isEqualTo("/work/0/startDate");
                });
        assertThat(mvc.get().uri("/api/master")).hasStatus(404); // nothing saved
    }

    @Test
    void validateEndpointNeverSaves() {
        assertThat(mvc.post().uri("/api/master/validate").contentType(MediaType.APPLICATION_JSON)
                .content(MasterFixtures.sampleText()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.versionNo").isEqualTo(1);
        assertThat(mvc.get().uri("/api/master")).hasStatus(404);
    }

    @Test
    void versionsDiffAndRestore() {
        loadSample();
        mvc.put().uri("/api/master").contentType(MediaType.APPLICATION_JSON)
                .content(text(set(MasterFixtures.sample(), "/basics/label", "\"Backend Engineer\""))).exchange();

        assertThat(mvc.get().uri("/api/master/versions/1")).hasStatusOk()
                .bodyJson().extractingPath("$.basics.label").isEqualTo("Software Engineer (Backend, Java)");
        assertThat(mvc.get().uri("/api/master/versions/42")).hasStatus(404);
        assertThat(mvc.get().uri("/api/master/diff?from=1&to=2")).hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.changes[0].type").isEqualTo("CHANGED");
                    assertThat(json).extractingPath("$.changes[0].path").isEqualTo("/basics/label");
                    assertThat(json).extractingPath("$.changes[0].after").isEqualTo("Backend Engineer");
                });
        assertThat(mvc.post().uri("/api/master/versions/1/restore")).hasStatusOk()
                .bodyJson().extractingPath("$.versionNo").isEqualTo(3);
    }
}

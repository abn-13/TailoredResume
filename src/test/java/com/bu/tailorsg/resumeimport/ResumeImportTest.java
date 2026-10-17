package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.documents.TestDocuments;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.TokenUsage;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Import end to end with the extractor model mocked: a DOCX goes in, a reviewable update comes out, and
 * applying it creates a valid master resume. The mock returns fixtures/import/llm-output.json.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResumeImportTest {

    @MockitoBean private ClaudeClient claude;
    @Autowired private ResumeImportService importer;
    @Autowired private PendingUpdateService updates;
    @Autowired private MasterResumeService master;
    @Autowired private JsonMapper json;
    @Autowired private MockMvcTester mvc;

    private byte[] resumeDocx;

    @BeforeEach
    void setUp() {
        resumeDocx = TestDocuments.docx(Arrays.asList(ImportPipelineUnitTest.fixture("resume.txt").split("\n")));
        JsonNode llmOutput = json.readTree(ImportPipelineUnitTest.fixture("llm-output.json"));
        when(claude.isConfigured()).thenReturn(true);
        when(claude.callJson(eq("master-import"), anyList())).thenReturn(
                new LlmResult(llmOutput, "master-import", 1, "claude-haiku-5-5", 1, TokenUsage.ZERO, BigDecimal.ZERO));
    }

    @Test
    void importCreatesAReviewableUpdateThatAppliesCleanly() {
        PendingUpdate update = importer.importResume("alex-lim.docx", resumeDocx);

        assertThat(update.getSourceType()).isEqualTo(PendingUpdate.Source.IMPORT);
        assertThat(update.getSummary()).isEqualTo(
                "Import from alex-lim.docx: 2 jobs, 0 projects, 1 education, 3 highlights, 6 skills (4 with evidence)");
        assertThat(updates.notesOf(update))
                .anySatisfy(n -> assertThat(n).contains("Removed line 4", "NRIC/FIN"))
                .anySatisfy(n -> assertThat(n).contains("From the model: Education dates give only years"))
                .anySatisfy(n -> assertThat(n).contains("assumed Singapore (+65)"))
                .anySatisfy(n -> assertThat(n).contains("Skill \"Kubernetes\" was not found"));

        PendingUpdateService.Preview preview = updates.preview(update);
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.canApply()).isTrue();
        assertThat(preview.baseVersionNo()).isNull();

        assertThat(updates.apply(update.getId()).versionNo()).isEqualTo(1);
        JsonNode stored = master.current().orElseThrow().json();
        assertThat(stored.at("/work/0/id").stringValue()).isEqualTo("work-1");
        assertThat(stored.at("/work/0/highlights/0/id").stringValue()).isEqualTo("work-1.h1");
        assertThat(stored.at("/skills/1/evidenceIds/0").stringValue()).isEqualTo("work-2.h1"); // Python
        assertThat(stored.toString()).doesNotContain("NEW-");
    }

    @Test
    void personalDataNeverReachesTheModelOrTheDatabase() {
        PendingUpdate update = importer.importResume("alex-lim.docx", resumeDocx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(claude).callJson(eq("master-import"), sent.capture());
        assertThat(sent.getValue().getFirst()).doesNotContain("S1234567D", "1 January 1995").contains("Alex Lim");
        assertThat(update.getRawInput()).doesNotContain("S1234567D", "1 January 1995");
    }

    @Test
    void importOverAnExistingMasterIsAReplaceWithAWarning() {
        master.loadSample();

        PendingUpdate update = importer.importResume("alex-lim.docx", resumeDocx);

        assertThat(updates.patchOf(update).at("/0/op").stringValue()).isEqualTo("replace");
        assertThat(updates.notesOf(update).getFirst()).contains("replaces your whole master resume");
        assertThat(updates.preview(update).baseVersionNo()).isEqualTo(1);
    }

    @Test
    void apiAndPageReportUnreadableFilesWithoutCallingTheModel() {
        MockMultipartFile scanned = new MockMultipartFile("file", "scan.pdf", "application/pdf", TestDocuments.pdf(List.of()));

        assertThat(mvc.post().multipart().uri("/api/master/import").file(scanned)).hasStatus(422)
                .bodyJson().extractingPath("$.detail").asString().contains("scanned or image-only");
        assertThat(mvc.post().multipart().uri("/master/import").file(scanned))
                .hasRedirectedUrl("/master/import")
                .flash().containsKey("flashError");
        verify(claude, never()).callJson(eq("master-import"), anyList());
    }

    @Test
    void apiImportReturnsTheUpdateAndPageRedirectsToReview() {
        MockMultipartFile file = new MockMultipartFile("file", "alex-lim.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", resumeDocx);

        assertThat(mvc.post().multipart().uri("/api/master/import").file(file)).hasStatus(201)
                .bodyJson().satisfies(body -> {
                    assertThat(body).extractingPath("$.sourceType").isEqualTo("IMPORT");
                    assertThat(body).extractingPath("$.preview.canApply").isEqualTo(true);
                });
        assertThat(mvc.post().multipart().uri("/master/import").file(file)).hasStatus3xxRedirection();

        long id = updates.list(null).getFirst().getId();
        assertThat(mvc.get().uri("/updates/" + id)).hasStatusOk().bodyText()
                .contains("Check these", "Resulting master resume", "Alex Lim", "Example Logistics Pte Ltd",
                        "Text read from the file");
    }

    @Test
    void apiSaysWhenNoKeyIsConfigured() {
        when(claude.isConfigured()).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "a.docx", "application/octet-stream", resumeDocx);

        assertThat(mvc.post().multipart().uri("/api/master/import").file(file)).hasStatus(503);
    }
}

package com.bu.tailorsg.coverletter;

import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.export.ExportFile;
import com.bu.tailorsg.export.ExportService;
import com.bu.tailorsg.jobs.JobService;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.llm.LlmTransport.UserBlock;
import com.bu.tailorsg.llm.TokenUsage;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.support.TestResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 7: write (mocked model), check, fix dashes, edit, export the cover letter. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CoverLetterFlowTest {

    @MockitoBean private ClaudeClient claude;
    @Autowired private JobService jobs;
    @Autowired private MasterResumeService master;
    @Autowired private ApplicationService applications;
    @Autowired private CoverLetterService letters;
    @Autowired private ExportService exports;
    @Autowired private MockMvcTester mvc;
    @Autowired private JsonMapper json;
    @Autowired private TailorSgProperties props;

    private long appId;

    @BeforeEach
    void setUp() {
        FileSystemUtils.deleteRecursively(Path.of(props.storage().exportsDir()).toFile());
        when(claude.isConfigured()).thenReturn(true);
        when(claude.call(eq("jd-extract"), anyList())).thenReturn(result("jd-extract", "fixtures/jobs/llm-jobspec.json"));
        when(claude.call(eq("tailor"), anyList())).thenReturn(result("tailor", "fixtures/tailoring/llm-tailored.json"));
        when(claude.call(eq("cover-letter"), anyList())).thenReturn(result("cover-letter", "fixtures/coverletter/llm-cover-letter.json"));
        master.loadSample();
        long jobId = jobs.fromText("Backend Engineer at Example Payments. Java, Spring Boot, PostgreSQL, REST APIs and Kafka "
                + "required; Kubernetes and AWS nice to have. We process payments for 2,000 merchants.").getId();
        appId = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(appId);
    }

    @Test
    void notesAreSavedTheLetterIsWrittenAndOnlyTheDashFails() {
        assertThat(mvc.post().uri("/applications/" + appId + "/cover-letter").param("availability", "available from January 2027")
                .param("whyCompany", "").param("tone", "formal")).hasRedirectedUrl("/applications/" + appId + "?tab=cover");
        assertThat(applications.optionsOf(applications.get(appId)).availability()).isEqualTo("available from January 2027");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(claude).call(eq("cover-letter"), blocks.capture());
        assertThat(((TextBlock) blocks.getValue().get(2)).text()).startsWith("<USER_NOTES>")
                .contains("\"availability\":\"available from January 2027\"", "\"mentionWorkPass\":\"false\"", "\"tone\":\"formal\"");

        CoverLetterDraft draft = letters.draftOf(applications.get(appId)).orElseThrow();
        assertThat(draft.signOff()).isEqualTo("Yours sincerely,");             // the name comes from the master
        assertThat(draft.wordCount()).isBetween(250, 380);

        CheckReport report = letters.reportOf(applications.get(appId)).orElseThrow();
        assertThat(report.errors()).as(report.errors().toString()).extracting(Finding::checkId).containsExactly("CL-01");
        assertThat(report.category(Category.TRUTH).findings()).as(report.category(Category.TRUTH).findings().toString()).isEmpty();

        assertThat(mvc.get().uri("/applications/" + appId + "?tab=cover")).hasStatusOk().bodyText()
                .contains("Fix dashes", "one cache in front of one query, but", "Application for Backend Engineer", "jordan.tan@example.com | +6590001234",
                        "Hiring Manager", "Example Payments Pte Ltd", "Yours sincerely,");

        mvc.post().uri("/applications/" + appId + "/cover-letter/fix-dashes").exchange();
        assertThat(letters.reportOf(applications.get(appId)).orElseThrow().hasErrors()).isFalse();
        assertThat(letters.draftOf(applications.get(appId)).orElseThrow().bodyText()).doesNotContain("—");
    }

    @Test
    void anEditedParagraphIsRecheckedAgainstItsSources() throws Exception {
        letters.write(appId);
        String fragment = mvc.post().uri("/applications/" + appId + "/cover-letter/paragraphs/edit").param("index", "1")
                .param("text", "At Example Fintech I cut latency by 60% with Kafka.").exchange().getResponse().getContentAsString();
        assertThat(fragment).contains("id=\"letter-workspace\"", "Saved and re-checked").doesNotContain("<html");
        assertThat(letters.reportOf(applications.get(appId)).orElseThrow().errors()).extracting(Finding::checkId)
                .contains("TRUTH-02", "TRUTH-03");
    }

    @Test
    void exportIsBlockedByTheDashThenSavedAfterTheFix() throws Exception {
        // Without this note the letter's "available from January 2027" has no source, and TRUTH-02 blocks it.
        applications.updateOptions(appId, new ApplicationOptions(1, true, true, true, null, null, "available from January 2027", false));
        letters.write(appId);
        assertThat(mvc.post().uri("/applications/" + appId + "/cover-letter/export").param("type", "PDF"))
                .flash().hasEntrySatisfying("flashError", e -> assertThat((String) e).contains("Export blocked"));

        letters.fixDashes(appId);
        ExportFile pdf = letters.export(appId, com.bu.tailorsg.export.ExportedFile.Type.PDF, null);
        ExportFile docx = letters.export(appId, com.bu.tailorsg.export.ExportedFile.Type.DOCX, null);
        assertThat(pdf.getFileName()).isEqualTo("Jordan_Tan_Cover_Letter_Example_Payments_Pte_Ltd.pdf");
        assertThat(pdf.getPageCount()).isEqualTo(1);
        assertThat(docx.getKind()).isEqualTo(ExportFile.Kind.COVER_LETTER);
        assertThat(Files.readAllBytes(exports.pathOf(pdf))).startsWith("%PDF".getBytes());
        assertThat(mvc.get().uri("/applications/" + appId + "?tab=export")).hasStatusOk().bodyText()
                .contains("Jordan_Tan_Cover_Letter_Example_Payments_Pte_Ltd.pdf", "Jordan_Tan_Cover_Letter_Example_Payments_Pte_Ltd.docx");
    }

    private LlmResult result(String prompt, String path) {
        return new LlmResult(json.readTree(TestResources.text(path)), prompt, 1, "test-model", 1, TokenUsage.ZERO, BigDecimal.ZERO);
    }
}

package com.bu.tailorsg.export;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.jobs.JobService;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.TokenUsage;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.support.TestResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Phase 6: export with the round-trip checks, the P5 blocker and override, file storage and download. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExportFlowTest {

    @MockitoBean private ClaudeClient claude;
    @Autowired private JobService jobs;
    @Autowired private MasterResumeService master;
    @Autowired private ApplicationService applications;
    @Autowired private ExportService exports;
    @Autowired private MockMvcTester mvc;
    @Autowired private JsonMapper json;
    @Autowired private com.bu.tailorsg.config.TailorSgProperties props;

    private long appId;

    @BeforeEach
    void setUp() throws java.io.IOException {
        // The database rolls back after each test; files on disk do not, so start each test with an empty folder.
        org.springframework.util.FileSystemUtils.deleteRecursively(java.nio.file.Path.of(props.storage().exportsDir()));
        when(claude.isConfigured()).thenReturn(true);
        when(claude.call(eq("jd-extract"), anyList())).thenReturn(result("jd-extract", "fixtures/jobs/llm-jobspec.json"));
        when(claude.call(eq("tailor"), anyList())).thenReturn(result("tailor", "fixtures/tailoring/llm-tailored.json"));
        master.loadSample();
        long jobId = jobs.fromText("Backend Engineer at Example Payments. Java, Spring Boot, PostgreSQL, REST APIs and Kafka "
                + "required; Kubernetes and AWS nice to have. We process payments for 2,000 merchants.").getId();
        appId = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(appId);
    }

    @Test
    void aCleanResumeExportsToOnePagePdfThatPassesTheRoundTrip() throws Exception {
        assertThat(mvc.post().uri("/applications/" + appId + "/export").param("type", "PDF"))
                .hasRedirectedUrl("/applications/" + appId + "?tab=export")
                .flash().hasEntrySatisfying("flash", f -> assertThat((String) f).contains("Saved Jordan_Tan_Resume_Example_Payments_Pte_Ltd.pdf"));

        ExportFile f = exports.filesOf(appId).get(0);
        assertThat(f.getPageCount()).isEqualTo(1);
        assertThat(f.getOverrideReason()).isNull();
        assertThat(f.getRelativePath()).startsWith("Example_Payments_Pte_Ltd_Backend_Engineer_").endsWith(".pdf");
        assertThat(Files.readAllBytes(exports.pathOf(f))).startsWith("%PDF".getBytes());
        assertThat(applications.get(appId).getStatus()).isEqualTo(Application.Status.EXPORTED);

        var check = exports.lastCheckOf(applications.get(appId)).orElseThrow();
        assertThat(check.report().category(Category.ATS).errors()).as(check.report().category(Category.ATS).findings().toString()).isZero();
        assertThat(check.fields()).allSatisfy(row -> assertThat(row.ok()).as(row.toString()).isTrue());

        var download = mvc.get().uri("/applications/" + appId + "/exports/" + f.getId()).exchange();
        assertThat(download).hasStatusOk().hasContentType(MediaType.APPLICATION_PDF);
        assertThat(download.getResponse().getHeader("Content-Disposition")).contains("Jordan_Tan_Resume_Example_Payments_Pte_Ltd.pdf");

        assertThat(mvc.get().uri("/applications/" + appId + "?tab=export")).hasStatusOk().bodyText()
                .contains("What an ATS would likely extract", "Software Engineering Intern", "Exported files", f.getSha256().substring(0, 12));
    }

    @Test
    void docxExportsAndASecondExportGetsANewName() {
        ExportFile first = exports.exportResume(appId, ExportedFile.Type.DOCX, null);
        ExportFile second = exports.exportResume(appId, ExportedFile.Type.DOCX, null);
        assertThat(first.getFileName()).isEqualTo("Jordan_Tan_Resume_Example_Payments_Pte_Ltd.docx");
        assertThat(second.getFileName()).isEqualTo("Jordan_Tan_Resume_Example_Payments_Pte_Ltd_2.docx");
        assertThat(mvc.get().uri("/applications/" + appId + "/exports/" + first.getId())).hasStatusOk()
                .hasContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void errorsBlockExportUntilAReasonIsTyped() {
        applications.editBullet(appId, "work-1", 0, "Built Kafka pipelines for Grab handling 2,000 refunds a day with Spring Boot");

        assertThat(mvc.post().uri("/applications/" + appId + "/export").param("type", "PDF").param("overrideReason", "too short"))
                .flash().hasEntrySatisfying("flashError", e -> assertThat((String) e).contains("Export blocked"));
        assertThat(exports.filesOf(appId)).isEmpty();
        assertThat(exports.lastCheckOf(applications.get(appId)).orElseThrow().blocked()).isTrue();

        assertThat(mvc.post().uri("/api/applications/" + appId + "/export?type=pdf").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(409).bodyJson().extractingPath("$.check.blocked").isEqualTo(true);

        String reason = "Recruiter asked for this exact wording; I will explain the Kafka work in the interview";
        assertThat(mvc.post().uri("/api/applications/" + appId + "/export?type=pdf").contentType(MediaType.APPLICATION_JSON)
                .content("{\"overrideReason\": \"" + reason + "\"}")).hasStatus(201).bodyJson()
                .extractingPath("$.file.overrideReason").isEqualTo(reason);
        assertThat(applications.get(appId).getOverrideReason()).isEqualTo(reason);
        assertThat(mvc.get().uri("/api/applications/" + appId + "/exports")).hasStatusOk().bodyJson().extractingPath("$[0].type").isEqualTo("PDF");
    }

    @Test
    void checkOnlyDoesNotSaveAndFilesOfOtherApplicationsAreNotServed() {
        assertThat(mvc.post().uri("/applications/" + appId + "/export").param("type", "DOCX").param("checkOnly", "true"))
                .flash().containsKey("flash");
        assertThat(exports.filesOf(appId)).isEmpty();
        assertThat(exports.lastCheckOf(applications.get(appId)).orElseThrow().type()).isEqualTo(ExportedFile.Type.DOCX);

        ExportFile f = exports.exportResume(appId, ExportedFile.Type.PDF, null);
        assertThatThrownBy(() -> exports.file(appId + 1, f.getId())).isInstanceOf(IllegalArgumentException.class);
    }

    private LlmResult result(String prompt, String path) {
        return new LlmResult(json.readTree(TestResources.text(path)), prompt, 1, "test-model", 1, TokenUsage.ZERO, BigDecimal.ZERO);
    }
}

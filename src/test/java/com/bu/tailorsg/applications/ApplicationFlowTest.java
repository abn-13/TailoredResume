package com.bu.tailorsg.applications;

import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.Finding;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 end to end with the models mocked: create an application from the job page form, tailor, render
 * with master structural data, check (TRUTH catches fabrication), and edit bullets inline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApplicationFlowTest {

    static final String POSTING = "Backend Engineer at Example Payments. You will build payment APIs in Java and "
            + "Spring Boot on PostgreSQL. Requirements: REST APIs, Kafka. Nice to have: Kubernetes, AWS. We process payments "
            + "for 2,000 merchants in Southeast Asia.";

    @MockitoBean private ClaudeClient claude;
    @Autowired private JobService jobs;
    @Autowired private MasterResumeService master;
    @Autowired private ApplicationService applications;
    @Autowired private MockMvcTester mvc;
    @Autowired private JsonMapper json;

    private long jobId;

    @BeforeEach
    void setUp() {
        when(claude.isConfigured()).thenReturn(true);
        when(claude.call(eq("jd-extract"), anyList())).thenReturn(result("jd-extract", TestResources.text("fixtures/jobs/llm-jobspec.json")));
        when(claude.call(eq("tailor"), anyList())).thenReturn(result("tailor", fixture("llm-tailored.json")));
        master.loadSample();
        jobId = jobs.fromText(POSTING).getId();
    }

    @Test
    void jobFormCreatesTailorsAndChecks() throws IOException {
        var response = mvc.post().uri("/applications").param("jobId", String.valueOf(jobId)).param("pageTarget", "1")
                .param("includeSummary", "true").param("showWorkAuth", "true").exchange();
        assertThat(response).hasStatus3xxRedirection();
        assertThat(response).flash().hasEntrySatisfying("flash", f -> assertThat((String) f)
                .contains("Tailored and checked", "Removed the full stop at the end of 1 bullet"));

        Application app = applications.list().stream().findFirst().map(r -> applications.get(r.id())).orElseThrow();
        CheckReport report = applications.reportOf(app).orElseThrow();
        assertThat(report.category(Category.TRUTH).errors()).as(report.category(Category.TRUTH).findings().toString()).isZero();
        assertThat(report.keywords().all()).anySatisfy(t -> assertThat(t.term()).isEqualTo("Kafka"));
        assertThat(applications.promptVersionsOf(app)).containsEntry("tailor", 1);
        assertThat(app.getMasterVersionNo()).isEqualTo(1);

        // Employer, title and dates come from the master (P2); experience is reverse-chronological even
        // though the model listed work-2 first.
        String page = mvc.get().uri("/applications/" + app.getId()).exchange().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Software Engineering Intern, Example Fintech Pte Ltd, Singapore", "May 2026 - Present",
                "Scorecard", "id=\"b-work-1-0\"", "work-1.h2", "Kafka", "Save and re-check", "Left out of this resume (2)");
        assertThat(page.indexOf("Example Fintech Pte Ltd")).isLessThan(page.indexOf("Acme Precision Engineering"));
        assertThat(page).doesNotContain("Automated the nightly settlement report with a GitHub Actions workflow, saving the operations team about 3 hours a week.");
    }

    @Test
    void theModelNeverSeesContactDetailsAndMasterIsCacheable() {
        long id = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(id);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(claude).call(eq("tailor"), blocks.capture());
        TextBlock first = (TextBlock) blocks.getValue().get(0);
        assertThat(first.cacheable()).isTrue();
        assertThat(first.text()).startsWith("<MASTER>").contains("work-1.h1", "\"spelling\"")
                .doesNotContain("jordan.tan@example.com", "+6590001234", "linkedin.com");
        assertThat(((TextBlock) blocks.getValue().get(1)).text()).startsWith("<JOB>").contains("Kafka");
        assertThat(((TextBlock) blocks.getValue().get(2)).text()).contains("\"pageTarget\":1");
    }

    @Test
    void truthChecksCatchAFabricatedEdit() throws IOException {
        long id = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(id);

        // The edited bullet (anchor b-work-1-0) now claims Kafka, a new number and an employer's client.
        String fragment = mvc.post().uri("/applications/" + id + "/bullets/edit").param("itemId", "work-1").param("index", "0")
                .param("text", "Built Kafka pipelines for Grab handling 2,000 refund requests a day with Spring Boot and PostgreSQL")
                .exchange().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(fragment).contains("id=\"workspace\"", "Saved and re-checked", "TRUTH-02", "TRUTH-03", "TRUTH-04")
                .doesNotContain("<html");

        CheckReport report = applications.reportOf(applications.get(id)).orElseThrow();
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.errors()).extracting(Finding::location).contains("b-work-1-0");
        assertThat(report.errors()).extracting(Finding::message)
                .anySatisfy(m -> assertThat(m).contains("2000"))
                .anySatisfy(m -> assertThat(m).contains("Apache Kafka"))
                .anySatisfy(m -> assertThat(m).contains("Grab"));
    }

    @Test
    void keywordPanelAddsOnlyHonestlyCitedBullets() {
        long id = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(id);

        assertThat(mvc.post().uri("/applications/" + id + "/bullets/add").param("itemId", "work-2").param("sourceId", "work-2.h2")
                .param("text", "Analysed vibration test data from 12 prototype units to identify a bracket resonance"))
                .hasStatus3xxRedirection().flash().containsKey("flash");
        var work2 = applications.tailoredOf(applications.get(id)).orElseThrow().experience().stream()
                .filter(e -> e.workId().equals("work-2")).findFirst().orElseThrow();
        assertThat(work2.bullets()).hasSize(2);
        assertThat(work2.bullets().get(1).sourceIds()).containsExactly("work-2.h2");

        assertThat(mvc.post().uri("/applications/" + id + "/bullets/add").param("itemId", "work-1").param("sourceId", "work-2.h2")
                .param("text", "Analysed vibration data")).flash().containsKey("flashError");
    }

    @Test
    void removingABulletAndEditingTheSummary() {
        long id = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(id);

        assertThat(mvc.post().uri("/applications/" + id + "/bullets/remove").param("itemId", "proj-1").param("index", "0"))
                .hasStatusOk().bodyText().contains("Removed and re-checked");
        assertThat(applications.tailoredOf(applications.get(id)).orElseThrow().projects()).extracting(p -> p.projectId())
                .containsExactly("proj-2", "proj-1");
        assertThat(applications.documentOf(applications.get(id)).orElseThrow().plainText()).doesNotContain("rule-based check engine");

        assertThat(mvc.post().uri("/applications/" + id + "/bullets/edit").param("itemId", "summary").param("text", "  "))
                .hasStatusOk().bodyText().contains("The text is empty");
        mvc.post().uri("/applications/" + id + "/bullets/edit").param("itemId", "summary")
                .param("text", "Backend-focused software engineering intern building Java and Spring Boot services").exchange();
        assertThat(applications.tailoredOf(applications.get(id)).orElseThrow().summary().sourceIds()).containsExactly("sum-1");
    }

    @Test
    void apiReplacesTheResumeOnlyWhenItFitsTheSchema() throws IOException {
        long id = applications.create(jobId, ApplicationOptions.defaults()).getId();
        applications.tailor(id);

        ObjectNode bad = (ObjectNode) json.readTree(fixture("llm-tailored.json"));
        ((ObjectNode) bad.withArray("experience").get(0)).put("workId", "employer-1");
        assertThat(mvc.put().uri("/api/applications/" + id + "/resume").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(bad))).hasStatus(422).bodyJson().extractingPath("$.errors[0].path")
                .isEqualTo("/experience/0/workId");

        assertThat(mvc.put().uri("/api/applications/" + id + "/resume").contentType(MediaType.APPLICATION_JSON)
                .content(fixture("llm-tailored.json"))).hasStatusOk().bodyJson().extractingPath("$.categories[0].category")
                .isEqualTo("TRUTH");
        assertThat(mvc.get().uri("/api/applications")).hasStatusOk().bodyJson().extractingPath("$[0].tailored").isEqualTo(true);
        assertThat(mvc.post().uri("/api/applications").contentType(MediaType.APPLICATION_JSON).content("{\"jobId\": 99999}"))
                .hasStatus(404);
    }

    @Test
    void withoutAKeyTheApplicationIsSavedAndExplainsWhy() {
        when(claude.isConfigured()).thenReturn(false);
        assertThat(mvc.post().uri("/applications").param("jobId", String.valueOf(jobId))).hasStatus3xxRedirection()
                .flash().hasEntrySatisfying("flashError", e -> assertThat((String) e).contains("ANTHROPIC_API_KEY"));
        long id = applications.list().get(0).id();
        assertThat(mvc.get().uri("/applications/" + id)).hasStatusOk().bodyText().contains("Not tailored yet", "Tailor now");
        assertThat(mvc.post().uri("/api/applications/" + id + "/tailor")).hasStatus(503);
    }

    @Test
    void trackerOnDashboardAndStatusChanges() {
        long id = applications.create(jobId, ApplicationOptions.defaults()).getId();
        assertThat(mvc.get().uri("/")).hasStatusOk().bodyText().contains("Backend Engineer", "Example Payments Pte Ltd", "not tailored");
        assertThat(mvc.post().uri("/applications/" + id + "/status").param("status", "APPLIED").param("returnTo", "dashboard"))
                .hasRedirectedUrl("/");
        assertThat(applications.get(id).getStatus()).isEqualTo(Application.Status.APPLIED);
        assertThat(mvc.get().uri("/applications")).hasStatusOk().bodyText().contains("APPLIED");
    }

    private LlmResult result(String prompt, String body) {
        return new LlmResult(json.readTree(body), prompt, 1, "test-model", 1, TokenUsage.ZERO, BigDecimal.ZERO);
    }

    static String fixture(String name) {
        return TestResources.text("fixtures/tailoring/" + name);
    }
}

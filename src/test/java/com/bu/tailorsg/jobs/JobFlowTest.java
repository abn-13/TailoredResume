package com.bu.tailorsg.jobs;

import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.TokenUsage;
import com.bu.tailorsg.master.MasterResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Paste and screenshot ingestion, the JobSpec editor and the job API, with the extractor model mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobFlowTest {

    private static final String POSTING = "Backend Engineer at Example Payments. You will build payment APIs in Java and "
            + "Spring Boot on PostgreSQL. Requirements: REST APIs, Kafka. Nice to have: Kubernetes, AWS. We process payments "
            + "for 2,000 merchants in Southeast Asia.";

    @MockitoBean private ClaudeClient claude;
    @Autowired private JobService jobs;
    @Autowired private MasterResumeService master;
    @Autowired private MockMvcTester mvc;
    @Autowired private JsonMapper json;

    @BeforeEach
    void mockExtractor() {
        when(claude.isConfigured()).thenReturn(true);
        when(claude.call(eq("jd-extract"), anyList())).thenReturn(new LlmResult(
                json.readTree(JobSourcesTest.fixture("llm-jobspec.json")), "jd-extract", 1, "claude-haiku-5-5", 1,
                TokenUsage.ZERO, BigDecimal.ZERO));
    }

    @Test
    void pastedTextBecomesACleanJobSpec() {
        JobPosting posting = jobs.fromText(POSTING);
        JobSpec spec = jobs.specOf(posting);

        assertThat(posting.getTitle()).isEqualTo("Backend Engineer");
        assertThat(spec.salaryRange()).isNull();                                  // 0/0 means not stated
        assertThat(spec.requiredSkills()).extracting(JobSpec.JdSkill::term)       // duplicate Java merged
                .containsExactly("Java", "Spring Boot", "PostgreSQL", "REST APIs", "Kafka");
        assertThat(spec.responsibilities()).containsExactly("Build payment APIs"); // blank dropped
        assertThat(spec.applyInstructions()).isNull();
    }

    @Test
    void screenshotsAreDownscaledAndSentTogether() throws IOException {
        BufferedImage wide = new BufferedImage(3200, 900, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(wide, "png", out);
        var block = ScreenshotInput.prepare(out.toByteArray(), "a.png");
        BufferedImage scaled = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(block.base64())));
        assertThat(Math.max(scaled.getWidth(), scaled.getHeight())).isEqualTo(ScreenshotInput.MAX_EDGE);

        MockMultipartFile file = new MockMultipartFile("files", "a.png", "image/png", out.toByteArray());
        assertThat(mvc.post().multipart().uri("/api/jobs/from-image").file(file)).hasStatus(201)
                .bodyJson().extractingPath("$.fetchNote").isEqualTo("1 screenshot");
        assertThat(mvc.post().multipart().uri("/api/jobs/from-image")
                .file(new MockMultipartFile("files", "x.png", "image/png", "not an image".getBytes()))).hasStatus(400);
    }

    @Test
    void editorShowsChipsAndMovingASkillPersists() {
        master.loadSample();
        long id = jobs.fromText(POSTING).getId();

        assertThat(mvc.get().uri("/jobs/" + id)).hasStatusOk().bodyText()
                .contains("Step 2 of 3", "Required", "Kafka", "to preferred →", "How much of this your master can cover",
                        "Create application and tailor", "Processes payments for 2,000 merchants");
        assertThat(mvc.post().uri("/jobs/" + id + "/skills/move").param("term", "Kafka").param("to", "preferred"))
                .hasStatusOk().bodyText().contains("id=\"job-skills\"");
        assertThat(jobs.specOf(jobs.get(id)).preferredSkills()).extracting(JobSpec.JdSkill::term).contains("Kafka");

        mvc.post().uri("/jobs/" + id + "/skills/add").param("term", "Redis").param("list", "required").exchange();
        mvc.post().uri("/jobs/" + id + "/skills/remove").param("term", "AWS").exchange();
        JobSpec spec = jobs.specOf(jobs.get(id));
        assertThat(spec.requiredSkills()).extracting(JobSpec.JdSkill::term).contains("Redis");
        assertThat(spec.preferredSkills()).extracting(JobSpec.JdSkill::term).doesNotContain("AWS");
    }

    @Test
    void detailsFormAndApiUpdatesKeepSkills() {
        long id = jobs.fromText(POSTING).getId();

        assertThat(mvc.post().uri("/jobs/" + id).param("title", "Software Engineer (Backend)").param("company", "Example Payments")
                .param("salaryMin", "5,000").param("salaryMax", "6.5k").param("salaryCurrency", "SGD")
                .param("companyFacts", "Fact one\nFact two")).hasStatus3xxRedirection();
        JobSpec spec = jobs.specOf(jobs.get(id));
        assertThat(spec.title()).isEqualTo("Software Engineer (Backend)");
        assertThat(spec.salaryRange().max()).isEqualTo(6500.0);
        assertThat(spec.companyFacts()).containsExactly("Fact one", "Fact two");
        assertThat(spec.requiredSkills()).isNotEmpty();

        assertThat(mvc.get().uri("/api/jobs/" + id)).hasStatusOk().bodyJson().extractingPath("$.spec.title")
                .isEqualTo("Software Engineer (Backend)");
        assertThat(mvc.put().uri("/api/jobs/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edited\",\"requiredSkills\":[{\"term\":\"Go\",\"aliases\":[]}]}")).hasStatusOk();
        assertThat(jobs.get(id).getTitle()).isEqualTo("Edited");
    }

    @Test
    void blockedLinksExplainWhatToDoInstead() {
        assertThat(mvc.post().uri("/jobs/from-url").param("url", "https://www.linkedin.com/jobs/view/1"))
                .hasRedirectedUrl("/applications/new?tab=link")
                .flash().containsEntry("flashError",
                        "This site blocks automated fetching. Paste the JD text or upload screenshots. (Reason: LinkedIn requires a login.)");
        assertThat(mvc.post().uri("/api/jobs/from-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://www.linkedin.com/jobs/view/1\"}")).hasStatus(422)
                .bodyJson().extractingPath("$.reason").isEqualTo("LinkedIn requires a login");
        assertThat(mvc.post().uri("/api/jobs/from-text").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"too short\"}"))
                .hasStatus(400);
    }

    @Test
    void newApplicationPageHasThreeWaysIn() {
        assertThat(mvc.get().uri("/applications/new?tab=screenshots")).hasStatusOk().bodyText()
                .contains("Link", "Screenshot(s)", "Paste", "1 to 5 screenshots");
    }
}

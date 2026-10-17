package com.bu.tailorsg.llm.demo;

import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.coverletter.CoverLetterService;
import com.bu.tailorsg.export.ExportFile;
import com.bu.tailorsg.export.ExportService;
import com.bu.tailorsg.export.ExportedFile;
import com.bu.tailorsg.fit.FitAdvice;
import com.bu.tailorsg.fit.FitService;
import com.bu.tailorsg.jobs.JobService;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmCallRepository;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.updates.PendingUpdateService;
import com.bu.tailorsg.updates.freetext.FreeTextUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app on the demo transport: every model-backed step produces schema-valid output that the real
 * checks accept. Also proves the calls are logged (Phase 9) at zero cost.
 */
@SpringBootTest(properties = "tailorsg.llm.demo-mode=true")
@AutoConfigureMockMvc
@Transactional
class DemoModeTest {

    static final String POSTING = """
            Backend Engineer at Example Payments.
            You will build payment APIs in Java and Spring Boot on PostgreSQL, with REST APIs and Kafka.
            We process payments for 2,000 merchants in Southeast Asia.
            Nice to have: Kubernetes, AWS. Please include a cover letter.""";

    @Autowired private ClaudeClient claude;
    @Autowired private MasterResumeService master;
    @Autowired private JobService jobs;
    @Autowired private ApplicationService applications;
    @Autowired private FitService fit;
    @Autowired private CoverLetterService letters;
    @Autowired private ExportService exports;
    @Autowired private FreeTextUpdateService freeText;
    @Autowired private PendingUpdateService updates;
    @Autowired private LlmCallRepository calls;
    @Autowired private TailorSgProperties props;
    @Autowired private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        FileSystemUtils.deleteRecursively(Path.of(props.storage().exportsDir()).toFile());
        master.loadSample();
    }

    @Test
    void everyStepWorksWithoutAnApiKey() {
        assertThat(claude.isConfigured()).isTrue();

        JobSpec spec = jobs.specOf(jobs.fromText(POSTING));
        assertThat(spec.title()).isEqualTo("Backend Engineer");
        assertThat(spec.company()).isEqualTo("Example Payments");
        assertThat(spec.requiredSkills()).extracting(JobSpec.JdSkill::term).contains("Java", "Spring Boot", "PostgreSQL", "Apache Kafka");
        assertThat(spec.preferredSkills()).extracting(JobSpec.JdSkill::term).contains("Kubernetes", "AWS");
        assertThat(spec.companyFacts()).anySatisfy(f -> assertThat(f).contains("2,000 merchants"));

        long jobId = jobs.list().get(0).getId();
        long appId = applications.create(jobId, new ApplicationOptions(1, true, true, true, null, null, "available from January 2027", true)).getId();
        CheckReport report = applications.tailor(appId).report();
        assertThat(report.category(Category.TRUTH).errors()).as(report.category(Category.TRUTH).findings().toString()).isZero();
        assertThat(applications.tailoredOf(applications.get(appId)).orElseThrow().gapsNotAddressed()).contains("Apache Kafka");

        FitAdvice.Stored advice = fit.advise(appId);
        assertThat(advice.advice().recommendation()).isNotNull();
        assertThat(advice.model()).isEqualTo(DemoLlmTransport.MODEL);

        var written = letters.write(appId);
        assertThat(written.report().category(Category.TRUTH).errors()).as(written.report().all().toString()).isZero();
        assertThat(written.report().errors()).isEmpty();

        ExportFile pdf = exports.exportResume(appId, ExportedFile.Type.PDF, null);
        assertThat(pdf.getPageCount()).isEqualTo(1);
        assertThat(letters.export(appId, ExportedFile.Type.DOCX, null).getFileName()).endsWith(".docx");

        var update = freeText.propose("In my internship I moved the nightly batch job from cron to Spring Batch, and failures dropped from 4 a month to none.");
        assertThat(updates.preview(update).canApply()).isTrue();

        assertThat(calls.count()).isEqualTo(5);
        assertThat(calls.totalCost()).isEqualByComparingTo("0");
    }

    @Test
    void everyPageShowsTheDemoBanner() {
        assertThat(mvc.get().uri("/")).hasStatusOk().bodyText().contains("DEMO MODE", "Demo mode is on", "all time");
        assertThat(mvc.get().uri("/settings")).hasStatusOk().bodyText().contains("DEMO MODE");
    }
}

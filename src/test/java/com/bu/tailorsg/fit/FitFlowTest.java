package com.bu.tailorsg.fit;

import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.fit.FitFacts.Evidence;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FitFlowTest {

    @MockitoBean private ClaudeClient claude;
    @Autowired private JobService jobs;
    @Autowired private MasterResumeService master;
    @Autowired private ApplicationService applications;
    @Autowired private FitService fit;
    @Autowired private MockMvcTester mvc;
    @Autowired private JsonMapper json;

    private long appId;

    @BeforeEach
    void setUp() {
        when(claude.isConfigured()).thenReturn(true);
        when(claude.call(eq("jd-extract"), anyList())).thenReturn(result("jd-extract", "fixtures/jobs/llm-jobspec.json"));
        when(claude.call(eq("fit-advice"), anyList())).thenReturn(result("fit-advice", "fixtures/fit/llm-fit-advice.json"));
        master.loadSample();
        long jobId = jobs.fromText("Backend Engineer at Example Payments. Java, Spring Boot, PostgreSQL, REST APIs and Kafka "
                + "required; Kubernetes and AWS nice to have. We process payments for 2,000 merchants.").getId();
        appId = applications.create(jobId, ApplicationOptions.defaults()).getId();
    }

    @Test
    void factsClassifyEveryTermByItsEvidence() {
        FitFacts facts = fit.facts(applications.get(appId)).orElseThrow();
        assertThat(facts.required()).extracting(FitFacts.TermFit::term, FitFacts.TermFit::evidence).contains(
                org.assertj.core.groups.Tuple.tuple("REST APIs", Evidence.EVIDENCED),
                org.assertj.core.groups.Tuple.tuple("Kafka", Evidence.GAP));
        assertThat(facts.preferred()).extracting(FitFacts.TermFit::term, FitFacts.TermFit::evidence).contains(
                org.assertj.core.groups.Tuple.tuple("Kubernetes", Evidence.UNEVIDENCED),
                org.assertj.core.groups.Tuple.tuple("AWS", Evidence.EVIDENCED));
        assertThat(facts.requiredCoverage()).isEqualTo(0.8);
        assertThat(facts.flags()).anySatisfy(f -> assertThat(f.message()).contains("No salary stated", "Employment Pass"));
    }

    @Test
    void adviceIsVerifiedStoredAndShownAsAdvisory() {
        assertThat(mvc.post().uri("/applications/" + appId + "/fit/advice")).hasRedirectedUrl("/applications/" + appId + "?tab=fit");
        assertThat(applications.promptVersionsOf(applications.get(appId))).containsEntry("fit-advice", 1);

        assertThat(mvc.get().uri("/applications/" + appId + "?tab=fit")).hasStatusOk().bodyText()
                .contains("ADVISORY", "from code", "Apply, and address the gap", "Refund events with Kafka", "What code removed or flagged",
                        "UNEVIDENCED", "Removed a strength that implies a skill you have no evidence for (Kafka)");
        assertThat(fit.adviceOf(applications.get(appId)).orElseThrow().advice().strengths())
                .noneSatisfy(s -> assertThat(s.point()).contains("Kafka"));
        assertThat(mvc.get().uri("/api/applications/" + appId + "/fit")).hasStatusOk().bodyJson()
                .extractingPath("$.advice.advice.recommendation").isEqualTo("APPLY_AND_ADDRESS_GAP");
    }

    private LlmResult result(String prompt, String path) {
        return new LlmResult(json.readTree(TestResources.text(path)), prompt, 1, "test-model", 1, TokenUsage.ZERO, BigDecimal.ZERO);
    }
}

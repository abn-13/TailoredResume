package com.bu.tailorsg.fit;

import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.kw.KeywordMatcher;
import com.bu.tailorsg.checks.kw.SkillDictionary;
import com.bu.tailorsg.fit.FitFacts.Evidence;
import com.bu.tailorsg.fit.FitFacts.TermFit;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.support.TestResources;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The deterministic half of Phase 5b, and the verification that holds the model's advice to it. */
class FitServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MasterResume SAMPLE = JSON.readValue(TestResources.text("samples/master-sample.json"), MasterResume.class);

    @Test
    void yearsMergeOverlapsAndCountSoftwareRolesByTitle() {
        LocalDate today = LocalDate.of(2026, 10, 17);
        // work-2 Jul 2021 - Dec 2024 (42 months), work-1 May 2026 - now (6 months)
        assertThat(FitService.years(SAMPLE, false, today)).isEqualTo(4.0);
        assertThat(FitService.years(SAMPLE, true, today)).isEqualTo(0.5);
        assertThat(FitService.startDate(SAMPLE, today)).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(FitService.startDate(SAMPLE, LocalDate.of(2027, 6, 1))).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    @Test
    void yearsAskedTakesTheLargestStatedMinimum() {
        JobSpec job = new JobSpec("Engineer", null, null, null, null, null, List.of(), List.of(),
                List.of("Mentor 2 juniors"), List.of("3+ years of Java", "2-4 years with AWS", "Java 21"), List.of(), List.of(),
                null, false, List.of());
        assertThat(FitService.yearsAsked(job)).isEqualTo(3);
        assertThat(FitService.yearsAsked(JobSpec.ofTerms("x", List.of(), List.of()))).isNull();
    }

    @Test
    void epThresholdFollowsTheStartDate() {
        FitProperties props = new FitProperties(List.of(new FitProperties.EpThreshold(LocalDate.of(2025, 1, 1), 5600, 6200),
                new FitProperties.EpThreshold(LocalDate.of(2027, 1, 1), 6000, 6600)), "https://www.mom.gov.sg");
        assertThat(props.epThresholdOn(LocalDate.of(2026, 10, 17)).orElseThrow().general()).isEqualTo(5600);
        assertThat(props.epThresholdOn(LocalDate.of(2027, 1, 1)).orElseThrow().general()).isEqualTo(6000);
        assertThat(props.epThresholdOn(LocalDate.of(2024, 1, 1))).isEmpty();
    }

    @Test
    void verificationKeepsOnlyWhatTheFactsSupport() {
        FitFacts facts = new FitFacts(
                List.of(new TermFit("Java", true, Evidence.EVIDENCED, "work-1.h1", ""), new TermFit("Kafka", true, Evidence.GAP, null, null)),
                List.of(new TermFit("Kubernetes", false, Evidence.UNEVIDENCED, "skill-11", "")), 0.5, 0.0, null, 4.0, 0.5,
                "Jan 2027", List.of(new FitFacts.Flag("FIT-EP-SALARY", Severity.INFO, "No salary stated.")));
        FitAdvice raw = JSON.readValue(TestResources.text("fixtures/fit/llm-fit-advice.json"), FitAdvice.class);
        KeywordMatcher matcher = new KeywordMatcher(new SkillDictionary(new DefaultResourceLoader()));
        List<String> notes = new ArrayList<>();

        FitAdvice verified = FitService.verify(raw, facts, SAMPLE, matcher, notes);

        assertThat(verified.strengths()).extracting(FitAdvice.Strength::point)
                .containsExactly("Built and tested refund endpoints with Spring Boot and PostgreSQL");
        assertThat(verified.gapsToAddress()).extracting(FitAdvice.GapPlan::term).containsExactly("Kafka");
        assertThat(verified.projectSuggestion().targetsGaps()).containsExactly("Kafka");
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("implies a skill", "Kafka"))
                .anySatisfy(n -> assertThat(n).contains("cites nothing"))
                .anySatisfy(n -> assertThat(n).contains("Removed \"Java\""))
                .anySatisfy(n -> assertThat(n).contains("narrowed"))
                .anySatisfy(n -> assertThat(n).contains("20 days"));
    }

    @Test
    void aProjectThatTargetsNoGapIsRemoved() {
        FitFacts facts = new FitFacts(List.of(new TermFit("Java", true, Evidence.EVIDENCED, "work-1.h1", "")), List.of(), 1.0, 1.0,
                null, 4.0, 0.5, "Jan 2027", List.of());
        FitAdvice raw = new FitAdvice(FitAdvice.Recommendation.BUILD_PROJECT_FIRST, "h", List.of(), List.of(), List.of(),
                new FitAdvice.ProjectSuggestion("Learn Java", List.of("Java"), "d", List.of("a"), 5, "s"));
        List<String> notes = new ArrayList<>();
        FitAdvice verified = FitService.verify(raw, facts, SAMPLE,
                new KeywordMatcher(new SkillDictionary(new DefaultResourceLoader())), notes);
        assertThat(verified.projectSuggestion().isPresent()).isFalse();
        assertThat(notes).hasSize(2);
    }
}

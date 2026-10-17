package com.bu.tailorsg.health;

import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.updates.PendingUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MasterHealthTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private MasterResumeService master;
    @Autowired private MasterHealthService health;
    @Autowired private PendingUpdateService updates;

    @Test
    void healthPageScoresTheSampleAndListsUnevidencedSkills() {
        master.loadSample();

        assertThat(mvc.get().uri("/master/health")).hasStatusOk().bodyText()
                .contains("Scores are heuristics", "Singapore norms", "Bullet quality", "Kubernetes (skill-11)")
                .contains("add metric", "/add-info?type=metric&amp;highlightId=work-2.h3");
        assertThat(mvc.get().uri("/api/master/health")).hasStatusOk()
                .bodyJson().extractingPath("$.categories[*].category").asArray().contains("SG", "LINT");
    }

    @Test
    void sampleMasterHasNoSingaporeErrors() {
        master.loadSample();

        var report = health.health().orElseThrow().report();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void keywordCheckAgainstHandEnteredTerms() {
        master.loadSample();

        assertThat(mvc.post().uri("/master/health/keywords").param("title", "Backend Engineer")
                .param("required", "Java\nSpring Boot\nKafka").param("preferred", "Kubernetes, AWS"))
                .hasStatusOk().bodyText()
                .contains("Matched", "Java", "Gaps (not in your master)", "Kafka", "Keyword match");
    }

    @Test
    void livelintPreviewFlagsWeakDraftsAndPraisesGoodOnes() {
        master.loadSample();

        assertThat(mvc.post().uri("/add-info/lint-preview").param("parentId", "work-2")
                .param("text", "Responsible for testing"))
                .hasStatusOk().bodyText().contains("LINT-01", "Responsible for");
        assertThat(mvc.post().uri("/add-info/lint-preview").param("parentId", "work-1")
                .param("text", "Reduced refund processing time by 30% by batching database writes with Spring Batch"))
                .hasStatusOk().bodyText().contains("Looks good");
    }

    @Test
    void addMetricQuickFixProposesAnUpdate() {
        master.loadSample();

        assertThat(mvc.get().uri("/add-info?type=metric&highlightId=work-2.h3")).hasStatusOk().bodyText()
                .contains("Add a metric to", "Coordinated design reviews with suppliers");
        assertThat(mvc.post().uri("/add-info/metric").param("highlightId", "work-2.h3").param("value", "6")
                .param("label", "suppliers").param("text", "Coordinated design reviews with suppliers in Malaysia and China"))
                .hasStatusOk().bodyText().contains("Mention the number in the highlight text");
        assertThat(mvc.post().uri("/add-info/metric").param("highlightId", "work-2.h3").param("value", "6")
                .param("label", "suppliers").param("text", "Coordinated design reviews with 6 suppliers in Malaysia and China"))
                .hasStatus3xxRedirection();
        assertThat(updates.list(null).getFirst().getSummary()).isEqualTo("Add metric to work-2.h3: 6 (suppliers)");
        assertThat(updates.preview(updates.list(null).getFirst()).canApply()).isTrue();
    }
}

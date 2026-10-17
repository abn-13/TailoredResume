package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.documents.TestDocuments;
import com.bu.tailorsg.llm.LlmCall;
import com.bu.tailorsg.llm.LlmCallRepository;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Imports a long fake resume (fixtures/import/long-resume.txt: "Casey Ong", about 18,500 characters, 78
 * bullets, three pages) through the real extractor model and the whole import pipeline.
 *
 * <p>Regression (2026-10-17): a real resume this long failed every time, because the entries call was cut
 * off at max-tokens (16,000). Adaptive thinking used about 6,600 of those tokens; the JSON itself needs about
 * 11,000. The import prompts now disable thinking, and this checks a long resume still fits with headroom.
 *
 * <p>Cost: two Haiku 5.5 calls, about $0.007, about 45 seconds. Skipped without ANTHROPIC_API_KEY; to skip
 * it even with a key: {@code ./mvnw test -DexcludedGroups=live}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Transactional
class LongResumeImportLiveTest {

    /** Each call must leave at least this share of max-tokens unused, so slightly longer resumes still fit. */
    private static final double MAX_SHARE_OF_LIMIT = 0.85;

    @Autowired private ResumeImportService importer;
    @Autowired private PendingUpdateService updates;
    @Autowired private LlmCallRepository calls;
    @Autowired private TailorSgProperties props;

    @Test
    void aThreePageResumeImportsCompletelyWithRoomToSpare() {
        List<String> lines = Arrays.asList(ImportPipelineUnitTest.fixture("long-resume.txt").split("\n"));

        PendingUpdate update = importer.importResume("casey-ong.docx", TestDocuments.docx(lines));

        assertThat(update.getSummary()).contains("6 jobs, 3 projects, 2 education, 78 highlights");
        assertThat(updates.preview(update).canApply()).isTrue();
        List<LlmCall> importCalls = calls.findTop20ByOrderByCreatedAtDescIdDesc().stream()
                .filter(c -> ResumeImportService.PROMPTS.contains(c.getPromptId())).toList();
        assertThat(importCalls).hasSize(2).allSatisfy(c -> assertThat(c.getOutputTokens()).as(c.getPromptId())
                .isLessThan((long) (props.llm().extractor().maxTokens() * MAX_SHARE_OF_LIMIT)));
    }
}

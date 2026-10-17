package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.documents.TestDocuments;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.JsonSchemaService;
import com.bu.tailorsg.llm.PromptLibrary;
import com.bu.tailorsg.llm.TokenUsage;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateRepository;
import com.bu.tailorsg.updates.PendingUpdateService;
import com.bu.tailorsg.updates.PendingUpdateService.Preview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * An import whose result fails master validation must still be saved, so the user can repair it on the
 * review page (imports are proposed with {@code mustBeValid = false}).
 *
 * <p>Regression (2026-10-17): this crashed with {@code UnexpectedRollbackException} ("Transaction silently
 * rolled back because it has been marked as rollback-only"). The preview's {@code validateDocument} call is
 * {@code @Transactional} and joined the caller's transaction; its {@code MasterValidationException} marked
 * that transaction rollback-only even though the preview caught it, so the commit failed.
 *
 * <p>Deliberately NOT {@code @Transactional}: a test-managed transaction is rolled back, never committed,
 * which is exactly why every other test missed this. So this class deletes what it creates.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportNeedingRepairTest {

    /** The mapper keeps a phone it cannot normalise (with a warning); the master schema then rejects it. */
    private static final String UNFIXABLE_PHONE = "65 9123 4567";

    @MockitoBean private ClaudeClient claude;
    @Autowired private ResumeImportService importer;
    @Autowired private PendingUpdateService updates;
    @Autowired private PendingUpdateRepository repository;
    @Autowired private JsonSchemaService schemas;
    @Autowired private PromptLibrary prompts;
    @Autowired private JsonMapper json;
    @Autowired private MockMvcTester mvc;

    @BeforeEach
    void modelReturnsAResumeWithAnUnfixablePhone() {
        ObjectNode llmOutput = (ObjectNode) json.readTree(ImportPipelineUnitTest.fixture("llm-output.json"));
        ((ObjectNode) llmOutput.get("basics")).put("phone", UNFIXABLE_PHONE);
        for (String prompt : ResumeImportService.PROMPTS) {
            JsonNode part = ImportSchemaSplitTest.slice(llmOutput, schemas.rawSchema(prompts.get(prompt).schemaPath()));
            when(claude.callJson(eq(prompt), anyList())).thenReturn(
                    new LlmResult(part, prompt, 1, "claude-haiku-5-5", 1, TokenUsage.ZERO, BigDecimal.ZERO));
        }
    }

    @AfterEach
    void deleteWhatThisTestCommitted() {
        repository.deleteAll();
    }

    @Test
    void anImportThatNeedsFixingIsSavedAndItsReviewPageOpens() {
        PendingUpdate update = importer.importResume("alex-lim.docx", resumeDocx());

        assertThat(repository.findById(update.getId())).isPresent();
        Preview preview = updates.preview(update); // its own read-only transaction: also crashed before the fix
        assertThat(preview.canApply()).isFalse();
        assertThat(preview.errors()).anySatisfy(e -> assertThat(e.path()).isEqualTo("/basics/phone"));
        assertThat(mvc.get().uri("/updates/" + update.getId())).hasStatusOk()
                .bodyText().contains(UNFIXABLE_PHONE);
    }

    @Test
    void savingAnEditedPatchThatIsStillInvalidKeepsTheEdit() {
        PendingUpdate update = importer.importResume("alex-lim.docx", resumeDocx());
        JsonNode patch = updates.patchOf(update);
        ((ObjectNode) patch.at("/0/value/basics")).put("name", "Alex Lim Edited"); // phone still invalid

        Preview preview = updates.replacePatch(update.getId(), patch.toString());

        assertThat(preview.canApply()).isFalse();
        assertThat(updates.patchOf(updates.get(update.getId())).at("/0/value/basics/name").stringValue())
                .isEqualTo("Alex Lim Edited");
    }

    private static byte[] resumeDocx() {
        return TestDocuments.docx(Arrays.asList(ImportPipelineUnitTest.fixture("resume.txt").split("\n")));
    }
}

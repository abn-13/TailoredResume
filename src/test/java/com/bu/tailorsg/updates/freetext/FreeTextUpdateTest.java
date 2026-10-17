package com.bu.tailorsg.updates.freetext;

import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.llm.LlmTransport.UserBlock;
import com.bu.tailorsg.llm.TokenUsage;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.support.TestResources;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 8: free text into a reviewed PendingUpdate, held to the user's own words. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FreeTextUpdateTest {

    static final String TEXT = "In my internship I moved the refund service's nightly batch job from cron to a Spring Batch job "
            + "on Kubernetes. Failures dropped from about 4 a month to none.\nNRIC: S1234567D";

    @MockitoBean private ClaudeClient claude;
    @Autowired private FreeTextUpdateService freeText;
    @Autowired private PendingUpdateService updates;
    @Autowired private MasterResumeService master;
    @Autowired private MockMvcTester mvc;
    @Autowired private JsonMapper json;

    @BeforeEach
    void setUp() {
        when(claude.isConfigured()).thenReturn(true);
        when(claude.call(eq("master-update"), anyList())).thenReturn(new LlmResult(
                json.readTree(TestResources.text("fixtures/updates/llm-master-update.json")), "master-update", 1, "test-model", 1,
                TokenUsage.ZERO, BigDecimal.ZERO));
        master.loadSample();
    }

    @Test
    void proposalIsCheckedAgainstWhatTheUserWrote() {
        PendingUpdate update = freeText.propose(TEXT);

        assertThat(update.getSourceType()).isEqualTo(PendingUpdate.Source.FREE_TEXT);
        assertThat(update.getRawInput()).doesNotContain("S1234567D");
        assertThat(updates.notesOf(update)).anySatisfy(n -> assertThat(n).contains("Ignored a change to /meta/version"))
                .anySatisfy(n -> assertThat(n).contains("not in what you wrote: 12"))
                .anySatisfy(n -> assertThat(n).contains("New skill \"Terraform\""))
                .anySatisfy(n -> assertThat(n).isEqualTo("Question: Which month did failures drop to none?"))
                .noneSatisfy(n -> assertThat(n).contains("Spring Batch\" is not mentioned"));
        assertThat(updates.patchOf(update).toString()).doesNotContain("/meta");

        var preview = updates.preview(update);
        assertThat(preview.canApply()).as(String.valueOf(preview.errors())).isTrue();
        assertThat(preview.assignedIds()).contains("work-1.h4");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(claude).call(eq("master-update"), blocks.capture());
        assertThat(((TextBlock) blocks.getValue().get(0)).cacheable()).isTrue();
        assertThat(((TextBlock) blocks.getValue().get(1)).text()).startsWith("<USER_TEXT>").doesNotContain("S1234567D");
    }

    @Test
    void answeringQuestionsReplacesTheProposal() {
        PendingUpdate first = freeText.propose(TEXT);
        PendingUpdate second = freeText.answer(first.getId(), "In August 2026.");

        assertThat(updates.get(first.getId()).getStatus()).isEqualTo(PendingUpdate.Status.REJECTED);
        assertThat(second.getStatus()).isEqualTo(PendingUpdate.Status.PROPOSED);
        assertThat(second.getRawInput()).contains("Answers to your questions (Which month did failures drop to none?):\nIn August 2026.");
        verify(claude, atLeastOnce()).call(eq("master-update"), anyList());
        assertThatThrownBy(() -> freeText.answer(first.getId(), "again")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pagesAndApi() {
        var response = mvc.post().uri("/add-info/free-text").param("text", TEXT).exchange();
        assertThat(response).hasStatus3xxRedirection();
        String location = response.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/updates/");
        assertThat(mvc.get().uri(location)).hasStatusOk().bodyText()
                .contains("What you wrote", "Answer the questions", "Which month did failures drop to none?", "Apply to master resume");

        assertThat(mvc.post().uri("/add-info/free-text").param("text", "short")).flash().containsKey("flashError");
        assertThat(mvc.post().uri("/api/updates/free-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"" + TEXT.replace("\n", "\\n") + "\"}")).hasStatus(201).bodyJson().extractingPath("$.review")
                .asString().startsWith("/updates/");
        assertThat(mvc.get().uri("/add-info?type=free-text")).hasStatusOk().bodyText().contains("Propose a change");
    }
}

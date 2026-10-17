package com.bu.tailorsg.coverletter;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.applications.ApplicationTabContributor;
import com.bu.tailorsg.export.ExportBlockedException;
import com.bu.tailorsg.export.ExportFile;
import com.bu.tailorsg.export.ExportedFile;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.master.MasterResumeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Cover letter tab and its API (spec 3: POST /api/applications/{id}/cover-letter). Paragraph edits are
 * htmx requests that return the refreshed letter workspace.
 */
@Controller
public class CoverLetterController implements ApplicationTabContributor {

    private final CoverLetterService letters;
    private final ApplicationService applications;
    private final MasterResumeService masters;
    private final ClaudeClient claude;

    public CoverLetterController(CoverLetterService letters, ApplicationService applications, MasterResumeService masters,
                                 ClaudeClient claude) {
        this.letters = letters;
        this.applications = applications;
        this.masters = masters;
        this.claude = claude;
    }

    @Override
    public void contribute(Application a, String activeTab, Model model) {
        if ("cover".equals(activeTab)) {
            populate(a, model);
        }
    }

    private void populate(Application a, Model model) {
        CoverLetterDraft draft = letters.draftOf(a).orElse(null);
        model.addAttribute("letter", draft);
        model.addAttribute("letterDoc", letters.documentOf(a).orElse(null));
        model.addAttribute("letterReport", letters.reportOf(a).orElse(null));
        model.addAttribute("dashChanges", draft == null ? java.util.List.of() : letters.dashChanges(draft));
        model.addAttribute("letterSources", sources());
    }

    private Map<String, String> sources() {
        Map<String, String> texts = new LinkedHashMap<>();
        masters.currentResume().ifPresent(m -> m.highlightsById().forEach((id, h) -> texts.put(id, h.text())));
        return texts;
    }

    /** Saves the notes the letter may use (USER_NOTES), then writes the letter. */
    @PostMapping("/applications/{id}/cover-letter")
    public String write(@PathVariable long id, @RequestParam(required = false) String whyCompany,
                        @RequestParam(required = false) String availability, @RequestParam(required = false) String tone,
                        @RequestParam(defaultValue = "false") boolean mentionWorkPass, RedirectAttributes redirect) {
        ApplicationOptions o = applications.optionsOf(applications.get(id));
        applications.updateOptions(id, new ApplicationOptions(o.pageTarget(), o.includeSummary(), o.showWorkAuth(), true,
                tone == null ? o.tone() : tone, blankToNull(whyCompany), blankToNull(availability), mentionWorkPass));
        if (!claude.isConfigured()) {
            redirect.addFlashAttribute("flashError", "Notes saved. Writing the letter needs ANTHROPIC_API_KEY (or demo mode).");
        } else {
            try {
                var written = letters.write(id);
                StringBuilder msg = new StringBuilder("Cover letter written and checked");
                if (written.costUsd() != null) {
                    msg.append(" (about US$").append(written.costUsd().setScale(3, RoundingMode.HALF_UP)).append(")");
                }
                msg.append(". ");
                written.notes().forEach(n -> msg.append(n).append(' '));
                redirect.addFlashAttribute("flash", msg.toString().strip());
            } catch (LlmException | IllegalStateException e) {
                redirect.addFlashAttribute("flashError", "Writing the letter failed: " + e.getMessage());
            }
        }
        return "redirect:/applications/" + id + "?tab=cover";
    }

    /** htmx: save one edited paragraph, re-check, return the refreshed letter workspace. */
    @PostMapping("/applications/{id}/cover-letter/paragraphs/edit")
    public String editParagraph(@PathVariable long id, @RequestParam int index, @RequestParam(defaultValue = "") String text, Model model) {
        try {
            letters.editParagraph(id, index, text);
            model.addAttribute("letterMessage", "Saved and re-checked.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("letterError", e.getMessage());
        }
        Application a = applications.get(id);
        model.addAttribute("app", a);
        populate(a, model);
        return "applications/cover :: letterWorkspace";
    }

    @PostMapping("/applications/{id}/cover-letter/fix-dashes")
    public String fixDashes(@PathVariable long id, RedirectAttributes redirect) {
        try {
            letters.fixDashes(id);
            redirect.addFlashAttribute("flash", "Dashes replaced and the letter re-checked.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/applications/" + id + "?tab=cover";
    }

    @PostMapping("/applications/{id}/cover-letter/export")
    public String export(@PathVariable long id, @RequestParam(defaultValue = "PDF") String type,
                         @RequestParam(defaultValue = "") String overrideReason, RedirectAttributes redirect) {
        try {
            ExportFile f = letters.export(id, ExportedFile.Type.valueOf(type.toUpperCase(Locale.ROOT)), overrideReason);
            redirect.addFlashAttribute("flash", "Saved " + f.getFileName() + ". Download it from the Export tab.");
        } catch (ExportBlockedException | IllegalStateException | IllegalArgumentException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/applications/" + id + "?tab=cover";
    }

    // ---------------------------------------------------------------- JSON API

    @PostMapping("/api/applications/{id}/cover-letter")
    @ResponseBody
    public ResponseEntity<?> apiWrite(@PathVariable long id) {
        applications.get(id);
        if (!claude.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", "ANTHROPIC_API_KEY is not set."));
        }
        return ResponseEntity.ok(letters.write(id));
    }

    @GetMapping("/api/applications/{id}/cover-letter")
    @ResponseBody
    public Map<String, Object> apiGet(@PathVariable long id) {
        Application a = applications.get(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draft", letters.draftOf(a).orElse(null));
        body.put("letter", letters.documentOf(a).orElse(null));
        body.put("report", letters.reportOf(a).orElse(null));
        return body;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

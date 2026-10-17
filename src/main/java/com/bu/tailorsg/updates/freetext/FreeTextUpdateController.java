package com.bu.tailorsg.updates.freetext;

import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.updates.PendingUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Free text into a proposed master update (spec 3: POST /api/updates/free-text), plus the Add Info page form. */
@Controller
public class FreeTextUpdateController {

    private final FreeTextUpdateService freeText;
    private final ClaudeClient claude;

    public FreeTextUpdateController(FreeTextUpdateService freeText, ClaudeClient claude) {
        this.freeText = freeText;
        this.claude = claude;
    }

    @PostMapping("/add-info/free-text")
    public String propose(@RequestParam(defaultValue = "") String text, RedirectAttributes redirect) {
        if (!claude.isConfigured()) {
            redirect.addFlashAttribute("flashError", "Free-text updates need ANTHROPIC_API_KEY (or demo mode). Use the forms meanwhile.");
            redirect.addFlashAttribute("freeText", text);
            return "redirect:/add-info?type=free-text";
        }
        try {
            PendingUpdate update = freeText.propose(text);
            redirect.addFlashAttribute("flash", "Proposed a change. Review it, answer any questions, then apply.");
            return "redirect:/updates/" + update.getId();
        } catch (IllegalArgumentException | LlmException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            redirect.addFlashAttribute("freeText", text);
            return "redirect:/add-info?type=free-text";
        }
    }

    /** The answer-and-regenerate loop on the review page. */
    @PostMapping("/updates/{id}/answer")
    public String answer(@PathVariable long id, @RequestParam(defaultValue = "") String answers, RedirectAttributes redirect) {
        try {
            PendingUpdate fresh = freeText.answer(id, answers);
            redirect.addFlashAttribute("flash", "Updated the proposal with your answers (update #" + id + " was replaced).");
            return "redirect:/updates/" + fresh.getId();
        } catch (IllegalArgumentException | LlmException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/updates/" + id;
        }
    }

    public record FreeTextRequest(String text) {}

    @PostMapping(path = "/api/updates/free-text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> api(@RequestBody FreeTextRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!claude.isConfigured()) {
            body.put("detail", "ANTHROPIC_API_KEY is not set.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            PendingUpdate u = freeText.propose(request.text());
            body.put("id", u.getId());
            body.put("summary", u.getSummary());
            body.put("review", "/updates/" + u.getId());
            return ResponseEntity.created(URI.create("/api/updates/" + u.getId())).body(body);
        } catch (IllegalArgumentException e) {
            body.put("detail", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
    }
}

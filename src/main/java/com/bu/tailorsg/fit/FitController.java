package com.bu.tailorsg.fit;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.applications.ApplicationTabContributor;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application page's Fit tab, and its JSON API: GET /api/applications/{id}/fit (facts + stored advice),
 * POST /api/applications/{id}/fit (ask the model for advice).
 */
@Controller
public class FitController implements ApplicationTabContributor {

    private final FitService fit;
    private final ApplicationService applications;
    private final ClaudeClient claude;

    public FitController(FitService fit, ApplicationService applications, ClaudeClient claude) {
        this.fit = fit;
        this.applications = applications;
        this.claude = claude;
    }

    @Override
    public void contribute(Application a, String activeTab, Model model) {
        if ("fit".equals(activeTab)) {
            model.addAttribute("fitFacts", fit.facts(a).orElse(null));
            model.addAttribute("fitAdvice", fit.adviceOf(a).orElse(null));
        }
    }

    @PostMapping("/applications/{id}/fit/advice")
    public String advise(@PathVariable long id, RedirectAttributes redirect) {
        applications.get(id);
        if (!claude.isConfigured()) {
            redirect.addFlashAttribute("flashError", "Advice needs ANTHROPIC_API_KEY (or demo mode).");
        } else {
            try {
                FitAdvice.Stored stored = fit.advise(id);
                redirect.addFlashAttribute("flash", "Advice updated (advisory)." + (stored.notes().isEmpty() ? ""
                        : " Code removed or flagged " + stored.notes().size() + " part" + (stored.notes().size() == 1 ? "" : "s") + " of it; see below."));
            } catch (LlmException | IllegalStateException e) {
                redirect.addFlashAttribute("flashError", "Advice failed: " + e.getMessage());
            }
        }
        return "redirect:/applications/" + id + "?tab=fit";
    }

    @GetMapping("/api/applications/{id}/fit")
    @ResponseBody
    public Map<String, Object> get(@PathVariable long id) {
        Application a = applications.get(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("facts", fit.facts(a).orElse(null));
        body.put("advice", fit.adviceOf(a).orElse(null));
        body.put("label", "ADVISORY: never changes a check result");
        return body;
    }

    @PostMapping("/api/applications/{id}/fit")
    @ResponseBody
    public ResponseEntity<?> post(@PathVariable long id) {
        applications.get(id);
        if (!claude.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", "ANTHROPIC_API_KEY is not set."));
        }
        return ResponseEntity.ok(fit.advise(id));
    }
}

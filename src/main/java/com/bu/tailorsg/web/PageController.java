package com.bu.tailorsg.web;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.ThresholdService;
import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.llm.LlmPing;
import com.bu.tailorsg.llm.LlmUsageMeter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Server-rendered shared pages (Thymeleaf): dashboard and settings. */
@Controller
public class PageController {

    private final SystemStatusService statusService;
    private final TailorSgProperties props;
    private final LlmPing llmPing;
    private final LlmUsageMeter meter;
    private final ApplicationService applications;
    private final ThresholdService thresholds;

    public PageController(SystemStatusService statusService, TailorSgProperties props,
                          LlmPing llmPing, LlmUsageMeter meter, ApplicationService applications, ThresholdService thresholds) {
        this.thresholds = thresholds;
        this.statusService = statusService;
        this.props = props;
        this.llmPing = llmPing;
        this.meter = meter;
        this.applications = applications;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("status", statusService.current());
        model.addAttribute("applications", applications.list().stream().limit(10).toList());
        model.addAttribute("statuses", Application.Status.values());
        return "dashboard";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("props", props);
        model.addAttribute("status", statusService.current());
        model.addAttribute("recentCalls", meter.recent());
        model.addAttribute("thresholdsOverridden", thresholds.isOverridden());
        if (!model.containsAttribute("thresholdsJson")) {
            model.addAttribute("thresholdsJson", thresholds.currentJson());
        }
        return "settings";
    }

    /** Saves edited thresholds (Phase 9). Invalid input is shown again with the problems, nothing is saved. */
    @PostMapping("/settings/thresholds")
    public String saveThresholds(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String thresholdsJson,
                                 org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        var problems = thresholds.save(thresholdsJson);
        if (problems.isEmpty()) {
            redirect.addFlashAttribute("flash", "Thresholds saved. They apply to the next check run; re-check applications to update their scorecards.");
        } else {
            redirect.addFlashAttribute("flashError", "Not saved: " + String.join(" ", problems));
            redirect.addFlashAttribute("thresholdsJson", thresholdsJson);
        }
        return "redirect:/settings#thresholds";
    }

    @PostMapping("/settings/thresholds/reset")
    public String resetThresholds(org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        thresholds.reset();
        redirect.addFlashAttribute("flash", "Thresholds reset to the application.yml defaults.");
        return "redirect:/settings#thresholds";
    }

    /**
     * htmx endpoint behind the "Test Claude connection" button. Returns an HTML fragment, not a
     * full page: htmx swaps it into the page (see fragments/ping-result.html).
     */
    @PostMapping("/settings/ping")
    public String settingsPing(Model model) {
        model.addAttribute("ping", llmPing.ping());
        // Overwrites the value LayoutAdvice set before the call, so the footer shows the new total.
        model.addAttribute("llmUsage", meter.snapshot());
        return "fragments/ping-result";
    }
}

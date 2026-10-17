package com.bu.tailorsg.applications;

import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.llm.LlmUsageMeter;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.tailoring.TailoredResume;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application pages (spec 7: "Application detail"): the tailored preview beside the scorecard, inline bullet
 * edits that re-run the checks, and the keyword panel's "add this bullet". Tabs: Resume, Fit, Cover letter,
 * Export. Inline edits are htmx requests that re-render the whole workspace (preview + scorecard), because
 * one edit can change findings anywhere (keyword counts, page estimate).
 */
@Controller
public class ApplicationPageController {

    static final Set<String> TABS = Set.of("resume", "fit", "cover", "export");

    private final ApplicationService applications;
    private final MasterResumeService masters;
    private final ClaudeClient claude;
    private final LlmUsageMeter meter;
    private final List<ApplicationTabContributor> contributors;

    public ApplicationPageController(ApplicationService applications, MasterResumeService masters, ClaudeClient claude,
                                     LlmUsageMeter meter, List<ApplicationTabContributor> contributors) {
        this.applications = applications;
        this.masters = masters;
        this.claude = claude;
        this.meter = meter;
        this.contributors = contributors;
    }

    /** Step 3 of the New Application flow (form on the job page): create, then tailor straight away. */
    @PostMapping("/applications")
    public String create(@RequestParam long jobId, @RequestParam(defaultValue = "1") int pageTarget,
                         @RequestParam(defaultValue = "false") boolean includeSummary,
                         @RequestParam(defaultValue = "false") boolean showWorkAuth,
                         @RequestParam(defaultValue = "false") boolean coverLetter,
                         @RequestParam(required = false) String tone, @RequestParam(required = false) String whyCompany,
                         @RequestParam(required = false) String availability,
                         @RequestParam(defaultValue = "false") boolean mentionWorkPass, RedirectAttributes redirect) {
        Application a = applications.create(jobId, new ApplicationOptions(pageTarget, includeSummary, showWorkAuth,
                coverLetter, tone, blankToNull(whyCompany), blankToNull(availability), mentionWorkPass));
        tailorInto(a.getId(), redirect);
        return "redirect:/applications/" + a.getId();
    }

    @GetMapping("/applications")
    public String list(Model model) {
        model.addAttribute("applications", applications.list());
        model.addAttribute("statuses", Application.Status.values());
        return "applications/list";
    }

    @GetMapping("/applications/{id}")
    public String detail(@PathVariable long id, @RequestParam(defaultValue = "resume") String tab, Model model) {
        Application a = applications.get(id);
        String activeTab = TABS.contains(tab) ? tab : "resume";
        populate(model, a);
        model.addAttribute("tab", activeTab);
        for (ApplicationTabContributor c : contributors) {
            c.contribute(a, activeTab, model);
        }
        return "applications/detail";
    }

    @PostMapping("/applications/{id}/tailor")
    public String tailor(@PathVariable long id, RedirectAttributes redirect) {
        applications.get(id);
        tailorInto(id, redirect);
        return "redirect:/applications/" + id;
    }

    @PostMapping("/applications/{id}/check")
    public String recheck(@PathVariable long id, RedirectAttributes redirect) {
        try {
            applications.recheck(id);
            redirect.addFlashAttribute("flash", "Checks re-run.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/applications/" + id;
    }

    /** htmx: save one edited bullet (or the summary), re-check, and return the refreshed workspace. */
    @PostMapping("/applications/{id}/bullets/edit")
    public String editBullet(@PathVariable long id, @RequestParam String itemId, @RequestParam(defaultValue = "0") int index,
                             @RequestParam(defaultValue = "") String text, Model model) {
        return workspace(id, model, () -> applications.editBullet(id, itemId, index, text), "Saved and re-checked.");
    }

    @PostMapping("/applications/{id}/bullets/remove")
    public String removeBullet(@PathVariable long id, @RequestParam String itemId, @RequestParam(defaultValue = "0") int index,
                               Model model) {
        return workspace(id, model, () -> applications.removeBullet(id, itemId, index), "Removed and re-checked.");
    }

    /** Keyword panel: add an evidenced bullet the user confirmed (plain form post, then back to the resume). */
    @PostMapping("/applications/{id}/bullets/add")
    public String addBullet(@PathVariable long id, @RequestParam String itemId, @RequestParam String sourceId,
                            @RequestParam(defaultValue = "") String text, RedirectAttributes redirect) {
        try {
            applications.addBullet(id, itemId, sourceId, text);
            redirect.addFlashAttribute("flash", "Added a bullet to " + itemId + " citing " + sourceId + ", and re-checked.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/applications/" + id;
    }

    @PostMapping("/applications/{id}/status")
    public String status(@PathVariable long id, @RequestParam Application.Status status,
                         @RequestParam(defaultValue = "") String returnTo, RedirectAttributes redirect) {
        applications.setStatus(id, status);
        redirect.addFlashAttribute("flash", "Status set to " + status + ".");
        return "dashboard".equals(returnTo) ? "redirect:/" : "list".equals(returnTo) ? "redirect:/applications"
                : "redirect:/applications/" + id;
    }

    // ---------------------------------------------------------------- helpers

    private void tailorInto(long id, RedirectAttributes redirect) {
        if (!claude.isConfigured()) {
            redirect.addFlashAttribute("flashError", "Application saved, but tailoring needs ANTHROPIC_API_KEY "
                    + "(or demo mode). Set it, restart, then press Tailor.");
            return;
        }
        try {
            var outcome = applications.tailor(id);
            StringBuilder msg = new StringBuilder("Tailored and checked");
            if (outcome.costUsd() != null) {
                msg.append(" (about US$").append(outcome.costUsd().setScale(3, java.math.RoundingMode.HALF_UP)).append(")");
            }
            msg.append(". ");
            outcome.notes().forEach(n -> msg.append(n).append(' '));
            msg.append(outcome.report().hasErrors() ? "Fix the ERRORs below before exporting." : "No blocking errors.");
            redirect.addFlashAttribute("flash", msg.toString().strip());
        } catch (LlmException | IllegalStateException e) {
            redirect.addFlashAttribute("flashError", "Tailoring failed: " + e.getMessage());
        }
    }

    interface Edit {
        CheckReport run();
    }

    private String workspace(long id, Model model, Edit edit, String ok) {
        try {
            edit.run();
            model.addAttribute("workspaceMessage", ok);
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("workspaceError", e.getMessage());
        }
        populate(model, applications.get(id));
        model.addAttribute("tab", "resume");
        return "applications/detail :: workspace";
    }

    private void populate(Model model, Application a) {
        ApplicationOptions options = applications.optionsOf(a);
        TailoredResume tailored = applications.tailoredOf(a).orElse(null);
        ResumeDocument doc = applications.documentOf(a).orElse(null);
        CheckReport report = applications.reportOf(a).orElse(null);
        Integer currentMaster = masters.current().map(StoredVersion::versionNo).orElse(null);
        model.addAttribute("app", a);
        model.addAttribute("job", applications.jobOf(a));
        model.addAttribute("spec", applications.specOf(a));
        model.addAttribute("options", options);
        model.addAttribute("tailored", tailored);
        model.addAttribute("doc", doc);
        model.addAttribute("report", report);
        model.addAttribute("sources", sourceTexts(masters.currentResume().orElse(null)));
        model.addAttribute("metricTargets", metricTargets(doc));
        model.addAttribute("currentMasterVersion", currentMaster);
        model.addAttribute("masterChanged", a.getMasterVersionNo() != null && currentMaster != null
                && !a.getMasterVersionNo().equals(currentMaster));
        model.addAttribute("promptVersions", applications.promptVersionsOf(a));
        model.addAttribute("llmConfigured", claude.isConfigured());
        model.addAttribute("statuses", Application.Status.values());
        model.addAttribute("llmUsage", meter.snapshot());
    }

    /** Bullet anchor to its first cited highlight: where a LINT-02 "add a metric" link should go. */
    static Map<String, String> metricTargets(ResumeDocument doc) {
        Map<String, String> targets = new LinkedHashMap<>();
        if (doc != null) {
            doc.sections().forEach(s -> s.entries().forEach(e -> e.bullets().forEach(b -> b.sourceIds().stream()
                    .filter(id -> id.contains(".h")).findFirst().ifPresent(id -> targets.put(b.anchor(), id)))));
        }
        return targets;
    }

    /** Highlight and summary-variant ID to its master text, for "hover a bullet to see its sources" (spec 7). */
    static Map<String, String> sourceTexts(MasterResume m) {
        Map<String, String> texts = new LinkedHashMap<>();
        if (m != null) {
            m.highlightsById().forEach((id, h) -> texts.put(id, h.text()));
            m.basics().summaryVariants().forEach(s -> texts.put(s.id(), s.text()));
        }
        return texts;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

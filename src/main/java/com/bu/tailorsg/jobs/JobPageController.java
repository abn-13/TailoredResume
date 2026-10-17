package com.bu.tailorsg.jobs;

import com.bu.tailorsg.health.MasterHealthService;
import com.bu.tailorsg.jobs.JobSpec.JdSkill;
import com.bu.tailorsg.jobs.JobSpec.SalaryRange;
import com.bu.tailorsg.jobs.source.BlockedSiteException;
import com.bu.tailorsg.json.JsonText;
import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.web.DisplayFormats;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * New Application (step 1: get the job description) and the JobSpec editor (step 2: check and correct it).
 * Step 3, the application options and tailoring, posts to /applications.
 */
@Controller
public class JobPageController {

    private final JobService jobs;
    private final ClaudeClient claude;
    private final MasterHealthService health;
    private final MasterResumeService master;
    private final JsonText jsonText;
    private final JsonMapper json;

    public JobPageController(JobService jobs, ClaudeClient claude, MasterHealthService health, MasterResumeService master,
                             JsonText jsonText, JsonMapper json) {
        this.jobs = jobs;
        this.claude = claude;
        this.health = health;
        this.master = master;
        this.jsonText = jsonText;
        this.json = json;
    }

    @GetMapping("/applications/new")
    public String newApplication(@RequestParam(defaultValue = "link") String tab, Model model) {
        model.addAttribute("active", "new-application");
        model.addAttribute("tab", List.of("link", "screenshots", "paste").contains(tab) ? tab : "link");
        model.addAttribute("llmConfigured", claude.isConfigured());
        model.addAttribute("hasMaster", master.current().isPresent());
        model.addAttribute("recent", jobs.list().stream().limit(8).toList());
        return "jobs/new";
    }

    @PostMapping("/jobs/from-url")
    public String fromUrl(@RequestParam(defaultValue = "") String url, RedirectAttributes redirect) {
        return handle(() -> jobs.fromUrl(url), "link", redirect);
    }

    @PostMapping("/jobs/from-text")
    public String fromText(@RequestParam(defaultValue = "") String text, RedirectAttributes redirect) {
        return handle(() -> jobs.fromText(text), "paste", redirect);
    }

    @PostMapping("/jobs/from-image")
    public String fromImages(@RequestParam("files") List<MultipartFile> files, RedirectAttributes redirect) {
        return handle(() -> {
            try {
                return jobs.fromImages(JobsApiController.images(files));
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read the upload.");
            }
        }, "screenshots", redirect);
    }

    private interface Action {
        JobPosting run();
    }

    private String handle(Action action, String tab, RedirectAttributes redirect) {
        try {
            JobPosting posting = action.run();
            redirect.addFlashAttribute("flash", "Read the posting. Check the extracted details below, fix anything wrong, then set up the application.");
            return "redirect:/jobs/" + posting.getId();
        } catch (BlockedSiteException e) {
            redirect.addFlashAttribute("flashError", BlockedSiteException.ADVICE + " (Reason: " + e.reason() + ".)");
        } catch (IllegalArgumentException | IllegalStateException | LlmException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/applications/new?tab=" + tab;
    }

    @GetMapping("/jobs")
    public String list(Model model) {
        model.addAttribute("active", "new-application");
        model.addAttribute("rows", jobs.list().stream().map(j -> new JobRow(j.getId(), j.getTitle(), j.getCompany(),
                j.getFetchNote(), DisplayFormats.dateTime(j.getCreatedAt()))).toList());
        return "jobs/list";
    }

    public record JobRow(long id, String title, String company, String source, String created) {}

    @GetMapping("/jobs/{id}")
    public String editor(@PathVariable long id, Model model) {
        JobPosting posting = jobs.get(id);
        JobSpec spec = jobs.specOf(posting);
        model.addAttribute("active", "new-application");
        model.addAttribute("job", posting);
        model.addAttribute("spec", spec);
        model.addAttribute("specJson", jsonText.pretty(json.valueToTree(spec)));
        model.addAttribute("created", DisplayFormats.dateTime(posting.getCreatedAt()));
        health.keywords(spec).ifPresent(r -> model.addAttribute("coverage", r.keywords()));
        var auth = master.currentResume().map(m -> m.basics().workAuthorization()).orElse(null);
        model.addAttribute("defaultShowWorkAuth", auth != null && auth.showOnResumeDefault());
        model.addAttribute("hasMaster", master.current().isPresent());
        return "jobs/edit";
    }

    /** Saves the plain fields; skills are edited separately (htmx chips). */
    @PostMapping("/jobs/{id}")
    public String save(@PathVariable long id, @RequestParam(defaultValue = "") String title,
                       @RequestParam(defaultValue = "") String company, @RequestParam(defaultValue = "") String location,
                       @RequestParam(defaultValue = "") String seniority, @RequestParam(defaultValue = "") String employmentType,
                       @RequestParam(defaultValue = "") String salaryMin, @RequestParam(defaultValue = "") String salaryMax,
                       @RequestParam(defaultValue = "") String salaryCurrency,
                       @RequestParam(defaultValue = "") String responsibilities, @RequestParam(defaultValue = "") String qualifications,
                       @RequestParam(defaultValue = "") String companyFacts, @RequestParam(defaultValue = "") String keywordsRaw,
                       @RequestParam(defaultValue = "") String applyInstructions,
                       @RequestParam(defaultValue = "false") boolean coverLetterRequested, RedirectAttributes redirect) {
        JobSpec old = jobs.specOf(jobs.get(id));
        SalaryRange salary = number(salaryMin) == null && number(salaryMax) == null ? null
                : new SalaryRange(number(salaryMin), number(salaryMax), blank(salaryCurrency));
        jobs.update(id, new JobSpec(blank(title), blank(company), blank(location), blank(seniority), blank(employmentType),
                salary, old.requiredSkills(), old.preferredSkills(), lines(responsibilities), lines(qualifications),
                commaList(keywordsRaw), lines(companyFacts), blank(applyInstructions), coverLetterRequested, old.warnings()));
        redirect.addFlashAttribute("flash", "Saved.");
        return "redirect:/jobs/" + id;
    }

    /** Raw JSON fallback for anything the form does not cover. */
    @PostMapping("/jobs/{id}/json")
    public String saveJson(@PathVariable long id, @RequestParam(defaultValue = "") String specJson, RedirectAttributes redirect) {
        try {
            jobs.update(id, json.treeToValue(jsonText.parseStrict(specJson), JobSpec.class));
            redirect.addFlashAttribute("flash", "Saved.");
        } catch (JsonSyntaxException e) {
            redirect.addFlashAttribute("flashError", "Not saved: " + e.describe());
        } catch (JacksonException e) {
            redirect.addFlashAttribute("flashError", "Not saved: that JSON does not fit the job spec format.");
        }
        return "redirect:/jobs/" + id;
    }

    // ---------------------------------------------------------------- skill chips (htmx)

    @PostMapping("/jobs/{id}/skills/move")
    public String move(@PathVariable long id, @RequestParam String term, @RequestParam String to, Model model) {
        JobSpec s = jobs.specOf(jobs.get(id));
        List<JdSkill> required = new ArrayList<>(s.requiredSkills());
        List<JdSkill> preferred = new ArrayList<>(s.preferredSkills());
        List<JdSkill> from = to.equals("preferred") ? required : preferred;
        List<JdSkill> into = to.equals("preferred") ? preferred : required;
        from.stream().filter(k -> k.term().equals(term)).findFirst().ifPresent(k -> {
            from.remove(k);
            into.add(k);
        });
        return skills(id, withSkills(s, required, preferred), model);
    }

    @PostMapping("/jobs/{id}/skills/remove")
    public String remove(@PathVariable long id, @RequestParam String term, Model model) {
        JobSpec s = jobs.specOf(jobs.get(id));
        return skills(id, withSkills(s, s.requiredSkills().stream().filter(k -> !k.term().equals(term)).toList(),
                s.preferredSkills().stream().filter(k -> !k.term().equals(term)).toList()), model);
    }

    @PostMapping("/jobs/{id}/skills/add")
    public String add(@PathVariable long id, @RequestParam(defaultValue = "") String term,
                      @RequestParam(defaultValue = "required") String list, @RequestParam(defaultValue = "") String aliases,
                      Model model) {
        JobSpec s = jobs.specOf(jobs.get(id));
        List<JdSkill> required = new ArrayList<>(s.requiredSkills());
        List<JdSkill> preferred = new ArrayList<>(s.preferredSkills());
        String t = term.strip();
        boolean exists = s.requiredSkills().stream().anyMatch(k -> k.term().equalsIgnoreCase(t))
                || s.preferredSkills().stream().anyMatch(k -> k.term().equalsIgnoreCase(t));
        if (!t.isEmpty() && !exists) {
            (list.equals("preferred") ? preferred : required).add(new JdSkill(t, commaList(aliases)));
        }
        return skills(id, withSkills(s, required, preferred), model);
    }

    private String skills(long id, JobSpec updated, Model model) {
        jobs.update(id, updated);
        model.addAttribute("job", jobs.get(id));
        model.addAttribute("spec", updated);
        return "jobs/edit :: skills";
    }

    private static JobSpec withSkills(JobSpec s, List<JdSkill> required, List<JdSkill> preferred) {
        return new JobSpec(s.title(), s.company(), s.location(), s.seniority(), s.employmentType(), s.salaryRange(),
                required, preferred, s.responsibilities(), s.qualifications(), s.keywordsRaw(), s.companyFacts(),
                s.applyInstructions(), s.coverLetterRequested(), s.warnings());
    }

    static List<String> lines(String text) {
        return Arrays.stream(text.split("\\R")).map(l -> l.strip().replaceFirst("^[•\\-*]\\s*", "")).filter(l -> !l.isEmpty()).toList();
    }

    static List<String> commaList(String text) {
        return Arrays.stream(text.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** "5,000" and "6.5k" both work. */
    static Double number(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.strip().replace(",", "").toLowerCase(Locale.ROOT);
        double factor = v.endsWith("k") ? 1000 : 1;
        try {
            return Double.parseDouble(v.replaceFirst("k$", "")) * factor;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

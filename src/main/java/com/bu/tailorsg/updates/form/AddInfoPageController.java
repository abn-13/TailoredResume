package com.bu.tailorsg.updates.form;

import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
import com.bu.tailorsg.updates.PendingUpdateService.InvalidProposalException;
import com.bu.tailorsg.updates.PendingUpdateService.Proposal;
import com.bu.tailorsg.updates.form.FormPatchBuilder.FieldProblem;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * "Add Info": one tab per entry type. Submitting a form never changes the master resume directly: it
 * creates a PendingUpdate and redirects to its review page, where the user sees the diff and applies it.
 *
 * <p>Standard Spring MVC form handling: {@code @Valid @ModelAttribute} binds and validates the fields,
 * {@link BindingResult} carries errors back to {@code th:errors} in the template. One small handler per
 * entry type keeps the binding target concrete; they all share {@link #submit}.
 */
@Controller
public class AddInfoPageController {

    private final MasterResumeService master;
    private final FormPatchBuilder builder;
    private final PendingUpdateService updates;
    private final JsonMapper jsonMapper;

    private final com.bu.tailorsg.llm.ClaudeClient claude;

    public AddInfoPageController(MasterResumeService master, FormPatchBuilder builder, PendingUpdateService updates,
                                 JsonMapper jsonMapper, com.bu.tailorsg.llm.ClaudeClient claude) {
        this.claude = claude;
        this.master = master;
        this.builder = builder;
        this.updates = updates;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/add-info")
    public String page(@RequestParam(defaultValue = "work") String type,
                       @RequestParam(required = false) String highlightId, Model model) {
        String safeType = EntryForm.TYPES.contains(type) || type.equals("free-text") || type.equals("metric") ? type : "work";
        EntryForm form = safeType.equals("free-text") ? null : EntryForm.empty(safeType);
        if (form instanceof EntryForm.Metric metric && highlightId != null) {
            metric.setHighlightId(highlightId);
            FormPatchBuilder.findHighlight(master.currentDocumentOrEmpty(), highlightId).ifPresent(h -> metric.setText(h.text()));
        }
        return render(safeType, form, model);
    }

    @PostMapping("/add-info/work")
    public String work(@Valid @ModelAttribute("form") EntryForm.Work form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/project")
    public String project(@Valid @ModelAttribute("form") EntryForm.Project form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/education")
    public String education(@Valid @ModelAttribute("form") EntryForm.Education form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/skill")
    public String skill(@Valid @ModelAttribute("form") EntryForm.Skill form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/certificate")
    public String certificate(@Valid @ModelAttribute("form") EntryForm.Certificate form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/award")
    public String award(@Valid @ModelAttribute("form") EntryForm.Award form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/highlight")
    public String highlight(@Valid @ModelAttribute("form") EntryForm.Highlight form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    @PostMapping("/add-info/metric")
    public String metric(@Valid @ModelAttribute("form") EntryForm.Metric form, BindingResult errors, Model model, RedirectAttributes redirect) {
        return submit(form, errors, model, redirect);
    }

    private String submit(EntryForm form, BindingResult errors, Model model, RedirectAttributes redirect) {
        String type = form.typeName();
        if (errors.hasErrors()) {
            return render(type, form, model);
        }
        JsonNode current = master.currentDocumentOrEmpty();
        if (current.isEmpty()) {
            return render(type, form, model); // the page explains that a master resume is needed first
        }
        try {
            FormPatchBuilder.Built built = builder.build(form, current);
            PendingUpdate update = updates.create(new Proposal(PendingUpdate.Source.FORM,
                    jsonMapper.writeValueAsString(form), built.patch(), built.summary(), List.of()), true);
            redirect.addFlashAttribute("flash", "Here is the change your form would make. Apply it to update your master resume.");
            return "redirect:/updates/" + update.getId();
        } catch (FieldProblem p) {
            errors.rejectValue(p.field(), "invalid", p.getMessage());
        } catch (InvalidProposalException e) {
            // Rules beyond the form's own fields, e.g. P6 privacy patterns in the highlight text
            model.addAttribute("problems", e.preview().errors());
            model.addAttribute("patchError", e.preview().patchError());
        }
        return render(type, form, model);
    }

    private String render(String type, EntryForm form, Model model) {
        JsonNode current = master.currentDocumentOrEmpty();
        model.addAttribute("active", "add-info");
        model.addAttribute("type", type);
        model.addAttribute("types", EntryForm.TYPES);
        model.addAttribute("hasMaster", !current.isEmpty());
        model.addAttribute("llmConfigured", claude.isConfigured());
        if (form != null) {
            model.addAttribute("form", form);
        }
        model.addAttribute("categories", SkillCategories.ALL);
        model.addAttribute("employmentTypes", List.of("Internship", "Full-time", "Contract", "Part-time"));
        model.addAttribute("parents", FormPatchBuilder.parents(current));
        model.addAttribute("skillNames", current.path("skills").values().stream()
                .map(s -> s.path("name").stringValue("")).filter(n -> !n.isBlank()).toList());
        model.addAttribute("highlightOptions", highlightOptions(current));
        return "add-info";
    }

    public record Option(String value, String label) {}

    /** Every highlight as "work-1.h2: Built REST endpoints for...", for the skill-evidence select. */
    private static List<Option> highlightOptions(JsonNode current) {
        List<Option> options = new ArrayList<>();
        for (String section : List.of("work", "projects", "education", "volunteer")) {
            current.path(section).values().forEach(item -> item.path("highlights").values().forEach(h -> {
                String text = h.path("text").stringValue("");
                options.add(new Option(h.path("id").stringValue(""),
                        h.path("id").stringValue("") + ": " + (text.length() > 70 ? text.substring(0, 69) + "…" : text)));
            }));
        }
        return options;
    }
}

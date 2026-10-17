package com.bu.tailorsg.health;

import com.bu.tailorsg.checks.Bullet;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.master.VersionNotFoundException;
import com.bu.tailorsg.updates.form.FormPatchBuilder;
import com.bu.tailorsg.master.MasterResumeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;

/** Master health page, its keyword check, the live lint preview, and GET /api/master/health. */
@Controller
public class MasterHealthController {

    private final MasterHealthService health;
    private final MasterResumeService master;

    public MasterHealthController(MasterHealthService health, MasterResumeService master) {
        this.health = health;
        this.master = master;
    }

    @GetMapping("/master/health")
    public String page(Model model) {
        model.addAttribute("active", "master");
        model.addAttribute("tab", "health");
        health.health().ifPresent(h -> {
            model.addAttribute("health", h);
            model.addAttribute("report", h.report());
        });
        return "master/health";
    }

    /** htmx: KW of the master against hand-entered terms (one per line or comma-separated). */
    @PostMapping("/master/health/keywords")
    public String keywords(@RequestParam(defaultValue = "") String title, @RequestParam(defaultValue = "") String required,
                           @RequestParam(defaultValue = "") String preferred, Model model) {
        JobSpec job = JobSpec.ofTerms(title.isBlank() ? null : title.strip(), terms(required), terms(preferred));
        health.keywords(job).ifPresent(r -> model.addAttribute("report", r));
        return "fragments/keyword-result :: result";
    }

    @GetMapping("/api/master/health")
    @ResponseBody
    public CheckReport api() {
        return health.health().orElseThrow(VersionNotFoundException::noMaster).report();
    }

    /** htmx: lint one draft highlight while the user types (Add Info, Highlight tab). */
    @PostMapping("/add-info/lint-preview")
    public String lintPreview(@RequestParam(defaultValue = "") String text, @RequestParam(defaultValue = "") String parentId,
                              Model model) {
        JsonNode doc = master.currentDocumentOrEmpty();
        Bullet.Kind kind = Bullet.Kind.OTHER;
        boolean current = false;
        var parent = FormPatchBuilder.findParent(doc, parentId);
        if (parent.isPresent()) {
            String pointer = parent.get().pointer();
            kind = pointer.startsWith("/work") || pointer.startsWith("/volunteer") ? Bullet.Kind.EXPERIENCE
                    : pointer.startsWith("/projects") ? Bullet.Kind.PROJECT : Bullet.Kind.OTHER;
            current = !doc.at(pointer).has("endDate") || doc.at(pointer + "/endDate").isNull();
        }
        model.addAttribute("findings", health.lintOne(text, kind, current));
        model.addAttribute("empty", text.isBlank());
        return "fragments/lint-preview :: preview";
    }

    static List<String> terms(String text) {
        return Arrays.stream(text.split("[,\\n]")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
}

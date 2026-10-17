package com.bu.tailorsg.master;

import com.bu.tailorsg.documents.UnreadableDocumentException;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.master.MasterResumeService.SaveResult;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.resumeimport.ResumeImportService;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.web.ChangeRow;
import com.bu.tailorsg.web.DisplayFormats;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

import java.util.Optional;

/**
 * Master Resume pages: raw JSON editor, history, single version, diff.
 *
 * <p>Validate and Save are htmx requests that return a result fragment (no page reload). Load sample
 * and Restore are ordinary form posts that redirect back with a one-off "flash" message
 * (Post/Redirect/Get, so a browser refresh does not repeat them).
 */
@Controller
@RequestMapping("/master")
public class MasterPageController {

    private static final String RESULT_FRAGMENT = "fragments/master-result";

    private final MasterResumeService service;
    private final ResumeImportService importer;
    private final ClaudeClient claude;

    public MasterPageController(MasterResumeService service, ResumeImportService importer, ClaudeClient claude) {
        this.service = service;
        this.importer = importer;
        this.claude = claude;
    }

    @GetMapping
    public String editor(Model model) {
        Optional<StoredVersion> current = service.current();
        model.addAttribute("active", "master");
        model.addAttribute("tab", "editor");
        model.addAttribute("current", current.map(MasterPageController::versionLine).orElse(null));
        model.addAttribute("json", current.map(v -> service.pretty(v.json())).orElse(""));
        return "master/editor";
    }

    /** Readable view of the current master resume (spec 3: Master Resume "view"). */
    @GetMapping("/view")
    public String view(Model model) {
        model.addAttribute("active", "master");
        model.addAttribute("tab", "view");
        service.current().ifPresent(v -> {
            model.addAttribute("current", versionLine(v));
            model.addAttribute("resume", service.asResume(v.json()).orElse(null));
        });
        return "master/view";
    }

    @GetMapping("/import")
    public String importPage(Model model) {
        model.addAttribute("active", "master");
        model.addAttribute("tab", "import");
        model.addAttribute("hasMaster", service.current().isPresent());
        model.addAttribute("llmConfigured", claude.isConfigured());
        return "master/import";
    }

    /** Upload a PDF/DOCX resume. Creates a PendingUpdate and opens its review page; nothing is applied yet. */
    @PostMapping("/import")
    public String importFile(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        try {
            PendingUpdate update = importer.importResume(file.getOriginalFilename(), file.getBytes());
            redirect.addFlashAttribute("flash", "Imported. Check every warning and the result below before applying.");
            return "redirect:/updates/" + update.getId();
        } catch (UnreadableDocumentException | LlmException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (IOException e) {
            redirect.addFlashAttribute("flashError", "Could not read the upload: " + e.getMessage());
        }
        return "redirect:/master/import";
    }

    @PostMapping("/validate")
    public String validate(@RequestParam(defaultValue = "") String json, Model model) {
        try {
            model.addAttribute("result", service.validate(json));
        } catch (MasterValidationException e) {
            model.addAttribute("errors", e.errors());
        }
        model.addAttribute("mode", "validate");
        return RESULT_FRAGMENT;
    }

    @PostMapping("/save")
    public String save(@RequestParam(defaultValue = "") String json, @RequestParam(required = false) String note,
                       Model model) {
        try {
            SaveResult result = service.save(json, note == null || note.isBlank() ? "Edited in JSON editor" : note);
            model.addAttribute("result", result);
            // Out-of-band updates: the editor shows the stored JSON (with any new IDs) and the new version line
            model.addAttribute("savedJson", service.pretty(result.document()));
            service.current().ifPresent(v -> model.addAttribute("current", versionLine(v)));
        } catch (MasterValidationException e) {
            model.addAttribute("errors", e.errors());
        }
        model.addAttribute("mode", "save");
        return RESULT_FRAGMENT;
    }

    @PostMapping("/sample")
    public String loadSample(RedirectAttributes redirect) {
        try {
            SaveResult result = service.loadSample();
            redirect.addFlashAttribute("flash", "Loaded the sample master resume (fake data) as version " + result.versionNo() + ".");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/master";
    }

    @GetMapping("/history")
    public String history(Model model) {
        model.addAttribute("active", "master");
        model.addAttribute("tab", "history");
        model.addAttribute("versions", service.history().stream()
                .map(v -> new VersionRow(v.getVersionNo(), DisplayFormats.dateTime(v.getCreatedAt()), v.getChangeNote()))
                .toList());
        return "master/history";
    }

    @GetMapping("/versions/{versionNo}")
    public String version(@PathVariable int versionNo, Model model) {
        StoredVersion version = service.version(versionNo).orElseThrow(() -> VersionNotFoundException.version(versionNo));
        int latest = service.current().map(StoredVersion::versionNo).orElse(versionNo);
        model.addAttribute("active", "master");
        model.addAttribute("tab", "history");
        model.addAttribute("version", versionLine(version));
        model.addAttribute("isCurrent", versionNo == latest);
        model.addAttribute("json", service.pretty(version.json()));
        return "master/version";
    }

    @PostMapping("/versions/{versionNo}/restore")
    public String restore(@PathVariable int versionNo, RedirectAttributes redirect) {
        try {
            SaveResult result = service.restore(versionNo);
            redirect.addFlashAttribute("flash", result.unchanged()
                    ? "Version " + versionNo + " is identical to the current version; nothing changed."
                    : "Restored version " + versionNo + " as new version " + result.versionNo() + ".");
        } catch (MasterValidationException e) {
            redirect.addFlashAttribute("flashError", "Version " + versionNo
                    + " no longer passes validation, so it was not restored: " + e.getMessage());
        }
        return "redirect:/master";
    }

    /** Diff page. Defaults to "previous version vs current" when from/to are not given. */
    @GetMapping("/diff")
    public String diff(@RequestParam(required = false) Integer from, @RequestParam(required = false) Integer to,
                       Model model) {
        int latest = service.current().orElseThrow(VersionNotFoundException::noMaster).versionNo();
        int toVersion = to == null ? latest : to;
        int fromVersion = from == null ? Math.max(1, toVersion - 1) : from;
        model.addAttribute("active", "master");
        model.addAttribute("tab", "history");
        model.addAttribute("from", fromVersion);
        model.addAttribute("to", toVersion);
        model.addAttribute("versionNumbers", service.history().stream().map(v -> v.getVersionNo()).toList());
        model.addAttribute("changes", ChangeRow.of(service.diff(fromVersion, toVersion)));
        return "master/diff";
    }

    // ---------------------------------------------------------------- view models

    public record VersionLine(int versionNo, String savedAt, String note) {}

    public record VersionRow(int versionNo, String savedAt, String note) {}

    private static VersionLine versionLine(StoredVersion v) {
        return new VersionLine(v.versionNo(), DisplayFormats.dateTime(v.createdAt()), v.changeNote());
    }
}

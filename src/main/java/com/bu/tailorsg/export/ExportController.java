package com.bu.tailorsg.export;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.applications.ApplicationTabContributor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Export tab (spec 7: export disabled while ERRORs exist; the override needs a typed reason), downloads,
 * and the JSON API: POST /api/applications/{id}/export?type=pdf|docx, GET /api/applications/{id}/exports.
 */
@Controller
public class ExportController implements ApplicationTabContributor {

    static final MediaType DOCX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ExportService exports;
    private final ApplicationService applications;

    public ExportController(ExportService exports, ApplicationService applications) {
        this.exports = exports;
        this.applications = applications;
    }

    @Override
    public void contribute(Application a, String activeTab, Model model) {
        if ("export".equals(activeTab)) {
            List<ExportFile> all = exports.filesOf(a.getId());
            model.addAttribute("exportCheck", exports.lastCheckOf(a).orElse(null));
            model.addAttribute("resumeFiles", all.stream().filter(f -> f.getKind() == ExportFile.Kind.RESUME).toList());
            model.addAttribute("letterFiles", all.stream().filter(f -> f.getKind() == ExportFile.Kind.COVER_LETTER).toList());
        }
    }

    @PostMapping("/applications/{id}/export")
    public String export(@PathVariable long id, @RequestParam(defaultValue = "PDF") String type,
                         @RequestParam(defaultValue = "false") boolean checkOnly,
                         @RequestParam(defaultValue = "") String overrideReason, RedirectAttributes redirect) {
        try {
            ExportedFile.Type t = type(type);
            if (checkOnly) {
                ExportService.ExportCheck check = exports.checkResume(id, t);
                redirect.addFlashAttribute(check.report().hasErrors() ? "flashError" : "flash", "Checked the " + t + ": "
                        + (check.report().hasErrors() ? check.report().errors().size() + " ERROR finding(s) would block export."
                        : "no blocking errors (" + check.pageCount() + " page" + (check.pageCount() == 1 ? "" : "s") + ")."));
            } else {
                ExportFile f = exports.exportResume(id, t, overrideReason);
                redirect.addFlashAttribute("flash", "Saved " + f.getFileName() + (f.getOverrideReason() != null
                        ? " (exported despite errors; your reason is stored with the file)." : "."));
            }
        } catch (ExportBlockedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/applications/" + id + "?tab=export";
    }

    @GetMapping("/applications/{id}/exports/{fileId}")
    public ResponseEntity<FileSystemResource> download(@PathVariable long id, @PathVariable long fileId) {
        ExportFile f = exports.file(id, fileId);
        Path path = exports.pathOf(f);
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(f.getType() == ExportedFile.Type.PDF ? MediaType.APPLICATION_PDF : DOCX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.getFileName()).build().toString())
                .body(new FileSystemResource(path));
    }

    // ---------------------------------------------------------------- JSON API

    public record ExportRequest(String overrideReason) {}

    @PostMapping("/api/applications/{id}/export")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiExport(@PathVariable long id, @RequestParam(defaultValue = "pdf") String type,
                                                         @RequestBody(required = false) ExportRequest body) {
        applications.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            ExportFile f = exports.exportResume(id, type(type), body == null ? null : body.overrideReason());
            out.put("file", summary(id, f));
            out.put("check", exports.lastCheckOf(applications.get(id)).orElse(null));
            return ResponseEntity.created(URI.create("/applications/" + id + "/exports/" + f.getId())).body(out);
        } catch (ExportBlockedException e) {
            out.put("detail", e.getMessage());
            out.put("check", e.check());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        } catch (IllegalStateException | IllegalArgumentException e) {
            out.put("detail", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }
    }

    @GetMapping("/api/applications/{id}/exports")
    @ResponseBody
    public List<Map<String, Object>> apiList(@PathVariable long id) {
        applications.get(id);
        return exports.filesOf(id).stream().map(f -> summary(id, f)).toList();
    }

    private static Map<String, Object> summary(long id, ExportFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("kind", f.getKind());
        m.put("type", f.getType());
        m.put("fileName", f.getFileName());
        m.put("sha256", f.getSha256());
        m.put("pageCount", f.getPageCount());
        m.put("overrideReason", f.getOverrideReason());
        m.put("createdAt", f.getCreatedAt());
        m.put("download", "/applications/" + id + "/exports/" + f.getId());
        return m;
    }

    static ExportedFile.Type type(String type) {
        try {
            return ExportedFile.Type.valueOf(type.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be pdf or docx.");
        }
    }
}

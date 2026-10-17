package com.bu.tailorsg.updates;

import com.bu.tailorsg.json.JsonPatchException;
import com.bu.tailorsg.json.JsonText;
import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.SaveResult;
import com.bu.tailorsg.master.MasterValidationException;
import com.bu.tailorsg.updates.PendingUpdate.Status;
import com.bu.tailorsg.updates.PendingUpdateService.Preview;
import com.bu.tailorsg.web.ChangeRow;
import com.bu.tailorsg.web.DisplayFormats;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Review pages for pending updates: list, review (diff, warnings, apply / reject), and the patch editor
 * (preview via htmx, save via a normal form post).
 */
@Controller
public class UpdatePageController {

    private final PendingUpdateService updates;
    private final MasterResumeService master;
    private final JsonText jsonText;

    public UpdatePageController(PendingUpdateService updates, MasterResumeService master, JsonText jsonText) {
        this.updates = updates;
        this.master = master;
        this.jsonText = jsonText;
    }

    public record UpdateRow(long id, String created, String source, String summary, String status, Integer appliedVersionNo) {}

    @GetMapping("/updates")
    public String list(@RequestParam(required = false) Status status, Model model) {
        model.addAttribute("active", "master");
        model.addAttribute("tab", "updates");
        model.addAttribute("filter", status);
        model.addAttribute("rows", updates.list(status).stream()
                .map(u -> new UpdateRow(u.getId(), DisplayFormats.dateTime(u.getCreatedAt()), label(u.getSourceType()),
                        u.getSummary(), u.getStatus().name(), u.getAppliedVersionNo()))
                .toList());
        return "updates/list";
    }

    @GetMapping("/updates/{id}")
    public String review(@PathVariable long id, Model model) {
        return reviewPage(updates.get(id), model);
    }

    private String reviewPage(PendingUpdate update, Model model) {
        model.addAttribute("active", "master");
        model.addAttribute("tab", "updates");
        model.addAttribute("update", update);
        model.addAttribute("source", label(update.getSourceType()));
        model.addAttribute("created", DisplayFormats.dateTime(update.getCreatedAt()));
        model.addAttribute("resolved", DisplayFormats.dateTime(update.getResolvedAt()));
        model.addAttribute("notes", updates.notesOf(update));
        model.addAttribute("patchText", jsonText.pretty(updates.patchOf(update)));
        if (update.getStatus() == Status.PROPOSED) {
            addPreview(model, updates.preview(update), update.getSourceType() == PendingUpdate.Source.IMPORT);
        }
        return "updates/review";
    }

    /** htmx: preview an edited patch without saving it. */
    @PostMapping("/updates/{id}/preview")
    public String previewEdit(@PathVariable long id, @RequestParam(defaultValue = "") String patch, Model model) {
        updates.get(id); // 404 if it does not exist
        Preview preview;
        try {
            preview = updates.preview(updates.parsePatch(patch));
        } catch (JsonSyntaxException e) {
            preview = failed(e.describe());
        } catch (JsonPatchException e) {
            preview = failed(e.getMessage());
        }
        addPreview(model, preview, false);
        model.addAttribute("edited", true);
        return "fragments/update-preview :: preview";
    }

    /**
     * Saves an edited patch. When the text can't be saved, the page is shown again with the user's text still
     * in the editor and the error just above it. (Redirecting would reload the stored patch: the edits would
     * be lost, and the error's line number would point into text the user can no longer see.)
     */
    @PostMapping("/updates/{id}/patch")
    public String savePatch(@PathVariable long id, @RequestParam(defaultValue = "") String patch,
                            Model model, RedirectAttributes redirect) {
        String error;
        try {
            updates.replacePatch(id, patch);
            redirect.addFlashAttribute("flash", "Patch saved. Review the changes below, then apply.");
            return "redirect:/updates/" + id;
        } catch (JsonSyntaxException e) {
            error = e.describe();
        } catch (JsonPatchException e) {
            error = e.getMessage();
        } catch (IllegalStateException e) { // e.g. no longer PROPOSED: nothing to keep editing
            redirect.addFlashAttribute("flashError", "Patch not saved: " + e.getMessage());
            return "redirect:/updates/" + id;
        }
        String view = reviewPage(updates.get(id), model);
        model.addAttribute("patchText", patch);
        model.addAttribute("patchSaveError", error);
        return view;
    }

    @PostMapping("/updates/{id}/apply")
    public String apply(@PathVariable long id, RedirectAttributes redirect) {
        try {
            SaveResult result = updates.apply(id);
            redirect.addFlashAttribute("flash", "Applied. Your master resume is now version " + result.versionNo()
                    + (result.assignedIds().isEmpty() ? "." : "; new IDs: " + String.join(", ", result.assignedIds()) + "."));
        } catch (JsonPatchException e) {
            redirect.addFlashAttribute("flashError", "Not applied: this update no longer fits the current master resume. "
                    + e.getMessage());
        } catch (MasterValidationException e) {
            redirect.addFlashAttribute("flashError", "Not applied: " + e.getMessage());
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/updates/" + id;
    }

    @PostMapping("/updates/{id}/reject")
    public String reject(@PathVariable long id, RedirectAttributes redirect) {
        try {
            updates.reject(id);
            redirect.addFlashAttribute("flash", "Update #" + id + " rejected. Your master resume was not changed.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/updates/" + id;
    }

    private void addPreview(Model model, Preview preview, boolean showResultOpen) {
        model.addAttribute("preview", preview);
        model.addAttribute("changes", ChangeRow.of(preview.changes()));
        model.addAttribute("resultResume", master.asResume(preview.result()).orElse(null));
        model.addAttribute("resultOpen", showResultOpen);
    }

    private static Preview failed(String message) {
        return new Preview(null, message, List.of(), List.of(), List.of(), List.of(), null);
    }

    static String label(PendingUpdate.Source source) {
        return switch (source) {
            case FORM -> "Form";
            case IMPORT -> "File import";
            case FREE_TEXT -> "Free text";
        };
    }
}

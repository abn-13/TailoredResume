package com.bu.tailorsg.updates;

import com.bu.tailorsg.json.JsonPatchException;
import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.SaveResult;
import com.bu.tailorsg.master.MasterValidationException;
import com.bu.tailorsg.updates.PendingUpdate.Status;
import com.bu.tailorsg.updates.PendingUpdateService.InvalidProposalException;
import com.bu.tailorsg.updates.PendingUpdateService.Proposal;
import com.bu.tailorsg.updates.UpdateViews.UpdateSummary;
import com.bu.tailorsg.updates.UpdateViews.UpdateView;
import com.bu.tailorsg.updates.form.EntryForm;
import com.bu.tailorsg.updates.form.FormPatchBuilder;
import com.bu.tailorsg.updates.form.FormPatchBuilder.FieldProblem;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;

/**
 * JSON API for pending updates (spec 3, "Updates"). Nothing here changes the master resume except
 * {@code POST /api/updates/{id}/apply}.
 */
@RestController
@RequestMapping("/api/updates")
public class UpdatesApiController {

    private final PendingUpdateService updates;
    private final FormPatchBuilder builder;
    private final MasterResumeService master;
    private final JsonMapper jsonMapper;

    public UpdatesApiController(PendingUpdateService updates, FormPatchBuilder builder, MasterResumeService master,
                                JsonMapper jsonMapper) {
        this.updates = updates;
        this.builder = builder;
        this.master = master;
        this.jsonMapper = jsonMapper;
    }

    /**
     * POST /api/updates/form: a typed entry ({"type": "work", ...}) becomes a proposed patch, built by the
     * server with no LLM. Rejected with 422 if the result would not be a valid master resume.
     */
    @PostMapping(path = "/form", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UpdateView> fromForm(@Valid @RequestBody EntryForm form) {
        JsonNode current = master.currentDocumentOrEmpty();
        if (current.isEmpty()) {
            throw new IllegalStateException("Create your master resume first (editor or import), then add entries to it.");
        }
        FormPatchBuilder.Built built = builder.build(form, current);
        PendingUpdate update = updates.create(new Proposal(PendingUpdate.Source.FORM,
                jsonMapper.writeValueAsString(form), built.patch(), built.summary(), List.of()), true);
        return ResponseEntity.created(URI.create("/api/updates/" + update.getId()))
                .body(UpdateViews.view(update, updates));
    }

    @GetMapping
    public List<UpdateSummary> list(@RequestParam(required = false) Status status) {
        return updates.list(status).stream().map(UpdateSummary::of).toList();
    }

    @GetMapping("/{id}")
    public UpdateView get(@PathVariable long id) {
        return UpdateViews.view(updates.get(id), updates);
    }

    /**
     * PATCH /api/updates/{id}: replace the proposed patch with an edited one. The body is the new
     * RFC 6902 patch (a JSON array). The response includes a fresh preview.
     */
    @PatchMapping(path = "/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/json-patch+json"})
    public UpdateView editPatch(@PathVariable long id, @RequestBody String patch) {
        updates.replacePatch(id, patch);
        return UpdateViews.view(updates.get(id), updates);
    }

    @PostMapping("/{id}/apply")
    public ApplyResponse apply(@PathVariable long id) {
        try {
            SaveResult result = updates.apply(id);
            return new ApplyResponse(id, result.versionNo(), result.assignedIds(), result.notes());
        } catch (JsonPatchException e) {
            throw new StalePatchException(e.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public UpdateView reject(@PathVariable long id) {
        updates.reject(id);
        return UpdateViews.view(updates.get(id), updates);
    }

    public record ApplyResponse(long updateId, int masterVersionNo, List<String> assignedIds, List<String> notes) {}

    public record FieldMessage(String field, String message) {}

    /** The patch was fine when proposed but no longer applies (the master changed). */
    static class StalePatchException extends RuntimeException {
        StalePatchException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- errors (RFC 9457 problem details)

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidForm(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Form rejected",
                e.getFieldErrorCount() + " field(s) need attention.");
        problem.setProperty("fieldErrors", e.getFieldErrors().stream()
                .map(f -> new FieldMessage(f.getField(), f.getDefaultMessage())).toList());
        return problem;
    }

    @ExceptionHandler(FieldProblem.class)
    ProblemDetail fieldProblem(FieldProblem e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Form rejected", e.getMessage());
        problem.setProperty("fieldErrors", List.of(new FieldMessage(e.field(), e.getMessage())));
        return problem;
    }

    @ExceptionHandler(InvalidProposalException.class)
    ProblemDetail invalidProposal(InvalidProposalException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Update rejected", e.getMessage());
        problem.setProperty("errors", e.preview().errors());
        return problem;
    }

    @ExceptionHandler(MasterValidationException.class)
    ProblemDetail invalidResult(MasterValidationException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Master resume rejected",
                e.errors().size() + " problem(s); nothing was applied.");
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler({JsonSyntaxException.class, JsonPatchException.class})
    ProblemDetail badPatch(RuntimeException e) {
        String detail = e instanceof JsonSyntaxException s ? s.describe() : e.getMessage();
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid patch", detail);
    }

    @ExceptionHandler(StalePatchException.class)
    ProblemDetail stale(StalePatchException e) {
        return problem(HttpStatus.CONFLICT, "Update no longer applies", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Not allowed now", e.getMessage());
    }

    @ExceptionHandler(UpdateNotFoundException.class)
    ProblemDetail notFound(UpdateNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

package com.bu.tailorsg.master;

import com.bu.tailorsg.master.MasterDiffer.Change;
import com.bu.tailorsg.master.MasterResumeService.SaveResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * JSON API for the master resume (spec section 3). Errors use RFC 9457 "problem details"
 * ({@code application/problem+json}), which Spring supports natively through {@link ProblemDetail}.
 */
@RestController
@RequestMapping("/api/master")
public class MasterApiController {

    private final MasterResumeService service;

    public MasterApiController(MasterResumeService service) {
        this.service = service;
    }

    /** GET /api/master: the current version's document. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode current() {
        return service.current().orElseThrow(VersionNotFoundException::noMaster).json();
    }

    /**
     * PUT /api/master: replace the master resume, creating a new version. The body is taken as raw
     * text (not bound to an object) so duplicate keys and syntax errors can be reported with line numbers.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaveResponse replace(@RequestBody String body, @RequestParam(required = false) String note) {
        return SaveResponse.of(service.save(body, note == null ? "Saved via API" : note));
    }

    /** POST /api/master/validate: run every check, save nothing. */
    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaveResponse validate(@RequestBody String body) {
        return SaveResponse.of(service.validate(body));
    }

    /** POST /api/master/sample: load the bundled fake sample (empty database only). */
    @PostMapping("/sample")
    public SaveResponse loadSample() {
        return SaveResponse.of(service.loadSample());
    }

    @GetMapping("/versions")
    public List<VersionItem> versions() {
        return service.history().stream()
                .map(v -> new VersionItem(v.getVersionNo(), v.getCreatedAt(), v.getChangeNote()))
                .toList();
    }

    @GetMapping(path = "/versions/{versionNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode version(@PathVariable int versionNo) {
        return service.version(versionNo).orElseThrow(() -> VersionNotFoundException.version(versionNo)).json();
    }

    @PostMapping("/versions/{versionNo}/restore")
    public SaveResponse restore(@PathVariable int versionNo) {
        return SaveResponse.of(service.restore(versionNo));
    }

    /** GET /api/master/diff?from=1&amp;to=2: ID-aware structural changes between two versions. */
    @GetMapping("/diff")
    public DiffResponse diff(@RequestParam int from, @RequestParam int to) {
        return new DiffResponse(from, to, service.diff(from, to));
    }

    // ---------------------------------------------------------------- response shapes

    public record SaveResponse(int versionNo, boolean unchanged, List<String> assignedIds, List<String> notes) {
        static SaveResponse of(SaveResult result) {
            return new SaveResponse(result.versionNo(), result.unchanged(), result.assignedIds(), result.notes());
        }
    }

    public record VersionItem(int versionNo, Instant createdAt, String changeNote) {}

    public record DiffResponse(int from, int to, List<Change> changes) {}

    // ---------------------------------------------------------------- errors

    /** 422: the JSON was understood but breaks the rules. Every error is listed with its path. */
    @ExceptionHandler(MasterValidationException.class)
    ProblemDetail invalid(MasterValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                e.errors().size() + " problem(s) found; nothing was saved.");
        problem.setTitle("Master resume rejected");
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler(VersionNotFoundException.class)
    ProblemDetail notFound(VersionNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

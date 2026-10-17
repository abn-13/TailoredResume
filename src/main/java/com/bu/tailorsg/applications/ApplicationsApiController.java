package com.bu.tailorsg.applications;

import com.bu.tailorsg.applications.ApplicationService.ApplicationRow;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.tailoring.TailoredResume;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** JSON API for applications (spec 3, "Applications"). Errors are RFC 9457 problem details. */
@RestController
@RequestMapping("/api/applications")
public class ApplicationsApiController {

    private final ApplicationService applications;
    private final ClaudeClient claude;

    public ApplicationsApiController(ApplicationService applications, ClaudeClient claude) {
        this.applications = applications;
        this.claude = claude;
    }

    public record CreateRequest(Long jobId, ApplicationOptions options) {}

    public record ApplicationDetail(long id, long jobId, String status, Integer masterVersionNo, ApplicationOptions options,
                                    TailoredResume tailoredResume, CheckReport checkReport, Map<String, Integer> promptVersions,
                                    String overrideReason, Instant createdAt, Instant updatedAt) {}

    public record TailorResponse(ApplicationDetail application, List<String> notes, BigDecimal costUsd, int attempts) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApplicationDetail> create(@RequestBody CreateRequest request) {
        if (request.jobId() == null) {
            throw new IllegalArgumentException("jobId is required.");
        }
        Application a = applications.create(request.jobId(),
                request.options() == null ? ApplicationOptions.defaults() : request.options());
        return ResponseEntity.created(URI.create("/api/applications/" + a.getId())).body(detail(a));
    }

    @GetMapping
    public List<ApplicationRow> list() {
        return applications.list();
    }

    @GetMapping("/{id}")
    public ApplicationDetail get(@PathVariable long id) {
        return detail(applications.get(id));
    }

    @PostMapping("/{id}/tailor")
    public TailorResponse tailor(@PathVariable long id) {
        requireKey();
        var outcome = applications.tailor(id);
        return new TailorResponse(detail(applications.get(id)), outcome.notes(), outcome.costUsd(), outcome.attempts());
    }

    @PostMapping("/{id}/check")
    public CheckReport check(@PathVariable long id) {
        return applications.recheck(id);
    }

    @PutMapping(path = "/{id}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CheckReport replaceResume(@PathVariable long id, @RequestBody JsonNode tailored) {
        return applications.replaceResume(id, tailored);
    }

    ApplicationDetail detail(Application a) {
        return new ApplicationDetail(a.getId(), a.getJobPostingId(), a.getStatus().name(), a.getMasterVersionNo(),
                applications.optionsOf(a), applications.tailoredOf(a).orElse(null), applications.reportOf(a).orElse(null),
                applications.promptVersionsOf(a), a.getOverrideReason(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private void requireKey() {
        if (!claude.isConfigured()) {
            throw new NotConfigured();
        }
    }

    static class NotConfigured extends RuntimeException {}

    @ExceptionHandler(NotConfigured.class)
    ProblemDetail notConfigured() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "ANTHROPIC_API_KEY is not set (or start the app in demo mode). See the README.");
    }

    @ExceptionHandler(ApplicationNotFoundException.class)
    ProblemDetail notFound(ApplicationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TailoredResumeInvalidException.class)
    ProblemDetail invalid(TailoredResumeInvalidException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        p.setTitle("Tailored resume rejected");
        p.setProperty("errors", e.errors());
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badInput(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    ProblemDetail llm(LlmException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}

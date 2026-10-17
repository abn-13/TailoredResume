package com.bu.tailorsg.jobs;

import com.bu.tailorsg.jobs.source.BlockedSiteException;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.llm.LlmTransport.ImageBlock;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JSON API for job postings (spec 3, "Job descriptions"). */
@RestController
@RequestMapping("/api/jobs")
public class JobsApiController {

    private final JobService jobs;
    private final ClaudeClient claude;

    public JobsApiController(JobService jobs, ClaudeClient claude) {
        this.jobs = jobs;
        this.claude = claude;
    }

    public record UrlRequest(String url) {}

    public record TextRequest(String text) {}

    public record JobView(long id, String sourceType, String sourceUrl, String fetchNote, String title, String company,
                          Instant createdAt, JobSpec spec, String rawText) {}

    public record JobSummary(long id, String sourceType, String title, String company, Instant createdAt) {}

    @PostMapping(path = "/from-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobView> fromUrl(@RequestBody UrlRequest request) {
        requireKey();
        return created(jobs.fromUrl(request.url() == null ? "" : request.url()));
    }

    @PostMapping(path = "/from-text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobView> fromText(@RequestBody TextRequest request) {
        requireKey();
        return created(jobs.fromText(request.text()));
    }

    /** Multipart field "files": 1-5 PNG/JPEG/WebP screenshots, in page order. */
    @PostMapping(path = "/from-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobView> fromImages(@RequestParam("files") List<MultipartFile> files) throws IOException {
        requireKey();
        return created(jobs.fromImages(images(files)));
    }

    @GetMapping
    public List<JobSummary> list() {
        return jobs.list().stream().map(j -> new JobSummary(j.getId(), j.getSourceType().name(), j.getTitle(),
                j.getCompany(), j.getCreatedAt())).toList();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable long id) {
        return view(jobs.get(id));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JobView update(@PathVariable long id, @RequestBody JobSpec spec) {
        return view(jobs.update(id, spec));
    }

    static List<ImageBlock> images(List<MultipartFile> files) throws IOException {
        List<ImageBlock> images = new ArrayList<>();
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                images.add(ScreenshotInput.prepare(file.getBytes(), file.getOriginalFilename()));
            }
        }
        return images;
    }

    private ResponseEntity<JobView> created(JobPosting posting) {
        return ResponseEntity.created(URI.create("/api/jobs/" + posting.getId())).body(view(posting));
    }

    private JobView view(JobPosting p) {
        return new JobView(p.getId(), p.getSourceType().name(), p.getSourceUrl(), p.getFetchNote(), p.getTitle(),
                p.getCompany(), p.getCreatedAt(), jobs.specOf(p), p.getRawText());
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
                "Reading job descriptions uses the extractor model, and ANTHROPIC_API_KEY is not set.");
    }

    @ExceptionHandler(BlockedSiteException.class)
    ProblemDetail blocked(BlockedSiteException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, BlockedSiteException.ADVICE);
        p.setTitle("Site blocks automated fetching");
        p.setProperty("reason", e.reason());
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badInput(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unreadable(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    ProblemDetail llm(LlmException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}

package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.documents.UnreadableDocumentException;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
import com.bu.tailorsg.updates.UpdateViews;
import com.bu.tailorsg.updates.UpdateViews.UpdateView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;

/**
 * POST /api/master/import (multipart field "file"): a PDF or DOCX resume becomes a PendingUpdate for
 * review (spec 3). Returns 201 with the update and its preview; the master is not changed.
 */
@RestController
public class ResumeImportApiController {

    private final ResumeImportService importer;
    private final PendingUpdateService updates;
    private final ClaudeClient claude;

    public ResumeImportApiController(ResumeImportService importer, PendingUpdateService updates, ClaudeClient claude) {
        this.importer = importer;
        this.updates = updates;
        this.claude = claude;
    }

    @PostMapping(path = "/api/master/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UpdateView> importResume(@RequestParam("file") MultipartFile file) throws IOException {
        if (!claude.isConfigured()) {
            throw new NotConfiguredException();
        }
        PendingUpdate update = importer.importResume(file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.created(URI.create("/api/updates/" + update.getId()))
                .body(UpdateViews.view(update, updates));
    }

    static class NotConfiguredException extends RuntimeException {
        NotConfiguredException() {
            super("Importing needs the extractor model, but ANTHROPIC_API_KEY is not set.");
        }
    }

    @ExceptionHandler(UnreadableDocumentException.class)
    ProblemDetail unreadable(UnreadableDocumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Could not read the file");
        return problem;
    }

    @ExceptionHandler(NotConfiguredException.class)
    ProblemDetail notConfigured(NotConfiguredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    ProblemDetail llmFailed(LlmException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setTitle("The extractor model call failed");
        return problem;
    }
}

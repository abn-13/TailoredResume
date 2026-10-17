package com.bu.tailorsg.web;

import com.bu.tailorsg.llm.LlmPing;
import com.bu.tailorsg.llm.LlmPing.PingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON endpoints for LLM diagnostics. */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmPing llmPing;

    public LlmController(LlmPing llmPing) {
        this.llmPing = llmPing;
    }

    /**
     * {@code POST /api/llm/ping}: one real call to the extractor model (costs a fraction of a cent).
     * POST rather than GET because it spends money and should not be triggered by a prefetch or crawler.
     */
    @PostMapping("/ping")
    public ResponseEntity<PingResult> ping() {
        PingResult result = llmPing.ping();
        HttpStatus status = switch (result.status()) {
            case OK -> HttpStatus.OK;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case ERROR -> HttpStatus.BAD_GATEWAY; // the upstream (Anthropic) call failed
        };
        return ResponseEntity.status(status).body(result);
    }
}

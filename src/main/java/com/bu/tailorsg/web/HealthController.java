package com.bu.tailorsg.web;

import com.bu.tailorsg.llm.LlmUsageMeter;
import com.bu.tailorsg.web.SystemStatusService.SystemStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/health}: is the app up, can it reach its database, and is an API key present.
 *
 * <p>A hand-written endpoint rather than Spring Actuator: we only need a few app-specific facts,
 * and it avoids exposing Actuator's wider surface on a personal app.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final SystemStatusService statusService;
    private final LlmUsageMeter meter;

    public HealthController(SystemStatusService statusService, LlmUsageMeter meter) {
        this.statusService = statusService;
        this.meter = meter;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        SystemStatus status = statusService.current();
        LlmUsageMeter.Snapshot usage = meter.snapshot();
        HealthResponse body = new HealthResponse(
                status.databaseUp() ? "UP" : "DOWN",
                status.databaseUp() ? "UP" : "DOWN",
                status.masterVersion(),
                new LlmHealth(status.apiKeyConfigured(), status.writerModel(), status.extractorModel(),
                        usage.calls(), usage.costDisplay()));
        // 503 lets scripts treat "DB down" as unhealthy. A missing API key is reported, not fatal:
        // everything except LLM features still works without one.
        return ResponseEntity.status(status.databaseUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** @param masterVersion current master resume version, or null before the first save */
    public record HealthResponse(String status, String database, Integer masterVersion, LlmHealth llm) {}

    public record LlmHealth(boolean apiKeyConfigured, String writerModel, String extractorModel,
                            int sessionCalls, String sessionCostUsd) {}
}

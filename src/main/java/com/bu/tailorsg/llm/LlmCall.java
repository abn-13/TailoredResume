package com.bu.tailorsg.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One model call, persisted so the cost footer can show an all-time total (Phase 9). Token counts and an
 * estimated cost only; never prompt or response text (they contain the resume).
 */
@Entity
@Table(name = "llm_call")
public class LlmCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String promptId;

    private int promptVersion;

    @Column(nullable = false, length = 80)
    private String model;

    private long inputTokens;
    private long outputTokens;
    private long cacheWriteTokens;
    private long cacheReadTokens;

    /** Null when the model has no price in application.yml. */
    @Column(precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(nullable = false)
    private Instant createdAt;

    protected LlmCall() {}

    public LlmCall(String promptId, int promptVersion, String model, TokenUsage usage, BigDecimal costUsd, Instant createdAt) {
        this.promptId = promptId;
        this.promptVersion = promptVersion;
        this.model = model;
        this.inputTokens = usage.input();
        this.outputTokens = usage.output();
        this.cacheWriteTokens = usage.cacheWrite();
        this.cacheReadTokens = usage.cacheRead();
        this.costUsd = costUsd;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getPromptId() { return promptId; }
    public int getPromptVersion() { return promptVersion; }
    public String getModel() { return model; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public Instant getCreatedAt() { return createdAt; }
}

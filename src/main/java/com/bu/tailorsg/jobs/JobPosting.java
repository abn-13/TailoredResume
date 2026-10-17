package com.bu.tailorsg.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A job posting and what was extracted from it (spec 2.2). The extracted {@link JobSpec} is stored as JSON
 * and can be corrected by the user; title and company are copied out for lists and file names.
 */
@Entity
@Table(name = "job_posting")
public class JobPosting {

    public enum SourceType { URL, SCREENSHOT, PASTE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SourceType sourceType;

    @Column(length = 2000)
    private String sourceUrl;

    @Column(length = 255)
    private String sourceHost;

    /** The posting text the extractor read (empty for screenshots). */
    @Lob
    private String rawText;

    /** JobSpec JSON. */
    @Lob
    @Column(nullable = false)
    private String extracted;

    @Column(length = 300)
    private String title;

    @Column(length = 300)
    private String company;

    /** How it was fetched, e.g. "MyCareersFuture API" or "3 screenshots". */
    @Column(length = 300)
    private String fetchNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected JobPosting() {}

    public JobPosting(SourceType sourceType, String sourceUrl, String sourceHost, String rawText, String extracted,
                      String title, String company, String fetchNote, Instant createdAt) {
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.sourceHost = sourceHost;
        this.rawText = rawText;
        this.extracted = extracted;
        this.title = title;
        this.company = company;
        this.fetchNote = fetchNote;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void updateSpec(String extracted, String title, String company, Instant at) {
        this.extracted = extracted;
        this.title = title;
        this.company = company;
        this.updatedAt = at;
    }

    public Long getId() { return id; }
    public SourceType getSourceType() { return sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceHost() { return sourceHost; }
    public String getRawText() { return rawText; }
    public String getExtracted() { return extracted; }
    public String getTitle() { return title; }
    public String getCompany() { return company; }
    public String getFetchNote() { return fetchNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.bu.tailorsg.applications;

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
 * One job application (spec 2.2): the tailored resume, cover letter, their check reports, and where it is in
 * the hiring process. JSON documents are stored as CLOBs; {@link ApplicationService} converts them.
 */
@Entity
@Table(name = "application")
public class Application {

    public enum Status { DRAFT, EXPORTED, APPLIED, INTERVIEW, REJECTED, OFFER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long jobPostingId;

    /** The master version the current tailored resume was generated from. */
    private Integer masterVersionNo;

    @Lob
    @Column(nullable = false)
    private String options;

    /** TailoredResume JSON. */
    @Lob
    private String tailoredResume;

    /** CoverLetterDraft JSON. */
    @Lob
    private String coverLetter;

    /** CheckReport JSON for the resume. */
    @Lob
    private String checkReport;

    /** CheckReport JSON for the cover letter. */
    @Lob
    private String coverLetterReport;

    /** FitAssessment JSON (Phase 5b, advisory). */
    @Lob
    private String fitAssessment;

    /** Prompt versions that produced the current content, e.g. {"tailor": 1, "cover-letter": 1}. */
    @Lob
    private String promptVersions;

    /** The last export attempt's checks (ExportService.ExportCheck JSON), shown on the Export tab. */
    @Lob
    private String lastExportCheck;

    /** P5: why the user exported despite ERRORs. */
    @Column(length = 1000)
    private String overrideReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Application() {}

    public Application(long jobPostingId, String options, Instant now) {
        this.jobPostingId = jobPostingId;
        this.options = options;
        this.status = Status.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void setTailored(String tailoredResume, Integer masterVersionNo, Instant now) {
        this.tailoredResume = tailoredResume;
        this.masterVersionNo = masterVersionNo;
        this.updatedAt = now;
    }

    public void setCheckReport(String report, Instant now) { this.checkReport = report; this.updatedAt = now; }
    public void setCoverLetter(String coverLetter, Instant now) { this.coverLetter = coverLetter; this.updatedAt = now; }
    public void setCoverLetterReport(String report, Instant now) { this.coverLetterReport = report; this.updatedAt = now; }
    public void setFitAssessment(String fit, Instant now) { this.fitAssessment = fit; this.updatedAt = now; }
    public void setPromptVersions(String versions) { this.promptVersions = versions; }
    public void setOptions(String options, Instant now) { this.options = options; this.updatedAt = now; }
    public void setOverrideReason(String reason, Instant now) { this.overrideReason = reason; this.updatedAt = now; }
    public void setStatus(Status status, Instant now) { this.status = status; this.updatedAt = now; }
    public void setLastExportCheck(String check, Instant now) { this.lastExportCheck = check; this.updatedAt = now; }

    public Long getId() { return id; }
    public long getJobPostingId() { return jobPostingId; }
    public Integer getMasterVersionNo() { return masterVersionNo; }
    public String getOptions() { return options; }
    public String getTailoredResume() { return tailoredResume; }
    public String getCoverLetter() { return coverLetter; }
    public String getCheckReport() { return checkReport; }
    public String getCoverLetterReport() { return coverLetterReport; }
    public String getFitAssessment() { return fitAssessment; }
    public String getPromptVersions() { return promptVersions; }
    public String getOverrideReason() { return overrideReason; }
    public String getLastExportCheck() { return lastExportCheck; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

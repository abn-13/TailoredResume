package com.bu.tailorsg.export;

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
 * One exported file on disk (spec 2.2), with its SHA-256, the checks it passed or failed, and the override
 * reason if it was exported despite ERRORs (P5). Files are never overwritten: a second export on the same
 * day gets a numbered name.
 */
@Entity
@Table(name = "export_file")
public class ExportFile {

    public enum Kind { RESUME, COVER_LETTER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ExportedFile.Type type;

    @Column(nullable = false, length = 200)
    private String fileName;

    /** Folder and file name, relative to tailorsg.storage.exports-dir. */
    @Column(nullable = false, length = 400)
    private String relativePath;

    @Column(nullable = false, length = 64)
    private String sha256;

    private long sizeBytes;

    private int pageCount;

    @Lob
    private String checkReport;

    @Column(length = 1000)
    private String overrideReason;

    @Column(nullable = false)
    private Instant createdAt;

    protected ExportFile() {}

    public ExportFile(long applicationId, Kind kind, ExportedFile.Type type, String fileName, String relativePath, String sha256,
                      long sizeBytes, int pageCount, String checkReport, String overrideReason, Instant createdAt) {
        this.applicationId = applicationId;
        this.kind = kind;
        this.type = type;
        this.fileName = fileName;
        this.relativePath = relativePath;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.pageCount = pageCount;
        this.checkReport = checkReport;
        this.overrideReason = overrideReason;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public long getApplicationId() { return applicationId; }
    public Kind getKind() { return kind; }
    public ExportedFile.Type getType() { return type; }
    public String getFileName() { return fileName; }
    public String getRelativePath() { return relativePath; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public int getPageCount() { return pageCount; }
    public String getCheckReport() { return checkReport; }
    public String getOverrideReason() { return overrideReason; }
    public Instant getCreatedAt() { return createdAt; }
}

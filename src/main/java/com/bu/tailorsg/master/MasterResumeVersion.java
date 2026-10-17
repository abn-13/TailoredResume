package com.bu.tailorsg.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One saved version of the master resume. Rows are never updated or deleted: every save adds a row,
 * and the row with the highest {@code versionNo} is "current" (spec 2.2).
 *
 * <p>The document is stored as JSON text in a CLOB rather than as tables of jobs and highlights:
 * the schema will keep evolving, history must reproduce each version exactly, and every consumer
 * (editor, checks, LLM prompts) wants the whole document anyway.
 */
@Entity
@Table(name = "master_resume_version", uniqueConstraints = @UniqueConstraint(columnNames = "version_no"))
public class MasterResumeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Lob // H2 maps this to CLOB: no practical size limit for a resume
    @Column(nullable = false, updatable = false)
    private String json;

    @Column(length = 200, updatable = false)
    private String changeNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only (it instantiates entities reflectively). */
    protected MasterResumeVersion() {}

    public MasterResumeVersion(int versionNo, String json, String changeNote, Instant createdAt) {
        this.versionNo = versionNo;
        this.json = json;
        this.changeNote = changeNote;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getJson() {
        return json;
    }

    public String getChangeNote() {
        return changeNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.bu.tailorsg.updates;

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
 * A proposed change to the master resume, waiting for the user's review (spec 2.2). Nothing reaches the
 * master until the user applies it: forms, imports and (Phase 8) free text all end up here first.
 *
 * <p>The change itself is an RFC 6902 JSON Patch against the master document. New items use
 * {@code NEW-n} placeholder IDs; the master pipeline assigns real IDs when the patch is applied.
 */
@Entity
@Table(name = "pending_update")
public class PendingUpdate {

    public enum Source { FREE_TEXT, FORM, IMPORT }

    public enum Status { PROPOSED, APPLIED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) // store the name, not the position, so reordering the enum is safe
    @Column(nullable = false, length = 16)
    private Source sourceType;

    /** What the user supplied: form fields as JSON, or the text extracted from an uploaded file. */
    @Lob
    private String rawInput;

    /** The RFC 6902 patch, as JSON text. Editable while PROPOSED. */
    @Lob
    @Column(nullable = false)
    private String proposedPatch;

    @Column(length = 300)
    private String summary;

    /** JSON array of strings: warnings from the importer, later clarifying questions (Phase 8). */
    @Lob
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** Master version current when the update was proposed (null if there was none). */
    private Integer baseVersionNo;

    /** Master version this update created when applied. */
    private Integer appliedVersionNo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    protected PendingUpdate() {}

    public PendingUpdate(Source sourceType, String rawInput, String proposedPatch, String summary, String notes,
                         Integer baseVersionNo, Instant createdAt) {
        this.sourceType = sourceType;
        this.rawInput = rawInput;
        this.proposedPatch = proposedPatch;
        this.summary = summary;
        this.notes = notes;
        this.baseVersionNo = baseVersionNo;
        this.createdAt = createdAt;
        this.status = Status.PROPOSED;
    }

    public void replacePatch(String patch) {
        requireProposed();
        this.proposedPatch = patch;
    }

    public void markApplied(int versionNo, Instant at) {
        requireProposed();
        this.status = Status.APPLIED;
        this.appliedVersionNo = versionNo;
        this.resolvedAt = at;
    }

    public void markRejected(Instant at) {
        requireProposed();
        this.status = Status.REJECTED;
        this.resolvedAt = at;
    }

    private void requireProposed() {
        if (status != Status.PROPOSED) {
            throw new IllegalStateException("Update #" + id + " is already " + status.name().toLowerCase() + ".");
        }
    }

    public Long getId() {
        return id;
    }

    public Source getSourceType() {
        return sourceType;
    }

    public String getRawInput() {
        return rawInput;
    }

    public String getProposedPatch() {
        return proposedPatch;
    }

    public String getSummary() {
        return summary;
    }

    public String getNotes() {
        return notes;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getBaseVersionNo() {
        return baseVersionNo;
    }

    public Integer getAppliedVersionNo() {
        return appliedVersionNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}

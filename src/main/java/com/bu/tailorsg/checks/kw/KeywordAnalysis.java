package com.bu.tailorsg.checks.kw;

import com.bu.tailorsg.checks.CheckReport.Status;

import java.util.List;

/**
 * Coverage of a job's skills by a resume (spec 6C), for the KW scorecard and the keyword panel.
 *
 * @param subScore round(100 * (0.75 * requiredCoverage + 0.25 * preferredCoverage))
 */
public record KeywordAnalysis(List<TermResult> required, List<TermResult> preferred, double requiredCoverage,
                              double preferredCoverage, int subScore, Status status) {

    public enum Match {
        /** In the resume. */
        MATCHED,
        /** Not in the resume, but the master has it: add it from the cited source. */
        EVIDENCED_NOT_USED,
        /** Not in the master at all: do not add it. */
        GAP
    }

    /**
     * @param sourceId   for EVIDENCED_NOT_USED: the master highlight (or skill) that has it
     * @param sourceText that highlight's text
     * @param itemId     the work/project entry the highlight belongs to (for "add this bullet")
     */
    public record TermResult(String term, boolean required, Match match, String sourceId, String sourceText,
                             String itemId) {}

    /** Whole percentages, rounded the same way as the KW-01 message (Math.round), for the keyword panel. */
    public long requiredPercent() {
        return Math.round(requiredCoverage * 100);
    }

    public long preferredPercent() {
        return Math.round(preferredCoverage * 100);
    }

    public List<TermResult> all() {
        return java.util.stream.Stream.concat(required.stream(), preferred.stream()).toList();
    }

    public List<TermResult> missing() {
        return all().stream().filter(t -> t.match() != Match.MATCHED).toList();
    }
}

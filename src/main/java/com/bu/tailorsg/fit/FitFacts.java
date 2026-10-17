package com.bu.tailorsg.fit;

import com.bu.tailorsg.checks.Severity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * The deterministic half of the job-fit view (Phase 5b): computed by code from the master and the job spec,
 * so it can be trusted the way the checks are. The model's advice is built on top of these facts and never
 * replaces them (P4).
 *
 * @param requiredCoverage share of required terms with evidence in the master (0-1)
 * @param yearsAsked       the largest "N years" the posting asks for, or null
 * @param totalYears       years of work in the master (overlaps merged, internships included)
 * @param softwareYears    the part of that in software roles (by job title)
 * @param startDate        when the user can start, for the work-pass threshold (graduation, or today)
 */
public record FitFacts(List<TermFit> required, List<TermFit> preferred, double requiredCoverage, double preferredCoverage,
                       Integer yearsAsked, double totalYears, double softwareYears, String startDate, List<Flag> flags) {

    public enum Evidence {
        /** A master highlight shows it. */
        EVIDENCED,
        /** In the skills list, and that skill cites evidence. */
        LISTED,
        /** In the skills list with no evidence: true or not, a recruiter cannot see it. */
        UNEVIDENCED,
        /** Somewhere else in the master (a course, a summary). */
        MENTIONED,
        /** Not in the master at all. */
        GAP
    }

    /** @param sourceId the highlight or skill that shows it, if any */
    public record TermFit(String term, boolean required, Evidence evidence, String sourceId, String sourceText) {

        @JsonIgnore
        public boolean counts() {
            return evidence != Evidence.GAP && evidence != Evidence.UNEVIDENCED;
        }
    }

    /** @param code e.g. FIT-YEARS, FIT-EP-SALARY */
    public record Flag(String code, Severity severity, String message) {}

    /** Required terms, then preferred ones. */
    @JsonIgnore
    public List<TermFit> allTerms() {
        return java.util.stream.Stream.concat(required.stream(), preferred.stream()).toList();
    }

    @JsonIgnore
    public List<String> gaps() {
        return java.util.stream.Stream.concat(required.stream(), preferred.stream())
                .filter(t -> t.evidence() == Evidence.GAP).map(TermFit::term).toList();
    }

    @JsonIgnore
    public List<String> requiredGaps() {
        return required.stream().filter(t -> t.evidence() == Evidence.GAP).map(TermFit::term).toList();
    }
}

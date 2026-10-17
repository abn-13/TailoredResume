package com.bu.tailorsg.checks;

import com.bu.tailorsg.checks.kw.KeywordAnalysis;

import java.time.Instant;
import java.util.List;

/**
 * The scorecard: one result per category that ran, plus the keyword breakdown for the keyword panel.
 * There is deliberately no overall "ATS score" (spec 6).
 *
 * @param keywords null when no job spec was available
 */
public record CheckReport(List<CategoryResult> categories, KeywordAnalysis keywords, Instant ranAt) {

    public static final String BANNER = "Scores are heuristics from published guidance, not a real employer's ATS.";

    public enum Status { PASS, WARN, FAIL }

    /**
     * @param subScore 0-100: 100 minus 25 per ERROR and 8 per WARN (KW: its coverage formula)
     */
    public record CategoryResult(Category category, Status status, int subScore, int errors, int warnings, int infos,
                                 List<Finding> findings) {}

    public boolean hasErrors() {
        return categories.stream().anyMatch(c -> c.errors() > 0);
    }

    public List<Finding> errors() {
        return categories.stream().flatMap(c -> c.findings().stream()).filter(f -> f.severity() == Severity.ERROR).toList();
    }

    public List<Finding> all() {
        return categories.stream().flatMap(c -> c.findings().stream()).toList();
    }

    public CategoryResult category(Category category) {
        return categories.stream().filter(c -> c.category() == category).findFirst().orElse(null);
    }
}

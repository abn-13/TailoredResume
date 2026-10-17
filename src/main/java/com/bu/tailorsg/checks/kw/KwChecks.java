package com.bu.tailorsg.checks.kw;

import com.bu.tailorsg.checks.Bullet;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.Check;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.TextTools;
import com.bu.tailorsg.checks.kw.KeywordAnalysis.Match;
import com.bu.tailorsg.checks.kw.KeywordAnalysis.TermResult;
import com.bu.tailorsg.checks.kw.KeywordMatcher.Term;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyword match (spec 6C). Only runs when a job spec is available. Thresholds are heuristics: no real ATS
 * publishes its scoring, so KW findings are never ERRORs and never block export.
 */
public final class KwChecks {

    private KwChecks() {}

    /**
     * KW-01: required/preferred coverage. WARN when required coverage is below the pass threshold (80%);
     * INFO per missing term, classified EVIDENCED_NOT_USED (add it from the master) or GAP (do not add).
     * Basis: ATS keyword filtering as described by recruiters; JobScan-style coverage heuristics.
     */
    @Component
    public static class Kw01Coverage implements Check {
        private final KeywordAnalyzer analyzer;

        public Kw01Coverage(KeywordAnalyzer analyzer) {
            this.analyzer = analyzer;
        }

        public String id() { return "KW-01"; }
        public Category category() { return Category.KW; }
        public List<Finding> run(CheckContext ctx) {
            if (ctx.job() == null) {
                return List.of();
            }
            KeywordAnalysis a = analyzer.analyse(ctx.job(), ctx.text(), ctx.master(), ctx.thresholds().kw());
            List<Finding> findings = new ArrayList<>();
            long matched = a.required().stream().filter(t -> t.match() == Match.MATCHED).count();
            String summary = "Required skills covered: " + matched + " of " + a.required().size()
                    + " (" + Math.round(a.requiredCoverage() * 100) + "%).";
            boolean low = a.requiredCoverage() < ctx.thresholds().kw().requiredPass();
            findings.add(Finding.of(id(), low ? Severity.WARN : Severity.INFO, "keywords", summary)
                    .withFix(low ? "Use evidenced terms from your master; never add skills you do not have." : ""));
            for (TermResult t : a.missing()) {
                String which = t.required() ? "Required" : "Preferred";
                if (t.match() == Match.EVIDENCED_NOT_USED) {
                    findings.add(new Finding(id(), Severity.INFO, "kw-" + t.term(), which + " \"" + t.term()
                            + "\" is in your master but not in this resume.",
                            "Add from " + (t.sourceId() == null ? "your master" : t.sourceId()) + ": "
                                    + TextTools.clip(t.sourceText(), 120), ""));
                } else {
                    findings.add(new Finding(id(), Severity.INFO, "kw-" + t.term(), which + " \"" + t.term()
                            + "\" has no evidence in your master (gap).",
                            "Do not add it. Consider addressing it in the cover letter, or add it to the master only if true.", ""));
                }
            }
            return findings;
        }
    }

    /**
     * KW-02 WARN: keyword stuffing. A dictionary keyword used more than 3 times in the resume, or a bullet with
     * more than 4 dictionary skills (a keyword list, not an achievement). Basis: recruiter guidance; spec 5.3 rule 8.
     */
    @Component
    public static class Kw02Stuffing implements Check {
        private final KeywordAnalyzer analyzer;

        public Kw02Stuffing(KeywordAnalyzer analyzer) {
            this.analyzer = analyzer;
        }

        public String id() { return "KW-02"; }
        public Category category() { return Category.KW; }
        public List<Finding> run(CheckContext ctx) {
            List<Term> all = analyzer.dictionaryTerms();
            List<Finding> findings = new ArrayList<>();
            int maxRepeats = ctx.thresholds().kw().maxKeywordRepeats();
            if (ctx.mode() != CheckMode.MASTER) { // the master naturally repeats skills across many bullets
                String body = ctx.document() != null ? ctx.document().bodyText() : ctx.text();
                for (Term term : all) {
                    int n = term.countIn(body);
                    if (n > maxRepeats) {
                        findings.add(Finding.of(id(), Severity.WARN, "keywords", "\"" + term.label() + "\" appears "
                                + n + " times (max " + maxRepeats + ").").withFix("Keep it where it matters most."));
                    }
                }
            }
            int maxPerBullet = ctx.thresholds().kw().maxSkillsPerBullet();
            for (Bullet b : ctx.bullets()) {
                List<String> found = all.stream().filter(t -> t.foundIn(b.text())).map(Term::label).toList();
                if (found.size() > maxPerBullet) {
                    findings.add(new Finding(id(), Severity.WARN, b.anchor(), found.size() + " skills in one bullet reads as a keyword list.",
                            "Keep the 2-3 tools that mattered most.", String.join(", ", found)));
                }
            }
            return findings;
        }

    }

    /** KW-03 INFO: whether the JD's job title (or a close variant) appears in the title line or summary. */
    @Component
    public static class Kw03Title implements Check {
        public String id() { return "KW-03"; }
        public Category category() { return Category.KW; }
        public List<Finding> run(CheckContext ctx) {
            if (ctx.job() == null || ctx.job().title() == null || ctx.job().title().isBlank()) {
                return List.of();
            }
            String title = ctx.job().title().strip();
            boolean present = TextTools.containsPhrase(ctx.titleLine(), title);
            return List.of(Finding.of(id(), Severity.INFO, "header", present
                    ? "The job title \"" + title + "\" appears in your title line or summary."
                    : "The job title \"" + title + "\" is not in your title line or summary.")
                    .withFix(present ? "" : "Use it in the title line if it truthfully describes you."));
        }
    }
}

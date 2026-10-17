package com.bu.tailorsg.checks;

import com.bu.tailorsg.checks.CheckReport.CategoryResult;
import com.bu.tailorsg.checks.CheckReport.Status;
import com.bu.tailorsg.checks.kw.KeywordAnalysis;
import com.bu.tailorsg.checks.kw.KeywordAnalyzer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the deterministic rules and builds the scorecard (spec 6). Rules are Spring beans implementing
 * {@link Check}; Spring injects all of them as a list, so adding a rule is just adding a class.
 *
 * <p>Sub-score per category: start at 100, minus 25 per ERROR and 8 per WARN, floor 0. The status is FAIL
 * with any ERROR, WARN with any WARN, otherwise PASS. KW is the exception: its score and status come from
 * keyword coverage, and it never produces ERRORs, so it never blocks export.
 */
@Service
public class CheckEngine {

    private final List<Check> checks;
    private final KeywordAnalyzer keywordAnalyzer;
    private final Clock clock;

    public CheckEngine(List<Check> checks, KeywordAnalyzer keywordAnalyzer, Clock clock) {
        this.checks = checks.stream().sorted(Comparator.comparing(Check::id)).toList();
        this.keywordAnalyzer = keywordAnalyzer;
        this.clock = clock;
    }

    public List<Check> checks() {
        return checks;
    }

    public CheckReport run(CheckContext ctx, Set<Category> categories) {
        Map<Category, List<Finding>> byCategory = new EnumMap<>(Category.class);
        for (Check check : checks) {
            if (categories.contains(check.category()) && check.modes().contains(ctx.mode())) {
                byCategory.computeIfAbsent(check.category(), c -> new ArrayList<>()).addAll(check.run(ctx));
            }
        }
        KeywordAnalysis keywords = null;
        if (categories.contains(Category.KW) && ctx.job() != null) {
            keywords = keywordAnalyzer.analyse(ctx.job(), ctx.text(), ctx.master(), ctx.thresholds().kw());
        }
        List<CategoryResult> results = new ArrayList<>();
        for (Category category : Category.values()) {
            if (!byCategory.containsKey(category)) {
                continue;
            }
            List<Finding> findings = byCategory.get(category).stream()
                    .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::checkId)).toList();
            int errors = count(findings, Severity.ERROR);
            int warnings = count(findings, Severity.WARN);
            int infos = count(findings, Severity.INFO);
            int score;
            Status status;
            if (category == Category.KW && keywords != null) {
                score = keywords.subScore();
                status = keywords.status();
            } else {
                score = Math.max(0, 100 - 25 * errors - 8 * warnings);
                status = errors > 0 ? Status.FAIL : warnings > 0 ? Status.WARN : Status.PASS;
            }
            results.add(new CategoryResult(category, status, score, errors, warnings, infos, findings));
        }
        return new CheckReport(results, keywords, clock.instant());
    }

    private static int count(List<Finding> findings, Severity severity) {
        return (int) findings.stream().filter(f -> f.severity() == severity).count();
    }
}

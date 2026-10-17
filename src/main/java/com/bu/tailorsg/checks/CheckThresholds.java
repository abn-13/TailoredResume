package com.bu.tailorsg.checks;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Every tunable number and word list used by the rules ({@code tailorsg.checks.*}, spec 6: "All thresholds
 * live in application.yml"). The Settings page can override them at runtime (see ThresholdService).
 */
@ConfigurationProperties(prefix = "tailorsg.checks")
public record CheckThresholds(Kw kw, Lint lint, Pages pages, Ats ats, Cl cl) {

    /**
     * @param requiredPass       required-skill coverage at or above which KW passes (heuristic)
     * @param requiredWarn       coverage at or above which KW warns rather than fails
     * @param maxKeywordRepeats  KW-02: a keyword appearing more often than this is stuffing
     * @param maxSkillsPerBullet KW-02: more dictionary skills than this in one bullet is a keyword list
     */
    public record Kw(double requiredPass, double requiredWarn, int maxKeywordRepeats, int maxSkillsPerBullet) {}

    /**
     * @param charsPerLine          approximate characters per rendered line (A4, 2 cm margins, 11pt)
     * @param experienceMetricShare LINT-02: share of experience bullets that should contain a metric
     * @param projectMetricShare    LINT-02: same for projects
     * @param maxSameStartVerb      LINT-08: the same first verb may start at most this many bullets
     * @param cliches               LINT-06 filler phrases
     * @param redundant             LINT-07 wordy phrase -> suggested replacement
     */
    public record Lint(int minWords, int maxWords, int maxLines, int charsPerLine, double experienceMetricShare,
                       double projectMetricShare, int maxSameStartVerb, List<String> cliches,
                       Map<String, String> redundant) {}

    /** @param wordsPerPage body words per page for page estimates (spec: 450-550 for this template) */
    public record Pages(int wordsPerPage) {}

    public record Ats(int minTextChars, double minTextRatio, double orderWarn, double orderError, double minBodyPt,
                      double errorBodyPt, double parserRecall, int dateWindowChars, List<String> fonts) {}

    public record Cl(int minWords, int maxWords, int hardMaxWords, int maxIRun, List<String> bannedPhrases) {}
}

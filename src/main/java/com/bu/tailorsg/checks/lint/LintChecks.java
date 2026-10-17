package com.bu.tailorsg.checks.lint;

import com.bu.tailorsg.checks.ActionVerbs;
import com.bu.tailorsg.checks.Bullet;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.Check;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.TextTools;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bullet quality (spec 6D). Runs on the tailored resume and on the master (Master health page).
 *
 * <p>Background: university career guides (e.g. NUS Centre for Future-ready Graduates) and recruiter
 * guidance agree on the same shape: start with a strong action verb, show the result with a number where
 * one exists, keep it to one or two lines, no first person, no filler.
 */
public final class LintChecks {

    private LintChecks() {}

    static Finding at(String id, Severity severity, Bullet b, String message) {
        return new Finding(id, severity, b.anchor(), message, "", TextTools.clip(b.text(), 160));
    }

    /** LINT-01: first word must be an action verb (WARN); banned openers are an ERROR. Basis: NUS CFG guide. */
    @Component
    public static class Lint01ActionVerb implements Check {
        static final Pattern BANNED = Pattern.compile(
                "^\\s*(responsible for|helped|assisted|worked on|participated in|involved in|tasked with|duties included)\\b",
                Pattern.CASE_INSENSITIVE);

        public String id() { return "LINT-01"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            for (Bullet b : ctx.bullets()) {
                var banned = BANNED.matcher(b.text());
                if (banned.find()) {
                    findings.add(at(id(), Severity.ERROR, b, "Starts with \"" + banned.group(1) + "\", which hides what you did.")
                            .withFix("Start with the action itself, e.g. \"Built…\", \"Reduced…\"."));
                    continue;
                }
                String first = TextTools.firstWord(b.text());
                if (!first.isEmpty() && !ActionVerbs.get().isActionVerb(first)) {
                    findings.add(at(id(), Severity.WARN, b, "\"" + first + "\" is not a strong action verb.")
                            .withFix("Start with a verb such as Built, Designed, Reduced, Automated."));
                }
            }
            return findings;
        }
    }

    /**
     * LINT-02: INFO for each bullet without a metric; WARN when fewer than the target share of experience
     * (50%) or project (30%) bullets have one. Never suggests inventing numbers. Basis: recruiter guidance
     * (quantify impact); thresholds are heuristics.
     */
    @Component
    public static class Lint02Metrics implements Check {
        public String id() { return "LINT-02"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            int exp = 0, expWith = 0, proj = 0, projWith = 0;
            for (Bullet b : ctx.bullets()) {
                if (b.kind() == Bullet.Kind.OTHER) {
                    continue;
                }
                boolean has = TextTools.hasMetric(b.text());
                if (b.kind() == Bullet.Kind.EXPERIENCE) { exp++; if (has) expWith++; }
                else { proj++; if (has) projWith++; }
                if (!has) {
                    findings.add(at(id(), Severity.INFO, b, "No number shows the result.")
                            .withFix("If you know a measurable outcome, add it to the master (Add metric). Never invent one."));
                }
            }
            var t = ctx.thresholds().lint();
            if (exp > 0 && expWith / (double) exp < t.experienceMetricShare()) {
                findings.add(Finding.of(id(), Severity.WARN, "Experience", expWith + " of " + exp
                        + " experience bullets have a metric (target " + pct(t.experienceMetricShare()) + ").")
                        .withFix("Ask yourself for the numbers behind the biggest bullets and add them to the master."));
            }
            if (proj > 0 && projWith / (double) proj < t.projectMetricShare()) {
                findings.add(Finding.of(id(), Severity.WARN, "Projects", projWith + " of " + proj
                        + " project bullets have a metric (target " + pct(t.projectMetricShare()) + ")."));
            }
            return findings;
        }

        private static String pct(double share) {
            return Math.round(share * 100) + "%";
        }
    }

    /** LINT-03 WARN: outside 12-28 words, or longer than 2 rendered lines. Basis: spec 5.3 rule 7. */
    @Component
    public static class Lint03Length implements Check {
        public String id() { return "LINT-03"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            var t = ctx.thresholds().lint();
            List<Finding> findings = new ArrayList<>();
            for (Bullet b : ctx.bullets()) {
                int words = TextTools.wordCount(b.text());
                if (words < t.minWords() || words > t.maxWords()) {
                    findings.add(at(id(), Severity.WARN, b, words + " words; aim for " + t.minWords() + "-" + t.maxWords() + ".")
                            .withFix(words < t.minWords() ? "Add the context or the result." : "Cut to one idea."));
                } else if (b.text().length() > t.maxLines() * t.charsPerLine()) {
                    findings.add(at(id(), Severity.WARN, b, "About " + (b.text().length() / t.charsPerLine() + 1)
                            + " lines when printed; aim for " + t.maxLines() + " at most."));
                }
            }
            return findings;
        }
    }

    /** LINT-04 WARN: first-person pronouns. Basis: resume convention (implied first person). */
    @Component
    public static class Lint04Pronouns implements Check {
        static final Pattern I = Pattern.compile("\\bI\\b");
        static final Pattern OTHERS = Pattern.compile("\\b(me|my|we|our|us|myself)\\b", Pattern.CASE_INSENSITIVE);

        public String id() { return "LINT-04"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            for (Bullet b : ctx.bullets()) {
                var m = OTHERS.matcher(b.text());
                if (I.matcher(b.text()).find() || m.find()) {
                    findings.add(at(id(), Severity.WARN, b, "Uses a first-person pronoun.").withFix("Drop I/me/my/we/our."));
                }
            }
            return findings;
        }
    }

    /** LINT-05 WARN: a past role's bullet not in past tense (first word ends -ed or is a known past form). */
    @Component
    public static class Lint05Tense implements Check {
        public String id() { return "LINT-05"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            for (Bullet b : ctx.bullets()) {
                if (b.currentRole() || b.kind() == Bullet.Kind.OTHER) {
                    continue;
                }
                String first = TextTools.firstWord(b.text());
                if (!first.isEmpty() && ActionVerbs.get().isPresentForm(first) && !ActionVerbs.get().isPastTense(first)) {
                    findings.add(at(id(), Severity.WARN, b, "\"" + first + "\" is present tense for a role that has ended.")
                            .withFix("Use past tense."));
                }
            }
            return findings;
        }
    }

    /** LINT-06 WARN: clichés and filler (configurable list). Basis: NUS CFG guide; recruiter guidance. */
    @Component
    public static class Lint06Cliches implements Check {
        public String id() { return "LINT-06"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            for (Bullet b : ctx.bullets()) {
                for (String cliche : ctx.thresholds().lint().cliches()) {
                    if (TextTools.containsPhrase(b.text(), cliche)) {
                        findings.add(at(id(), Severity.WARN, b, "Filler: \"" + cliche + "\".").withFix("Show it with a fact instead."));
                    }
                }
            }
            return findings;
        }
    }

    /** LINT-07 WARN: wordy phrasing, e.g. "Conducted research" for "Researched". Basis: NUS CFG guide. */
    @Component
    public static class Lint07Redundant implements Check {
        public String id() { return "LINT-07"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            for (Bullet b : ctx.bullets()) {
                ctx.thresholds().lint().redundant().forEach((phrase, better) -> {
                    if (TextTools.containsPhrase(b.text(), phrase)) {
                        findings.add(at(id(), Severity.WARN, b, "Wordy: \"" + phrase + "\".").withFix("Use \"" + better + "\"."));
                    }
                });
            }
            return findings;
        }
    }

    /** LINT-08 WARN: the same first verb used more than twice, or twice in a row. */
    @Component
    public static class Lint08RepeatedVerbs implements Check {
        public String id() { return "LINT-08"; }
        public Category category() { return Category.LINT; }
        public Set<CheckMode> modes() { return EnumSet.of(CheckMode.RESUME, CheckMode.EXPORT); }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            Bullet previous = null;
            for (Bullet b : ctx.bullets()) {
                String first = TextTools.firstWord(b.text()).toLowerCase(Locale.ROOT);
                if (first.isEmpty()) {
                    continue;
                }
                counts.merge(first, 1, Integer::sum);
                if (previous != null && previous.itemId().equals(b.itemId())
                        && TextTools.firstWord(previous.text()).equalsIgnoreCase(first)) {
                    findings.add(at(id(), Severity.WARN, b, "Starts with the same verb as the bullet before it.")
                            .withFix("Vary the verb."));
                }
                previous = b;
            }
            int max = ctx.thresholds().lint().maxSameStartVerb();
            counts.forEach((verb, n) -> {
                if (n > max) {
                    findings.add(Finding.of(id(), Severity.WARN, "", "\"" + capitalise(verb) + "\" starts " + n
                            + " bullets (max " + max + ").").withFix("Swap some for synonyms."));
                }
            });
            return findings;
        }

        private static String capitalise(String s) {
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    /**
     * LINT-09 WARN: bullet counts per entry outside spec 5.3 rule 9 (most relevant role 3-6, others 1-3,
     * projects 2-3, at most 3 projects on one page), and trailing full stops used inconsistently.
     */
    @Component
    public static class Lint09Counts implements Check {
        public String id() { return "LINT-09"; }
        public Category category() { return Category.LINT; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            List<Bullet> scoped = ctx.bullets().stream().filter(b -> b.kind() != Bullet.Kind.OTHER).toList();
            long withStop = scoped.stream().filter(b -> b.text().strip().endsWith(".")).count();
            if (withStop > 0 && withStop < scoped.size()) {
                findings.add(Finding.of(id(), Severity.WARN, "", withStop + " of " + scoped.size()
                        + " bullets end with a full stop.").withFix("Be consistent; this template uses no full stops."));
            }
            if (ctx.mode() == CheckMode.MASTER) {
                return findings; // the master holds everything; counts only matter on a tailored resume
            }
            Map<String, Integer> experience = new LinkedHashMap<>();
            Map<String, Integer> projects = new LinkedHashMap<>();
            for (Bullet b : scoped) {
                (b.kind() == Bullet.Kind.EXPERIENCE ? experience : projects).merge(b.itemId(), 1, Integer::sum);
            }
            String mostRelevant = experience.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            experience.forEach((item, n) -> {
                boolean main = item.equals(mostRelevant);
                int min = main ? 3 : 1;
                int max = main ? 6 : 3;
                if (n < min || n > max) {
                    findings.add(Finding.of(id(), Severity.WARN, item, item + " has " + n + " bullets; "
                            + (main ? "the most relevant role" : "other roles") + " should have " + min + "-" + max + "."));
                }
            });
            projects.forEach((item, n) -> {
                if (n < 2 || n > 3) {
                    findings.add(Finding.of(id(), Severity.WARN, item, item + " has " + n + " bullets; projects should have 2-3."));
                }
            });
            if (ctx.pageTarget() == 1 && projects.size() > 3) {
                findings.add(Finding.of(id(), Severity.WARN, "Projects", projects.size() + " projects on a one-page resume; keep at most 3."));
            }
            return findings;
        }
    }
}

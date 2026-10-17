package com.bu.tailorsg.checks.cl;

import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.Check;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.TextTools;
import com.bu.tailorsg.coverletter.CoverLetterDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cover letter rules (spec 6F). They run in {@link CheckMode#COVER_LETTER} on the model's draft; the TRUTH
 * rules run alongside them, paragraph by paragraph.
 */
public final class ClChecks {

    private ClChecks() {}

    static final Set<CheckMode> LETTER = EnumSet.of(CheckMode.COVER_LETTER);

    abstract static class ClRule implements Check {
        public Category category() { return Category.CL; }
        public Set<CheckMode> modes() { return LETTER; }

        public final List<Finding> run(CheckContext ctx) {
            return ctx.coverLetter() == null ? List.of() : check(ctx, ctx.coverLetter());
        }

        abstract List<Finding> check(CheckContext ctx, CoverLetterDraft letter);
    }

    /**
     * CL-01 ERROR: an em dash (U+2014) or en dash (U+2013) anywhere. Offers the {@link DashFixer} auto-fix.
     * Basis: spec 5.4 writing style; dashes read as machine-written.
     */
    @Component
    public static class Cl01Dashes extends ClRule {
        public String id() { return "CL-01"; }
        List<Finding> check(CheckContext ctx, CoverLetterDraft letter) {
            List<Finding> out = new ArrayList<>();
            for (int i = 0; i < letter.paragraphs().size(); i++) {
                String text = letter.paragraphs().get(i).text();
                if (DashFixer.hasDash(text)) {
                    out.add(new Finding(id(), Severity.ERROR, "p-" + i, "Dash in paragraph " + (i + 1) + ".",
                            "Use \"Fix dashes\" to replace them with commas (shown before applying).", TextTools.clip(text, 160)));
                }
            }
            for (String part : List.of(nz(letter.recipientLine()), nz(letter.subject()), nz(letter.signOff()))) {
                if (DashFixer.hasDash(part)) {
                    out.add(new Finding(id(), Severity.ERROR, "letter", "Dash in \"" + part + "\".", "", part));
                }
            }
            return out;
        }
    }

    /** CL-02: body word count outside 250-380 is a WARN, above 450 an ERROR. Basis: half to one A4 page. */
    @Component
    public static class Cl02Length extends ClRule {
        public String id() { return "CL-02"; }
        List<Finding> check(CheckContext ctx, CoverLetterDraft letter) {
            var t = ctx.thresholds().cl();
            int words = TextTools.wordCount(letter.bodyText());
            if (words > t.hardMaxWords()) {
                return List.of(Finding.of(id(), Severity.ERROR, "letter", words + " words; the limit is " + t.hardMaxWords() + ".")
                        .withFix("Cut to " + t.minWords() + "-" + t.maxWords() + " words."));
            }
            if (words < t.minWords() || words > t.maxWords()) {
                return List.of(Finding.of(id(), Severity.WARN, "letter", words + " words; aim for " + t.minWords() + "-" + t.maxWords() + "."));
            }
            return List.of();
        }
    }

    /**
     * CL-03 WARN: banned openers and phrases (spec 5.4), more than 3 sentences in a row starting with "I",
     * and exclamation marks. Basis: spec 5.4 writing style.
     */
    @Component
    public static class Cl03Style extends ClRule {
        public String id() { return "CL-03"; }
        List<Finding> check(CheckContext ctx, CoverLetterDraft letter) {
            List<Finding> out = new ArrayList<>();
            String all = letter.fullText();
            for (String phrase : ctx.thresholds().cl().bannedPhrases()) {
                if (TextTools.containsPhrase(all, phrase)) {
                    out.add(Finding.of(id(), Severity.WARN, "letter", "Avoid \"" + phrase + "\".").withFix("Say it plainly in your own words."));
                }
            }
            int run = 0;
            int maxRun = 0;
            for (String s : TextTools.sentences(letter.bodyText())) {
                run = "I".equals(TextTools.firstWord(s)) ? run + 1 : 0;
                maxRun = Math.max(maxRun, run);
            }
            if (maxRun > ctx.thresholds().cl().maxIRun()) {
                out.add(Finding.of(id(), Severity.WARN, "letter", maxRun + " sentences in a row start with \"I\" (max "
                        + ctx.thresholds().cl().maxIRun() + ").").withFix("Start some with the company, the work or the result."));
            }
            if (letter.bodyText().contains("!")) {
                out.add(Finding.of(id(), Severity.WARN, "letter", "Exclamation mark.").withFix("Use a full stop."));
            }
            return out;
        }
    }

    /**
     * CL-04 WARN: no company-specific fact: no sentence with the company name plus a term from the job's
     * company facts, and no content from the user's own notes. Basis: recruiters skip generic letters.
     */
    @Component
    public static class Cl04CompanyFact extends ClRule {
        static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{4,}");

        public String id() { return "CL-04"; }
        List<Finding> check(CheckContext ctx, CoverLetterDraft letter) {
            String company = ctx.job() == null ? "" : TextTools.shortCompanyName(ctx.job().company());
            List<String> facts = ctx.job() == null ? List.of() : ctx.job().companyFacts();
            for (String sentence : TextTools.sentences(letter.bodyText())) {
                boolean names = !company.isEmpty() && TextTools.containsPhrase(sentence, company);
                if (names && facts.stream().anyMatch(f -> shares(sentence, f, 2))) {
                    return List.of();
                }
            }
            String notes = ctx.userNotes().getOrDefault("whyCompany", "");
            if (!notes.isBlank() && TextTools.sentences(letter.bodyText()).stream().anyMatch(s -> shares(s, notes, 3))) {
                return List.of();
            }
            return List.of(Finding.of(id(), Severity.WARN, "p-0", "No company-specific fact (the company's name with a fact from"
                    + " the posting, or your own reasons).").withFix("Add your reasons under \"Why this company\" and regenerate,"
                    + " or edit the opening paragraph."));
        }

        static boolean shares(String a, String b, int min) {
            Set<String> left = new java.util.HashSet<>();
            var m = WORD.matcher(a.toLowerCase(Locale.ROOT));
            while (m.find()) {
                left.add(m.group());
            }
            int common = 0;
            var n = WORD.matcher(b.toLowerCase(Locale.ROOT));
            Set<String> seen = new java.util.HashSet<>();
            while (n.find()) {
                if (seen.add(n.group()) && left.contains(n.group())) {
                    common++;
                }
            }
            return common >= min;
        }
    }

    /** CL-05 WARN: a generic salutation. Prefer a named person, or "Dear Hiring Manager". Basis: spec 6F. */
    @Component
    public static class Cl05Salutation extends ClRule {
        static final Pattern GENERIC = Pattern.compile("(?i)to whom it may concern|dear sir or madam|dear sir/madam|dear sirs?\\b");

        public String id() { return "CL-05"; }
        List<Finding> check(CheckContext ctx, CoverLetterDraft letter) {
            return GENERIC.matcher(nz(letter.recipientLine()) + "\n" + letter.bodyText()).find()
                    ? List.of(Finding.of(id(), Severity.WARN, "letter", "Generic salutation.").withFix("Use a name if the posting gives one, otherwise \"Dear Hiring Manager\"."))
                    : List.of();
        }
    }

    /**
     * CL-06 ERROR: mentions salary, age, nationality or marital status (P6, TAFEP). One neutral
     * work-authorisation sentence is allowed when the user ticked "mention work authorisation".
     */
    @Component
    public static class Cl06Personal extends ClRule {
        // Not "single" or "children": "a single service", "child processes" are ordinary in a software letter.
        static final Pattern PERSONAL = Pattern.compile("(?i)(\\bsalar(y|ies)\\b|\\bremuneration\\b|S\\$\\s?\\d|\\bSGD\\s?\\d|\\bexpected pay\\b|"
                + "\\byears old\\b|\\baged \\d|\\bmy age\\b|\\bdate of birth\\b|\\bnationality\\b|\\bmarital\\b|\\bmarried\\b|"
                + "\\bdivorced\\b|\\bspouse\\b|\\breligio(n|us)\\b)");
        /** "a Pakistani national", "an Indian citizen": case-sensitive so "national" in "a national bank" is fine. */
        static final Pattern NATIONAL = Pattern.compile("\\b(?:a|an|am)\\s+[A-Z][a-z]+\\s+(?:national|citizen)\\b");
        static final Pattern WORK_PASS = Pattern.compile("(?i)\\b(employment pass|work pass|work authori[sz]ation|work permit|visa|sponsorship|S Pass)\\b");

        public String id() { return "CL-06"; }
        List<Finding> check(CheckContext ctx, CoverLetterDraft letter) {
            List<Finding> out = new ArrayList<>();
            boolean passAllowed = "true".equals(ctx.userNotes().get("mentionWorkPass"));
            int passSentences = 0;
            for (int i = 0; i < letter.paragraphs().size(); i++) {
                for (String s : TextTools.sentences(letter.paragraphs().get(i).text())) {
                    var personal = PERSONAL.matcher(s);
                    var national = NATIONAL.matcher(s);
                    String hit = personal.find() ? personal.group() : national.find() ? national.group() : null;
                    if (hit != null) {
                        out.add(new Finding(id(), Severity.ERROR, "p-" + i, "Mentions \"" + hit + "\" (personal data).",
                                "Remove it: Singapore fair-employment guidance (TAFEP) says employers should not ask, so do not offer it.", s));
                    }
                    if (WORK_PASS.matcher(s).find()) {
                        passSentences++;
                        if (!passAllowed) {
                            out.add(new Finding(id(), Severity.ERROR, "p-" + i, "Mentions work authorisation, but you did not ask for that.",
                                    "Remove it, or tick \"Mention work authorisation\" and regenerate.", s));
                        }
                    }
                }
            }
            if (passAllowed && passSentences > 1) {
                out.add(Finding.of(id(), Severity.ERROR, "letter", passSentences + " sentences about work authorisation; keep one neutral sentence."));
            }
            return out;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

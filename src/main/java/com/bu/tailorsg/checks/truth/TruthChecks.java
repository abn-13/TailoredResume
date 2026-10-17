package com.bu.tailorsg.checks.truth;

import com.bu.tailorsg.checks.ActionVerbs;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.Check;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.TextTools;
import com.bu.tailorsg.checks.kw.KeywordAnalyzer;
import com.bu.tailorsg.checks.kw.KeywordMatcher;
import com.bu.tailorsg.checks.kw.KeywordMatcher.Term;
import com.bu.tailorsg.checks.truth.TruthSources.Item;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.render.ResumeDates;
import com.bu.tailorsg.render.ResumeDocument;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No fabrication (spec 6E): every generated sentence must trace back to the master. These rules are why the
 * scorecard can be trusted: they compare text with text, and never ask a model whether it made something up.
 * ERRORs here block export (P5).
 */
public final class TruthChecks {

    private TruthChecks() {}

    static final Set<CheckMode> GENERATED = EnumSet.of(CheckMode.RESUME, CheckMode.EXPORT, CheckMode.COVER_LETTER);
    static final Set<CheckMode> RESUME_ONLY = EnumSet.of(CheckMode.RESUME, CheckMode.EXPORT);

    static Finding at(String id, Severity severity, Item item, String message) {
        return new Finding(id, severity, item.anchor(), message, "", TextTools.clip(item.text(), 200));
    }

    /**
     * TRUTH-01 ERROR: every bullet cites at least one source, every cited ID exists in the master, and a bullet
     * only cites highlights of the entry it sits under (otherwise project work would be credited to an
     * employer). Also every workId, projectId and skillId exists. Basis: P3.
     */
    @Component
    public static class Truth01Sources implements Check {
        public String id() { return "TRUTH-01"; }
        public Category category() { return Category.TRUTH; }
        public Set<CheckMode> modes() { return GENERATED; }
        public List<Finding> run(CheckContext ctx) {
            TruthSources src = new TruthSources(ctx);
            List<Finding> findings = new ArrayList<>();
            for (Item item : src.items) {
                boolean paragraph = ctx.mode() == CheckMode.COVER_LETTER;
                if (item.sourceIds().isEmpty() && !paragraph) {
                    findings.add(at(id(), Severity.ERROR, item, "Cites no source, so nothing shows it is true.")
                            .withFix("Cite the master highlight it comes from, or remove it."));
                }
                for (String sid : item.sourceIds()) {
                    if (!src.exists(sid)) {
                        findings.add(at(id(), Severity.ERROR, item, "Cites " + sid + ", which is not in your master."));
                    } else if (item.itemId() != null && src.ownerOf.containsKey(sid) && !src.ownerOf.get(sid).equals(item.itemId())) {
                        findings.add(at(id(), Severity.ERROR, item, "Cites " + sid + " from " + src.ownerOf.get(sid)
                                + ", but sits under " + item.itemId() + ".").withFix("Move it to " + src.ownerOf.get(sid) + " or remove it."));
                    }
                }
            }
            if (ctx.tailored() != null) {
                MasterResume m = ctx.master();
                Set<String> work = new HashSet<>();
                m.work().forEach(w -> work.add(w.id()));
                m.volunteer().forEach(w -> work.add(w.id()));
                Set<String> projects = new HashSet<>();
                m.projects().forEach(p -> projects.add(p.id()));
                Set<String> skills = new HashSet<>();
                m.skills().forEach(s -> skills.add(s.id()));
                ctx.tailored().experience().stream().filter(e -> !work.contains(e.workId())).forEach(e -> findings.add(
                        Finding.of(id(), Severity.ERROR, "Experience", e.workId() + " is not an entry in your master.")));
                ctx.tailored().projects().stream().filter(p -> !projects.contains(p.projectId())).forEach(p -> findings.add(
                        Finding.of(id(), Severity.ERROR, "Projects", p.projectId() + " is not a project in your master.")));
                ctx.tailored().skillsLines().forEach(line -> line.items().stream().filter(i -> !skills.contains(i.skillId()))
                        .forEach(i -> findings.add(Finding.of(id(), Severity.ERROR, "Skills", "Skill \"" + i.name() + "\" ("
                                + i.skillId() + ") is not in your master.").withFix("Add it to your master only if it is true."))));
            }
            return findings;
        }
    }

    /**
     * TRUTH-02 ERROR: every number in generated text appears in its cited sources' text or metrics.
     * Normalised: "35 %" = "35%", "1,200" = "1200", "S$5k" = "5000", "2x" = "2". Version numbers that are part
     * of a skill name ("Java 21", "OAuth 2.0") are not claims and are skipped. Basis: P1.
     */
    @Component
    public static class Truth02Numbers implements Check {
        static final Pattern NUMBER = Pattern.compile(
                "(?<![\\p{L}\\p{N}.])(S\\$|US\\$|\\$)?\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?\\s?(%|k|K|m|M|x|X)?(?![\\p{L}\\p{N}])");

        private final KeywordAnalyzer keywords;

        public Truth02Numbers(KeywordAnalyzer keywords) {
            this.keywords = keywords;
        }

        public String id() { return "TRUTH-02"; }
        public Category category() { return Category.TRUTH; }
        public Set<CheckMode> modes() { return GENERATED; }
        public List<Finding> run(CheckContext ctx) {
            TruthSources src = new TruthSources(ctx);
            Set<String> versioned = versionedNames(ctx.master());
            List<Finding> findings = new ArrayList<>();
            for (Item item : src.items) {
                Set<String> allowed = numbers(src.sourceText(item), Set.of());
                Set<String> missing = new LinkedHashSet<>();
                for (String n : numbers(item.text(), versioned)) {
                    if (!allowed.contains(n)) {
                        missing.add(n);
                    }
                }
                if (!missing.isEmpty()) {
                    findings.add(at(id(), Severity.ERROR, item, "Number" + (missing.size() > 1 ? "s " : " ") + String.join(", ", missing)
                            + " not found in the cited sources.").withFix(allowed.isEmpty() ? "Remove the number."
                            : "Use only numbers from the sources: " + String.join(", ", allowed) + "."));
                }
            }
            return findings;
        }

        /** Names with version numbers: master skills plus every dictionary variant containing a digit. */
        private Set<String> versionedNames(MasterResume m) {
            Set<String> names = new HashSet<>();
            m.skills().forEach(s -> {
                if (s.name().matches(".*\\d.*")) {
                    names.add(s.name().toLowerCase(Locale.ROOT));
                }
            });
            keywords.dictionary().entries().forEach(e -> e.variants().stream().filter(v -> v.matches(".*\\d.*"))
                    .forEach(v -> names.add(v.toLowerCase(Locale.ROOT))));
            return names;
        }

        /** Normalised numeric values in text, skipping those that end a versioned name ("Java 21"). */
        public static Set<String> numbers(String text, Set<String> versioned) {
            Set<String> values = new LinkedHashSet<>();
            if (text == null) {
                return values;
            }
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                if (!versioned.isEmpty() && isVersion(text, m.start(2), m.end(), versioned)) {
                    continue;
                }
                BigDecimal value = new BigDecimal(m.group(2).replace(",", "") + (m.group(3) == null ? "" : m.group(3)));
                String suffix = m.group(4) == null ? "" : m.group(4).toLowerCase(Locale.ROOT);
                if (suffix.equals("k")) {
                    value = value.multiply(BigDecimal.valueOf(1000));
                } else if (suffix.equals("m") && m.group(1) != null) {
                    value = value.multiply(BigDecimal.valueOf(1_000_000)); // "S$2m"; a bare "5m" could be minutes
                }
                values.add(value.stripTrailingZeros().toPlainString());
            }
            return values;
        }

        private static boolean isVersion(String text, int numberStart, int numberEnd, Set<String> versioned) {
            int wordStart = numberStart - 1;
            while (wordStart > 0 && (text.charAt(wordStart) == ' ' || text.charAt(wordStart) == '/')) {
                wordStart--;
            }
            while (wordStart > 0 && (Character.isLetterOrDigit(text.charAt(wordStart - 1)) || text.charAt(wordStart - 1) == '.')) {
                wordStart--;
            }
            String phrase = text.substring(Math.max(0, wordStart), numberEnd).toLowerCase(Locale.ROOT).strip();
            return versioned.contains(phrase);
        }
    }

    /**
     * TRUTH-03 ERROR: every dictionary skill named in generated text appears in the cited sources, or is a
     * master skill whose evidence includes a cited source. In a cover letter, a sentence that says the user has
     * not used something ("I have not used Kafka in production") may name it. Basis: P1, P3.
     */
    @Component
    public static class Truth03Skills implements Check {
        static final Pattern NEGATED = Pattern.compile(
                "\\b(not|never|haven't|hasn't|yet to|no experience|keen to learn|learning|want to learn|would like to learn)\\b",
                Pattern.CASE_INSENSITIVE);

        private final KeywordAnalyzer keywords;

        public Truth03Skills(KeywordAnalyzer keywords) {
            this.keywords = keywords;
        }

        public String id() { return "TRUTH-03"; }
        public Category category() { return Category.TRUTH; }
        public Set<CheckMode> modes() { return GENERATED; }
        public List<Finding> run(CheckContext ctx) {
            TruthSources src = new TruthSources(ctx);
            List<Finding> findings = new ArrayList<>();
            for (Item item : src.items) {
                String sources = src.sourceText(item);
                String checked = withoutNames(ctx, ctx.mode() == CheckMode.COVER_LETTER ? withoutNegatedSentences(item.text()) : item.text());
                Set<String> unsupported = new LinkedHashSet<>();
                for (Term term : KeywordMatcher.longestMatches(keywords.dictionaryTerms(), checked)) {
                    if (!term.foundIn(sources) && !evidencedSkill(ctx.master(), term, item.sourceIds())) {
                        unsupported.add(term.label());
                    }
                }
                if (!unsupported.isEmpty()) {
                    findings.add(at(id(), Severity.ERROR, item, "Unsupported skill" + (unsupported.size() > 1 ? "s: " : ": ")
                            + String.join(", ", unsupported) + " (not in the cited sources).")
                            .withFix("Remove it, or cite the master highlight that shows it."));
                }
            }
            return findings;
        }

        /**
         * Removes organisation and project names: "Example Fintech" names an employer, it does not claim the
         * skill "Fintech". Longest names first, so "Acme Cloud Pte Ltd" goes before "Acme Cloud".
         */
        static String withoutNames(CheckContext ctx, String text) {
            List<String> names = new ArrayList<>();
            MasterResume m = ctx.master();
            m.work().forEach(w -> names.add(w.name()));
            m.volunteer().forEach(w -> names.add(w.name()));
            m.projects().forEach(p -> names.add(p.name()));
            m.education().forEach(e -> names.add(e.institution()));
            m.certificates().forEach(c -> names.add(c.issuer()));
            if (ctx.job() != null) {
                names.add(ctx.job().company());
            }
            new ArrayList<>(names).forEach(n -> names.add(TextTools.shortCompanyName(n)));
            String out = text;
            for (String name : names.stream().filter(n -> n != null && n.length() > 2).distinct()
                    .sorted((a, b) -> b.length() - a.length()).toList()) {
                out = TextTools.phrasePattern(name).matcher(out).replaceAll(" ");
            }
            return out;
        }

        private static boolean evidencedSkill(MasterResume m, Term term, List<String> cited) {
            return m.skills().stream().anyMatch(s -> term.foundIn(s.name()) && s.evidenceIds().stream().anyMatch(cited::contains));
        }

        private static String withoutNegatedSentences(String text) {
            return String.join(" ", TextTools.sentences(text).stream().filter(s -> !NEGATED.matcher(s).find()).toList());
        }
    }

    /**
     * TRUTH-04 ERROR: capitalised names and product names (not at the start of a sentence, and not dictionary
     * skills, action verbs or common acronyms) must appear somewhere in the master (or, in a cover letter, in
     * the job's facts and the user's notes). Catches invented employers, clients and products. Basis: P1.
     */
    @Component
    public static class Truth04ProperNouns implements Check {
        static final Pattern CAPITALISED = Pattern.compile("(?<![\\p{L}\\p{N}])[A-Z][\\p{L}\\p{N}+#&'’.-]*[\\p{L}\\p{N}+#]|(?<![\\p{L}\\p{N}])[A-Z](?![\\p{L}\\p{N}])");
        static final Set<String> COMMON = Set.of("I", "A", "An", "The", "And", "Or", "Of", "For", "With", "In", "On", "At", "To",
                "By", "From", "As", "Via", "Per", "Over", "Into", "Using", "Through", "My", "Our", "This", "That", "These",
                "It", "Its", "We", "You", "Your", "Dear", "Hiring", "Manager", "Team", "Yours", "Sincerely",
                "API", "APIs", "UI", "UX", "QA", "SDK", "CLI", "ETL", "MVP", "KPI", "KPIs", "ROI", "SLA", "SLO", "OKR", "OKRs",
                "B2B", "B2C", "SaaS", "PaaS", "IaaS", "PoC", "POC", "CEO", "CTO", "CFO", "PM", "HR", "IT", "ICT", "R&D", "GPA",
                "Q1", "Q2", "Q3", "Q4", "AM", "OS", "PR", "PRs", "ID", "IDs", "URL", "URLs", "PDF", "CSV", "JSON",
                "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
                "November", "December", "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
                "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Present", "Singapore");

        private final KeywordAnalyzer keywords;

        public Truth04ProperNouns(KeywordAnalyzer keywords) {
            this.keywords = keywords;
        }

        public String id() { return "TRUTH-04"; }
        public Category category() { return Category.TRUTH; }
        public Set<CheckMode> modes() { return GENERATED; }
        public List<Finding> run(CheckContext ctx) {
            TruthSources src = new TruthSources(ctx);
            String allowedText = ctx.masterText() + "\n" + src.extraAllowed;
            List<Finding> findings = new ArrayList<>();
            for (Item item : src.items) {
                String text = blankOutDictionaryTerms(item.text());
                Set<String> unknown = new LinkedHashSet<>();
                for (String sentence : sentencesWithOffsets(text)) {
                    Matcher m = CAPITALISED.matcher(sentence);
                    boolean first = true;
                    while (m.find()) {
                        String word = m.group().replaceAll("[.'’]+$", "");
                        boolean sentenceStart = first && sentence.substring(0, m.start()).isBlank();
                        first = false;
                        if (sentenceStart || COMMON.contains(word) || ActionVerbs.get().isActionVerb(word)
                                || keywords.dictionary().lookup(word).isPresent() || word.length() < 2) {
                            continue;
                        }
                        if (!TextTools.containsPhrase(allowedText, word)) {
                            unknown.add(word);
                        }
                    }
                }
                if (!unknown.isEmpty()) {
                    findings.add(at(id(), Severity.ERROR, item, "Name" + (unknown.size() > 1 ? "s " : " ")
                            + String.join(", ", unknown) + " not found anywhere in your master.")
                            .withFix("Remove it, or add it to your master if it is true."));
                }
            }
            return findings;
        }

        private String blankOutDictionaryTerms(String text) {
            String result = text;
            for (Term term : keywords.dictionaryTerms()) {
                if (term.foundIn(result)) {
                    for (Pattern p : term.patterns()) {
                        result = p.matcher(result).replaceAll(r -> " ".repeat(r.group().length()));
                    }
                }
            }
            return result;
        }

        private static List<String> sentencesWithOffsets(String text) {
            return List.of(text.split("(?<=[.!?:])\\s+"));
        }
    }

    /**
     * TRUTH-05 ERROR: structural facts in the rendered resume (name, employer, title, location, dates,
     * institution, degree) equal the master exactly. The renderer guarantees this; the rule asserts it (P2).
     */
    @Component
    public static class Truth05Structure implements Check {
        public String id() { return "TRUTH-05"; }
        public Category category() { return Category.TRUTH; }
        public Set<CheckMode> modes() { return RESUME_ONLY; }
        public List<Finding> run(CheckContext ctx) {
            ResumeDocument doc = ctx.document();
            if (doc == null) {
                return List.of();
            }
            MasterResume m = ctx.master();
            List<Finding> findings = new ArrayList<>();
            if (!m.basics().name().equals(doc.header().name())) {
                findings.add(Finding.of(id(), Severity.ERROR, "header", "Name differs from the master."));
            }
            for (ResumeDocument.Section section : doc.sections()) {
                for (ResumeDocument.Entry e : section.entries()) {
                    String expected = expected(m, e.id());
                    if (expected == null) {
                        findings.add(Finding.of(id(), Severity.ERROR, section.heading(), e.id() + " is not in your master."));
                    } else if (!expected.equals(actual(e))) {
                        findings.add(Finding.of(id(), Severity.ERROR, section.heading(), e.id() + " heading differs from the master.")
                                .withEvidence(actual(e) + " vs " + expected));
                    }
                }
            }
            return findings;
        }

        private static String actual(ResumeDocument.Entry e) {
            return e.title() + "|" + e.organisation() + "|" + e.location() + "|" + e.dates();
        }

        private static String expected(MasterResume m, String id) {
            for (var w : m.work()) if (w.id().equals(id)) return w.position() + "|" + w.name() + "|" + w.location() + "|" + ResumeDates.range(w.startDate(), w.endDate());
            for (var w : m.volunteer()) if (w.id().equals(id)) return w.position() + "|" + w.name() + "|" + w.location() + "|" + ResumeDates.range(w.startDate(), w.endDate());
            for (var p : m.projects()) if (p.id().equals(id)) return p.name() + "|" + p.role() + "|null|" + ResumeDates.range(p.startDate(), p.endDate());
            for (var e : m.education()) if (e.id().equals(id)) return (e.area() == null ? e.studyType() : e.studyType() + " (" + e.area() + ")")
                    + "|" + e.institution() + "|null|" + ResumeDates.range(e.startDate(), e.endDate());
            return null;
        }
    }

    /**
     * TRUTH-06 WARN: scope inflation. Words like "led", "managed", "owned", "architected", "spearheaded" or a
     * team size, when the cited source does not say so (often it says "contributed" or "supported"). Shows the
     * source next to the bullet. Basis: P1.
     */
    @Component
    public static class Truth06Inflation implements Check {
        static final Pattern INFLATION = Pattern.compile(
                "\\b(led|lead|leading|managed|owned|architected|spearheaded|headed|directed|oversaw|supervised)\\b"
                        + "|\\bteam of (\\d+|two|three|four|five|six|seven|eight|nine|ten)\\b|\\b\\d+-person team\\b",
                Pattern.CASE_INSENSITIVE);
        static final Pattern WEAK = Pattern.compile("\\b(contributed|supported|assisted|helped|participated|worked on|involved)\\b",
                Pattern.CASE_INSENSITIVE);

        public String id() { return "TRUTH-06"; }
        public Category category() { return Category.TRUTH; }
        public Set<CheckMode> modes() { return RESUME_ONLY; }
        public List<Finding> run(CheckContext ctx) {
            TruthSources src = new TruthSources(ctx);
            List<Finding> findings = new ArrayList<>();
            for (Item item : src.items) {
                String sources = src.sourceText(item);
                Matcher m = INFLATION.matcher(item.text());
                Set<String> inflated = new LinkedHashSet<>();
                while (m.find()) {
                    if (!TextTools.containsPhrase(sources, m.group())) {
                        inflated.add(m.group());
                    }
                }
                if (!inflated.isEmpty()) {
                    Matcher weak = WEAK.matcher(sources);
                    String note = weak.find() ? " The source says \"" + weak.group() + "\"." : "";
                    findings.add(new Finding(id(), Severity.WARN, item.anchor(), "\"" + String.join("\", \"", inflated)
                            + "\" is not in the source." + note, "Match the source's scope.",
                            "Bullet: " + TextTools.clip(item.text(), 160) + " | Source: " + TextTools.clip(sources, 160)));
                }
            }
            return findings;
        }
    }
}

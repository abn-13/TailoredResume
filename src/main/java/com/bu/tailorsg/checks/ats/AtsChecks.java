package com.bu.tailorsg.checks.ats;

import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.Check;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.CheckThresholds;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.ats.ParserRecall.FieldCheck;
import com.bu.tailorsg.export.ExportedFile;
import com.bu.tailorsg.export.ExportedFile.FontUse;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Kind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ATS parseability (spec 6B): round-trip checks on the exported file. Each rule reads the file back the way a
 * parser would (PDFBox / POI text) and compares it with what was rendered. They only run at export time,
 * when {@link CheckContext#export()} is set.
 */
public final class AtsChecks {

    private AtsChecks() {}

    /** Spec 4: the only section headings allowed (case-insensitive). */
    public static final Set<String> ALLOWED_HEADINGS = Set.of("summary", "profile", "education", "experience", "work experience",
            "professional experience", "internship experience", "projects", "technical projects", "skills", "technical skills",
            "certifications", "awards", "leadership", "co-curricular activities", "publications", "volunteering", "languages");

    static final Set<CheckMode> EXPORT = EnumSet.of(CheckMode.EXPORT);
    static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Base class: every ATS rule needs the exported file and skips itself without one. */
    abstract static class AtsRule implements Check {
        public Category category() { return Category.ATS; }
        public Set<CheckMode> modes() { return EXPORT; }

        public final List<Finding> run(CheckContext ctx) {
            return ctx.export() == null ? List.of() : check(ctx, ctx.export(), ctx.thresholds().ats());
        }

        abstract List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t);

        Finding error(String message) { return Finding.of(id(), Severity.ERROR, "file", message); }
        Finding warn(String message) { return Finding.of(id(), Severity.WARN, "file", message); }
    }

    /**
     * ATS-01 ERROR: the text can be extracted: more than 300 characters, and at least 90% of the rendered
     * letters and digits (catches image-only or garbled output). Basis: every ATS starts from extracted text.
     */
    @Component
    public static class Ats01Extractable extends AtsRule {
        public String id() { return "ATS-01"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            int got = alnum(file.extractedText());
            int expected = alnum(ctx.text());
            if (got < t.minTextChars()) {
                return List.of(error("Only " + got + " characters can be extracted; the file may be image-only.")
                        .withFix("Export again; never upload a scanned or designed resume."));
            }
            if (expected > 0 && got < t.minTextRatio() * expected) {
                return List.of(error("Only " + Math.round(100.0 * got / expected) + "% of the text can be extracted."));
            }
            return List.of();
        }
    }

    /**
     * ATS-02 ERROR: the name is within the first 3 non-empty lines, and the email and phone can be extracted.
     * Basis: parsers take the name from the top of the first page.
     */
    @Component
    public static class Ats02Contact extends AtsRule {
        public String id() { return "ATS-02"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            List<Finding> out = new ArrayList<>();
            List<String> lines = file.extractedText().lines().map(String::strip).filter(l -> !l.isEmpty()).limit(3).toList();
            String name = ctx.master().basics().name();
            if (lines.stream().noneMatch(l -> l.contains(name))) {
                out.add(error("The name is not in the first 3 lines of the extracted text."));
            }
            String text = file.extractedText().replaceAll("\\s+", " ");
            String email = ctx.master().basics().email();
            if (email != null && !text.contains(email)) {
                out.add(error("The email " + email + " cannot be extracted intact."));
            }
            String phone = ctx.master().basics().phone();
            if (phone != null && !text.replaceAll("[\\s()-]", "").contains(phone.replaceAll("[\\s()-]", ""))) {
                out.add(error("The phone number cannot be extracted intact."));
            }
            return out;
        }
    }

    /**
     * ATS-03 ERROR: every section heading is in the extracted text, on its own line, in order, and is one of
     * the allowed headings. Basis: parsers split a resume into sections by recognised headings.
     */
    @Component
    public static class Ats03Headings extends AtsRule {
        public String id() { return "ATS-03"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            List<Finding> out = new ArrayList<>();
            List<String> lines = file.extractedText().lines().map(l -> l.strip().toLowerCase(Locale.ROOT)).toList();
            int from = 0;
            for (String heading : ctx.headings()) {
                String h = heading.toLowerCase(Locale.ROOT);
                if (!ALLOWED_HEADINGS.contains(h)) {
                    out.add(error("\"" + heading + "\" is not a standard heading.").withFix("Use one of: Summary, Education, Experience, Projects, Skills..."));
                }
                int at = indexOf(lines, h, from);
                if (at < 0) {
                    out.add(error("Heading \"" + heading + "\" is missing or out of order in the extracted text."));
                } else {
                    from = at + 1;
                }
            }
            return out;
        }

        private static int indexOf(List<String> lines, String heading, int from) {
            for (int i = from; i < lines.size(); i++) {
                if (lines.get(i).equals(heading)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * ATS-04 ERROR: for every job, the position, employer and both dates are within 400 characters of each
     * other in the extracted text, and the jobs are in source order. Basis: parsers attach dates to the
     * nearest job title; a date far away is a date lost.
     */
    @Component
    public static class Ats04Jobs extends AtsRule {
        public String id() { return "ATS-04"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            ResumeDocument doc = ctx.document();
            if (doc == null) {
                return List.of();
            }
            String text = file.extractedText().replaceAll("\\s+", " ");
            List<Finding> out = new ArrayList<>();
            int cursor = 0;
            for (var section : doc.sections()) {
                if (section.kind() != Kind.EXPERIENCE && section.kind() != Kind.VOLUNTEERING) {
                    continue;
                }
                for (Entry e : section.entries()) {
                    int at = text.indexOf(e.title(), cursor);
                    if (at < 0) {
                        out.add(error("\"" + e.title() + "\" is missing or out of order in the extracted text."));
                        continue;
                    }
                    int lo = Math.max(0, at - t.dateWindowChars());
                    String window = text.substring(lo, Math.min(text.length(), at + t.dateWindowChars()));
                    List<String> missing = new ArrayList<>();
                    for (String part : parts(e)) {
                        if (!window.contains(part)) {
                            missing.add(part);
                        }
                    }
                    if (!missing.isEmpty()) {
                        out.add(error(e.title() + ": " + String.join(", ", missing) + " not found near the job title."));
                    }
                    cursor = at + e.title().length();
                }
            }
            return out;
        }

        private static List<String> parts(Entry e) {
            List<String> parts = new ArrayList<>();
            if (e.organisation() != null) {
                parts.add(e.organisation());
            }
            if (!e.dates().isEmpty()) {
                for (String d : e.dates().split(" - ")) {
                    parts.add(d.strip());
                }
            }
            return parts;
        }
    }

    /**
     * ATS-05 WARN (ERROR below 0.75): reading order. The word sequence of the extracted text matches the
     * rendered text with a similarity of at least 0.90 (2 x longest common subsequence / total words).
     * Basis: columns and floating boxes scramble extraction order.
     */
    @Component
    public static class Ats05ReadingOrder extends AtsRule {
        public String id() { return "ATS-05"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            double ratio = similarity(words(ctx.text()), words(file.extractedText()));
            String pct = Math.round(ratio * 100) + "%";
            if (ratio < t.orderError()) {
                return List.of(error("Reading order similarity is " + pct + "; the text extracts out of order."));
            }
            if (ratio < t.orderWarn()) {
                return List.of(warn("Reading order similarity is " + pct + " (want " + Math.round(t.orderWarn() * 100) + "%)."));
            }
            return List.of();
        }

        static double similarity(List<String> a, List<String> b) {
            if (a.isEmpty() && b.isEmpty()) {
                return 1.0;
            }
            int[] prev = new int[b.size() + 1];
            int[] cur = new int[b.size() + 1];
            for (int i = 1; i <= a.size(); i++) {
                for (int j = 1; j <= b.size(); j++) {
                    cur[j] = a.get(i - 1).equals(b.get(j - 1)) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
                }
                int[] swap = prev;
                prev = cur;
                cur = swap;
            }
            return 2.0 * prev[b.size()] / (a.size() + b.size());
        }
    }

    /**
     * ATS-06 ERROR (DOCX): no tables, no text boxes or shapes, and nothing in the header or footer.
     * Basis: many parsers skip tables, text boxes and headers entirely.
     */
    @Component
    public static class Ats06DocxLayout extends AtsRule {
        public String id() { return "ATS-06"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            if (file.type() != ExportedFile.Type.DOCX) {
                return List.of();
            }
            List<Finding> out = new ArrayList<>();
            if (file.facts().tables() > 0) {
                out.add(error(file.facts().tables() + " table(s) in the DOCX."));
            }
            if (file.facts().textBoxes() > 0) {
                out.add(error(file.facts().textBoxes() + " text box(es) or shape(s) in the DOCX."));
            }
            if (!file.facts().headerFooterText().isBlank()) {
                out.add(error("Text in the page header or footer: \"" + file.facts().headerFooterText() + "\"."));
            }
            return out;
        }
    }

    /**
     * ATS-07 WARN: fonts are embedded (PDF) and on the allow-list; body text at least 10.5pt (ERROR below
     * 10pt). Basis: NUS CFG recommends 11pt; unembedded fonts can extract as garbage.
     */
    @Component
    public static class Ats07Fonts extends AtsRule {
        public String id() { return "ATS-07"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            List<Finding> out = new ArrayList<>();
            for (FontUse f : file.facts().fonts()) {
                if (file.type() == ExportedFile.Type.PDF && !f.embedded()) {
                    out.add(warn("Font " + f.name() + " is not embedded."));
                }
                String compact = f.name().replace(" ", "").toLowerCase(Locale.ROOT);
                boolean allowed = t.fonts().stream().anyMatch(a -> compact.startsWith(a.replace(" ", "").toLowerCase(Locale.ROOT)));
                if (!allowed) {
                    out.add(warn("Font " + f.name() + " is not on the allow-list " + t.fonts() + "."));
                }
            }
            double body = file.facts().bodyFontPt();
            if (body > 0 && body < t.errorBodyPt()) {
                out.add(error("Body text is " + body + "pt (minimum " + t.errorBodyPt() + "pt)."));
            } else if (body > 0 && body < t.minBodyPt()) {
                out.add(warn("Body text is " + body + "pt; use at least " + t.minBodyPt() + "pt (11pt recommended)."));
            }
            return out;
        }
    }

    /**
     * ATS-08 WARN: unusual bullet glyphs (anything but • ▪ - ‣ ◦), private-use code points (icon fonts) or
     * ligatures (ﬁ, ﬂ) in the extracted text. Basis: these extract as boxes or break words apart.
     */
    @Component
    public static class Ats08Glyphs extends AtsRule {
        static final Pattern ODD = Pattern.compile("[\\x{E000}-\\x{F8FF}\\x{FB00}-\\x{FB06}\\x{FFFD}]");
        static final Pattern LINE_START = Pattern.compile("(?m)^\\s*([^\\p{L}\\p{N}\\s(\"'+$#@&.])");
        static final Set<String> BULLETS = Set.of("•", "▪", "-", "‣", "◦");

        public String id() { return "ATS-08"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            List<Finding> out = new ArrayList<>();
            Matcher odd = ODD.matcher(file.extractedText());
            if (odd.find()) {
                out.add(warn("Characters that extract badly (icon fonts or ligatures), e.g. U+"
                        + String.format("%04X", (int) odd.group().charAt(0)) + "."));
            }
            Matcher start = LINE_START.matcher(file.extractedText());
            Set<String> glyphs = new java.util.TreeSet<>();
            while (start.find()) {
                if (!BULLETS.contains(start.group(1))) {
                    glyphs.add(start.group(1));
                }
            }
            if (!glyphs.isEmpty()) {
                out.add(warn("Non-standard bullet glyphs: " + String.join(" ", glyphs) + ".").withFix("Use the round bullet •."));
            }
            return out;
        }
    }

    /**
     * ATS-10 WARN: a heuristic OpenResume-style parser ({@link ResumeParser}) recovers at least 90% of the
     * master-sourced fields: name, email, phone, each job's title, employer and dates, each degree and
     * institution. The Export tab shows the full field table.
     */
    @Component
    public static class Ats10Parser extends AtsRule {
        private final ResumeParser parser;

        public Ats10Parser(ResumeParser parser) {
            this.parser = parser;
        }

        public String id() { return "ATS-10"; }
        List<Finding> check(CheckContext ctx, ExportedFile file, CheckThresholds.Ats t) {
            if (ctx.document() == null) {
                return List.of();
            }
            List<FieldCheck> rows = ParserRecall.compare(ctx.document(), parser.parse(file.extractedText()));
            double recall = ParserRecall.recall(rows);
            if (recall >= t.parserRecall()) {
                return List.of();
            }
            List<String> missed = rows.stream().filter(r -> !r.ok()).map(FieldCheck::field).toList();
            return List.of(new Finding(id(), Severity.WARN, "file", "A typical parser recovers " + Math.round(recall * 100)
                    + "% of your fields (want " + Math.round(t.parserRecall() * 100) + "%).",
                    "See the field table on the Export tab.", "Missed: " + String.join("; ", missed)));
        }
    }

    static int alnum(String s) {
        int n = 0;
        for (int i = 0; s != null && i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(s == null ? "" : s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}

package com.bu.tailorsg.checks.sg;

import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.Check;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.DatedEntry;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.SgPatterns;
import com.bu.tailorsg.checks.TextTools;
import com.bu.tailorsg.export.ExportedFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singapore norms (spec 6A). Each nested class is one rule and one Spring bean.
 *
 * <p>Background: Singapore's Tripartite Guidelines on Fair Employment Practices (TAFEP) say job
 * applications should not ask for age, race, gender, religion, marital status, family responsibilities or
 * photographs, and the PDPC's NRIC advisory guidelines restrict collecting NRIC numbers. University career
 * offices (e.g. NUS Centre for Future-ready Graduates) give the same advice for resumes.
 */
public final class SgChecks {

    private SgChecks() {}

    static List<Finding> matches(String id, Severity severity, Pattern pattern, String text, String message, String fix) {
        List<Finding> findings = new ArrayList<>();
        Matcher m = pattern.matcher(text == null ? "" : text);
        while (m.find()) {
            findings.add(new Finding(id, severity, "", message, fix, m.group().strip()));
        }
        return findings;
    }

    /** SG-01 ERROR: NRIC/FIN number. Basis: PDPC Advisory Guidelines on NRIC numbers; TAFEP. */
    @Component
    public static class Sg01NricFin implements Check {
        public String id() { return "SG-01"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            return matches(id(), Severity.ERROR, SgPatterns.NRIC_FIN, ctx.text(),
                    "NRIC/FIN number on the resume.", "Remove it. Employers should not collect NRIC at the application stage.");
        }
    }

    /** SG-02 ERROR: date of birth or age. Basis: TAFEP (age is not a selection criterion). */
    @Component
    public static class Sg02DobAge implements Check {
        public String id() { return "SG-02"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            return matches(id(), Severity.ERROR, SgPatterns.DOB_OR_AGE, ctx.text(),
                    "Date of birth or age on the resume.", "Remove it (TAFEP: do not include age or date of birth).");
        }
    }

    /**
     * SG-03 ERROR: marital status, religion, race, gender or children fields, and words like "married" or
     * "single" in the header block. Basis: TAFEP.
     */
    @Component
    public static class Sg03PersonalFields implements Check {
        public String id() { return "SG-03"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>(matches(id(), Severity.ERROR, SgPatterns.PERSONAL_FIELD_LINE, ctx.text(),
                    "Personal-particulars field (marital status, religion, race, gender or children).", "Remove the whole line."));
            findings.addAll(matches(id(), Severity.ERROR, SgPatterns.MARITAL_WORDS, ctx.headerText(),
                    "Marital status in the header.", "Remove it."));
            return findings;
        }
    }

    /** SG-04 ERROR: an image or photo in the exported file. Basis: TAFEP (no photographs); ATS cannot read images. */
    @Component
    public static class Sg04Images implements Check {
        public String id() { return "SG-04"; }
        public Category category() { return Category.SG; }
        public Set<CheckMode> modes() { return EnumSet.of(CheckMode.EXPORT); }
        public List<Finding> run(CheckContext ctx) {
            ExportedFile file = ctx.export();
            if (file == null) {
                return List.of();
            }
            int images = file.type() == ExportedFile.Type.PDF ? pdfImages(file.bytes()) : docxImages(file.bytes());
            return images == 0 ? List.of() : List.of(Finding.of(id(), Severity.ERROR, "file",
                    images + " image(s) in the " + file.type() + ".").withFix("Remove photos and images; use text only."));
        }

        static int pdfImages(byte[] bytes) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                int count = 0;
                for (PDPage page : doc.getPages()) {
                    for (COSName name : page.getResources().getXObjectNames()) {
                        if (page.getResources().getXObject(name) instanceof PDImageXObject) {
                            count++;
                        }
                    }
                }
                return count;
            } catch (IOException e) {
                return 0; // ATS-01 reports unreadable files
            }
        }

        static int docxImages(byte[] bytes) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                return doc.getAllPictures().size();
            } catch (IOException e) {
                return 0;
            }
        }
    }

    /** SG-05 WARN: a full residential address. Basis: spec 6A (keep the city only); privacy. */
    @Component
    public static class Sg05Address implements Check {
        public String id() { return "SG-05"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            return matches(id(), Severity.WARN, SgPatterns.RESIDENTIAL_ADDRESS, ctx.text(),
                    "Looks like a full residential address.", "Keep \"Singapore\" only.");
        }
    }

    /** SG-06 WARN: salary mentioned. Basis: spec 6A; salary belongs in the application form, not the resume. */
    @Component
    public static class Sg06Salary implements Check {
        static final Pattern ANYWHERE = Pattern.compile("\\b(expected|current|last[- ]drawn)\\s+salary\\b", Pattern.CASE_INSENSITIVE);
        static final Pattern HEADER = Pattern.compile("\\bsalary\\b|\\bSGD\\s?\\d|S\\$\\s?\\d", Pattern.CASE_INSENSITIVE);

        public String id() { return "SG-06"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>(matches(id(), Severity.WARN, ANYWHERE, ctx.text(),
                    "Salary mentioned.", "Leave salary out of the resume."));
            if (findings.isEmpty()) {
                findings.addAll(matches(id(), Severity.WARN, HEADER, ctx.headerText(),
                        "Salary in the personal details.", "Leave salary out of the resume."));
            }
            return findings;
        }
    }

    /** SG-07 WARN: "References available upon request" or referee details. Basis: spec 6A; NUS CFG guide. */
    @Component
    public static class Sg07References implements Check {
        static final Pattern AVAILABLE = Pattern.compile("references\\s+(are\\s+)?available\\s+(up)?on\\s+request", Pattern.CASE_INSENSITIVE);
        static final Pattern REFEREE = Pattern.compile("(?m)^\\s*(referees?|references)\\s*[:\\-]?.*$", Pattern.CASE_INSENSITIVE);

        public String id() { return "SG-07"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>(matches(id(), Severity.WARN, AVAILABLE, ctx.text(),
                    "\"References available upon request\" wastes a line.", "Remove it; employers ask when they need referees."));
            if (findings.isEmpty()) {
                findings.addAll(matches(id(), Severity.WARN, REFEREE, ctx.text(),
                        "Referee details on the resume.", "Remove referee names and contacts; give them when asked."));
            }
            return findings;
        }
    }

    /** SG-08 WARN: a standalone "Nationality:" field. INFO: work-authorisation line vs the user's setting. */
    @Component
    public static class Sg08Nationality implements Check {
        static final Pattern NATIONALITY = Pattern.compile("(?im)^\\s*nationality\\s*[:\\-].*$");

        public String id() { return "SG-08"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>(matches(id(), Severity.WARN, NATIONALITY, ctx.text(),
                    "Standalone nationality field.", "Remove it. If relevant, a neutral work-authorisation line says what employers need."));
            var auth = ctx.master().basics().workAuthorization();
            if (auth != null && ctx.mode() != CheckMode.MASTER) {
                boolean shown = ctx.workAuthLine() != null;
                String state = shown ? "shown (\"" + ctx.workAuthLine() + "\")" : "not shown";
                String setting = ctx.showWorkAuth() ? "on" : "off";
                findings.add(Finding.of(id(), Severity.INFO, "header", "Work-authorisation line is " + state
                        + "; the option for this application is " + setting + "."
                        + (shown != ctx.showWorkAuth() ? " They differ: check the option." : "")));
            }
            return findings;
        }
    }

    /** SG-09 WARN: more pages than the target; ERROR: more than 2 pages. Basis: NUS CFG (1 page for fresh graduates, 2 at most). */
    @Component
    public static class Sg09Pages implements Check {
        public String id() { return "SG-09"; }
        public Category category() { return Category.SG; }
        public Set<CheckMode> modes() { return EnumSet.of(CheckMode.RESUME, CheckMode.EXPORT); }
        public List<Finding> run(CheckContext ctx) {
            int pages = ctx.pages();
            String how = ctx.pageCount() != null ? "" : " (estimated from " + TextTools.wordCount(ctx.document() != null
                    ? ctx.document().bodyText() : ctx.text()) + " words)";
            if (pages > 2) {
                return List.of(Finding.of(id(), Severity.ERROR, "page", pages + " pages" + how + "; more than 2 is too long.")
                        .withFix("Cut the least relevant bullets and projects."));
            }
            if (pages > ctx.pageTarget()) {
                return List.of(Finding.of(id(), Severity.WARN, "page", pages + " pages" + how + ", target is " + ctx.pageTarget() + ".")
                        .withFix("Trim bullets on older or less relevant entries."));
            }
            return List.of();
        }
    }

    /**
     * SG-10 WARN: dates in more than one format (spec: MMM YYYY only). ERROR: Experience or Education not in
     * reverse-chronological order. Basis: NUS CFG guide; recruiter scanning convention.
     */
    @Component
    public static class Sg10Dates implements Check {
        static final Map<String, Pattern> OTHER_STYLES = new LinkedHashMap<>();
        static {
            OTHER_STYLES.put("full month name", Pattern.compile("\\b(January|February|March|April|June|July|August|September|October|November|December),? \\d{4}\\b"));
            OTHER_STYLES.put("abbreviation with a full stop or 'Sept'", Pattern.compile("\\b(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\. \\d{4}\\b|\\bSept \\d{4}\\b"));
            OTHER_STYLES.put("MM/YYYY", Pattern.compile("\\b(0?[1-9]|1[0-2])/(19|20)\\d{2}\\b"));
            OTHER_STYLES.put("YYYY-MM", Pattern.compile("\\b(19|20)\\d{2}-(0[1-9]|1[0-2])\\b"));
            OTHER_STYLES.put("two-digit year", Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s?['’]\\d{2}\\b"));
        }

        public String id() { return "SG-10"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            List<Finding> findings = new ArrayList<>();
            Set<String> examples = new LinkedHashSet<>();
            OTHER_STYLES.forEach((style, pattern) -> {
                Matcher m = pattern.matcher(ctx.text());
                while (m.find()) {
                    examples.add("'" + m.group() + "' (" + style + ")");
                }
            });
            if (!examples.isEmpty()) {
                findings.add(Finding.of(id(), Severity.WARN, "", "Dates are not all in MMM YYYY format.")
                        .withFix("Write dates as 'May 2024'.").withEvidence(String.join(", ", examples)));
            }
            Map<String, List<DatedEntry>> bySection = new LinkedHashMap<>();
            ctx.entries().forEach(e -> bySection.computeIfAbsent(e.section(), k -> new ArrayList<>()).add(e));
            bySection.forEach((section, list) -> {
                for (int i = 1; i < list.size(); i++) {
                    if (sortKey(list.get(i)).compareTo(sortKey(list.get(i - 1))) > 0) {
                        findings.add(Finding.of(id(), Severity.ERROR, section, section + " is not in reverse-chronological order: \""
                                        + list.get(i - 1).label() + "\" comes before the more recent \"" + list.get(i).label() + "\".")
                                .withFix("Put the most recent entry first."));
                    }
                }
            });
            return findings;
        }

        /** Current roles first, then by end date, then by start date. */
        static String sortKey(DatedEntry e) {
            String end = e.endDate() == null ? "9999-99" : e.endDate();
            String start = e.startDate() == null ? "0000-00" : e.startDate();
            return end + "|" + start;
        }
    }

    /** SG-11 WARN: British and American spellings mixed. Default preference: British (Singapore English). */
    @Component
    public static class Sg11Spelling implements Check {
        /** Verb stems that take -ise (British) / -ize (American). */
        static final List<String> ISE_STEMS = List.of("organ", "optim", "util", "priorit", "standard", "minim", "maxim",
                "visual", "custom", "recogn", "real", "summar", "container", "parallel", "central", "normal", "modern",
                "digit", "synchron", "initial", "serial", "author", "categor", "special", "capital", "monet", "stabil",
                "virtual", "local", "personal", "formal", "operational");
        /** Other British / American pairs, as regex alternatives. */
        static final List<String[]> PAIRS = List.of(
                new String[] {"colour\\w*", "color\\w*"}, new String[] {"behaviour\\w*", "behavior\\w*"},
                new String[] {"favour\\w*", "favor\\w*"}, new String[] {"labour\\w*", "labor\\w*"},
                new String[] {"honour\\w*", "honor\\w*"}, new String[] {"centre[sd]?", "center(s|ed)?"},
                new String[] {"modelling|modelled", "modeling|modeled"}, new String[] {"travelling|travelled", "traveling|traveled"},
                new String[] {"cancelled|cancelling", "canceled|canceling"}, new String[] {"labelled|labelling", "labeled|labeling"},
                new String[] {"catalogue[sd]?", "catalog(s|ed)?"}, new String[] {"defence", "defense"},
                new String[] {"analys(e|ed|es|ing)", "analyz(e|ed|es|ing)"}, new String[] {"enrolment", "enrollment"},
                new String[] {"ageing", "aging"}, new String[] {"fulfil(s|ment)?", "fulfill(s|ment)?"});

        public String id() { return "SG-11"; }
        public Category category() { return Category.SG; }
        public List<Finding> run(CheckContext ctx) {
            Set<String> british = new LinkedHashSet<>();
            Set<String> american = new LinkedHashSet<>();
            String suffix = "(e|ed|es|ing|ation|ations|er|ers)";
            for (String stem : ISE_STEMS) {
                collect(Pattern.compile("\\b" + stem + "is" + suffix + "\\b", Pattern.CASE_INSENSITIVE), ctx.text(), british);
                collect(Pattern.compile("\\b" + stem + "iz" + suffix + "\\b", Pattern.CASE_INSENSITIVE), ctx.text(), american);
            }
            for (String[] pair : PAIRS) {
                collect(Pattern.compile("\\b(" + pair[0] + ")\\b", Pattern.CASE_INSENSITIVE), ctx.text(), british);
                collect(Pattern.compile("\\b(" + pair[1] + ")\\b", Pattern.CASE_INSENSITIVE), ctx.text(), american);
            }
            if (!british.isEmpty() && !american.isEmpty()) {
                return List.of(Finding.of(id(), Severity.WARN, "", "British and American spellings are mixed.")
                        .withFix("Use " + ("american".equals(ctx.spelling()) ? "American" : "British") + " spelling throughout.")
                        .withEvidence("British: " + String.join(", ", british) + "; American: " + String.join(", ", american)));
            }
            if (!"american".equals(ctx.spelling()) && !american.isEmpty()) {
                return List.of(Finding.of(id(), Severity.INFO, "", "American spelling, while your master prefers British.")
                        .withEvidence(String.join(", ", american)));
            }
            return List.of();
        }

        private static void collect(Pattern pattern, String text, Set<String> into) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                into.add(m.group().toLowerCase());
            }
        }
    }
}

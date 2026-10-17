package com.bu.tailorsg.checks.ats;

import com.bu.tailorsg.checks.ats.ResumeParser.Item;
import com.bu.tailorsg.checks.ats.ResumeParser.ParsedResume;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Kind;
import com.bu.tailorsg.render.ResumeDocument.Section;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * ATS-10's comparison: every field that came from the master (name, email, phone, each job's title,
 * employer and dates, each degree and institution) against what the parser recovered. The rows are the
 * "what an ATS would likely extract" table on the Export tab.
 */
public final class ParserRecall {

    private ParserRecall() {}

    public record FieldCheck(String field, String expected, String extracted, boolean ok) {}

    public static List<FieldCheck> compare(ResumeDocument doc, ParsedResume parsed) {
        List<FieldCheck> rows = new ArrayList<>();
        rows.add(row("Name", doc.header().name(), parsed.name()));
        String email = doc.header().contacts().stream().map(ResumeDocument.Contact::label).filter(l -> l.contains("@")).findFirst().orElse(null);
        if (email != null) {
            rows.add(row("Email", email, parsed.email()));
        }
        String phone = doc.header().contacts().stream().filter(c -> c.href() != null && c.href().startsWith("tel:"))
                .map(ResumeDocument.Contact::label).findFirst().orElse(null);
        if (phone != null) {
            rows.add(new FieldCheck("Phone", phone, nz(parsed.phone()), digits(phone).equals(digits(nz(parsed.phone())))));
        }
        for (Section s : doc.sections()) {
            boolean jobs = s.kind() == Kind.EXPERIENCE || s.kind() == Kind.VOLUNTEERING;
            boolean school = s.kind() == Kind.EDUCATION;
            if (!jobs && !school) {
                continue;
            }
            List<Item> found = jobs ? parsed.experience() : parsed.education();
            for (Entry e : s.entries()) {
                Item match = found.stream().filter(i -> same(i.title(), e.title())).findFirst()
                        .orElse(found.stream().filter(i -> same(i.org(), e.organisation())).findFirst().orElse(null));
                String label = jobs ? e.title() : e.organisation();
                rows.add(field(jobs ? "Job title" : "Degree", e.title(), match, Item::title, label));
                rows.add(field(jobs ? "Employer" : "Institution", e.organisation(), match, Item::org, label));
                if (jobs && !e.dates().isEmpty()) {
                    rows.add(field("Dates", e.dates(), match, Item::dates, label));
                }
            }
        }
        return rows;
    }

    public static double recall(List<FieldCheck> rows) {
        return rows.isEmpty() ? 1.0 : rows.stream().filter(FieldCheck::ok).count() / (double) rows.size();
    }

    private static FieldCheck field(String name, String expected, Item match, Function<Item, String> getter, String which) {
        String got = match == null ? "" : nz(getter.apply(match));
        return new FieldCheck(name + " (" + which + ")", nz(expected), got, same(got, expected));
    }

    private static FieldCheck row(String field, String expected, String got) {
        return new FieldCheck(field, nz(expected), nz(got), same(got, expected));
    }

    static boolean same(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        return !x.isEmpty() && x.equals(y);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[–—]", "-").replaceAll("\\s*-\\s*", " - ")
                .replaceAll("\\s+", " ").replaceAll("^[\\s,.;:]+|[\\s,.;:]+$", "");
    }

    private static String digits(String s) {
        return s.replaceAll("\\D", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

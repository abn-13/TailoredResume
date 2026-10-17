package com.bu.tailorsg.checks.ats;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OpenResume-style parser in four steps (open-resume.com's parser, simplified):
 * <ol>
 *   <li>text to lines;</li>
 *   <li>lines to sections, by heading lines (a known heading, or a short ALL-CAPS line);</li>
 *   <li>sections to subsections: a new job or degree starts at a non-bullet line after bullets, or at a
 *       second line with a date range;</li>
 *   <li>features: name (first short line of letters), email and phone by pattern; for each subsection, the
 *       date range, and title / organisation from the headline split at commas.</li>
 * </ol>
 * Deliberately naive, like real parsers: if this cannot find a field, many ATSs will not either.
 */
@Component
public class HeuristicResumeParser implements ResumeParser {

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static final Pattern PHONE = Pattern.compile("(?<![\\d])(\\+?\\d[\\d\\s()-]{6,16}\\d)(?![\\d])");
    static final String MONTH = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?";
    static final Pattern DATE_RANGE = Pattern.compile("((?:" + MONTH + "\\s+)?\\d{4})\\s*(?:-|–|—|to)\\s*((?:" + MONTH
            + "\\s+)?\\d{4}|Present|Current|Now)", Pattern.CASE_INSENSITIVE);
    static final Pattern BULLET = Pattern.compile("^[•▪‣◦●·\\-*]\\s*");
    static final Pattern CONTINUATION = Pattern.compile("^[\\p{Ll}\\d(]");
    static final Pattern DETAIL = Pattern.compile("^[A-Z][A-Za-z /]{1,25}:\\s");
    static final Pattern NAME = Pattern.compile("^[\\p{L}][\\p{L}'.-]*(?:\\s+[\\p{L}][\\p{L}'.-]*){0,4}$");

    @Override
    public ParsedResume parse(String text) {
        List<String> lines = text == null ? List.of() : text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();

        // Step 2: sections
        List<String> headings = new ArrayList<>();
        List<List<String>> bodies = new ArrayList<>();
        List<String> profile = new ArrayList<>();
        List<String> current = profile;
        for (String line : lines) {
            if (isHeading(line)) {
                headings.add(line.replaceAll(":$", ""));
                current = new ArrayList<>();
                bodies.add(current);
            } else {
                current.add(line);
            }
        }

        String name = profile.stream().filter(l -> NAME.matcher(l).matches() && l.split("\\s+").length >= 2).findFirst().orElse(null);
        String email = find(EMAIL, text);
        String phone = profile.stream().map(l -> find(PHONE, l)).filter(p -> p != null && p.replaceAll("\\D", "").length() >= 8)
                .findFirst().orElse(null);

        List<Item> experience = new ArrayList<>();
        List<Item> education = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            String h = headings.get(i).toLowerCase(Locale.ROOT);
            if (h.contains("experience") || h.equals("volunteering") || h.equals("leadership")) {
                experience.addAll(items(bodies.get(i)));
            } else if (h.equals("education")) {
                education.addAll(items(bodies.get(i)));
            }
        }
        return new ParsedResume(name, email, phone, experience, education, headings);
    }

    static boolean isHeading(String line) {
        String l = line.replaceAll(":$", "").strip();
        if (AtsChecks.ALLOWED_HEADINGS.contains(l.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return l.length() <= 30 && l.equals(l.toUpperCase(Locale.ROOT)) && l.matches("[A-Z &/-]{4,}");
    }

    /**
     * Step 3 and 4 for one section. Rules, in order, for each line:
     * <ul>
     *   <li>a bullet line belongs to the current item;</li>
     *   <li>after bullets, a line starting in lower case or with a digit continues the previous bullet
     *       (a wrapped line);</li>
     *   <li>"Label: value" lines (Grade:, Relevant courses:) are details of the current item;</li>
     *   <li>any other line after bullets, or after the current item's date line, starts the next item.</li>
     * </ul>
     */
    static List<Item> items(List<String> body) {
        List<List<String>> groups = new ArrayList<>();
        List<String> header = null;
        boolean inBullets = false;
        boolean sawDate = false;
        for (String line : body) {
            if (BULLET.matcher(line).find()) {
                inBullets = true;
                continue;
            }
            if (inBullets && CONTINUATION.matcher(line).find()) {
                continue;
            }
            if (header != null && DETAIL.matcher(line).find()) {
                continue;
            }
            if (header == null || inBullets || sawDate) {
                header = new ArrayList<>();
                groups.add(header);
                inBullets = false;
                sawDate = false;
            }
            header.add(line);
            sawDate = sawDate || DATE_RANGE.matcher(line).find();
        }
        List<Item> items = new ArrayList<>();
        for (List<String> g : groups) {
            String joined = String.join(" ", g);
            Matcher d = DATE_RANGE.matcher(joined);
            String dates = "";
            String headline = joined;
            if (d.find()) {
                dates = d.group(1) + " - " + d.group(2);
                headline = joined.substring(0, d.start()) + " " + joined.substring(d.end());
            }
            String[] parts = headline.replaceAll("\\s+", " ").strip().split("\\s*,\\s*");
            if (parts.length >= 1 && !parts[0].isBlank()) {
                items.add(new Item(parts[0].strip(), parts.length > 1 ? parts[1].strip() : "", dates));
            }
        }
        return items;
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? m.group().strip() : null;
    }
}

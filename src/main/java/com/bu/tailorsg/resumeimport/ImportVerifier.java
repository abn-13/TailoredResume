package com.bu.tailorsg.resumeimport;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic provenance check for an import (in the spirit of P1 and P4): every fact the model put
 * into the master resume should be visible in the uploaded text. Anything that is not is listed for
 * the user to check on the review page. It does not block applying, because PDF extraction can mangle
 * text, but it makes an invented or misread value hard to miss.
 *
 * <ul>
 *   <li>Names, titles, institutions, skills, certificates, awards: must appear as a phrase.</li>
 *   <li>Highlights: must appear as a phrase, or at least {@value #HIGHLIGHT_COVERAGE_PERCENT}% of their
 *       words must appear (line breaks inside bullets often break exact matches).</li>
 *   <li>Metric values: their digits must appear in the text.</li>
 * </ul>
 */
@Component
public class ImportVerifier {

    static final int HIGHLIGHT_COVERAGE_PERCENT = 90;
    static final int MAX_NOTES = 40;

    public List<String> verify(JsonNode document, String sourceText) {
        Source source = new Source(sourceText);
        List<String> notes = new ArrayList<>();
        JsonNode basics = document.path("basics");
        phrase(source, basics.path("name"), "Name", notes);
        phrase(source, basics.path("label"), "Headline", notes);
        phrase(source, basics.path("email"), "Email", notes);
        digits(source, basics.path("phone").stringValue("").replaceFirst("^\\+65", ""), "Phone", notes);

        for (String section : List.of("work", "volunteer")) {
            for (JsonNode item : document.path(section).values()) {
                String where = item.path("position").stringValue("") + " at " + item.path("name").stringValue("");
                phrase(source, item.path("name"), "Employer of " + where, notes);
                phrase(source, item.path("position"), "Job title of " + where, notes);
                highlights(source, item, where, notes);
            }
        }
        for (JsonNode item : document.path("projects").values()) {
            phrase(source, item.path("name"), "Project name", notes);
            highlights(source, item, "project " + item.path("name").stringValue(""), notes);
        }
        for (JsonNode item : document.path("education").values()) {
            phrase(source, item.path("institution"), "Institution", notes);
            phrase(source, item.path("studyType"), "Qualification", notes);
            highlights(source, item, item.path("studyType").stringValue(""), notes);
        }
        document.path("skills").values().forEach(s -> phrase(source, s.path("name"), "Skill", notes));
        document.path("certificates").values().forEach(c -> phrase(source, c.path("name"), "Certificate", notes));
        document.path("awards").values().forEach(a -> phrase(source, a.path("title"), "Award", notes));

        if (notes.size() > MAX_NOTES) {
            int extra = notes.size() - MAX_NOTES;
            notes.subList(MAX_NOTES, notes.size()).clear();
            notes.add("...and " + extra + " more values not found in the uploaded text.");
        }
        return notes;
    }

    private static void highlights(Source source, JsonNode item, String where, List<String> notes) {
        for (JsonNode h : item.path("highlights").values()) {
            String text = h.path("text").stringValue("");
            if (!source.containsPhrase(text) && source.wordCoveragePercent(text) < HIGHLIGHT_COVERAGE_PERCENT) {
                notes.add("Highlight under " + where + " does not match the uploaded text; check its wording: \""
                        + abbreviate(text) + "\"");
            }
            for (JsonNode m : h.path("metrics").values()) {
                digits(source, m.path("value").stringValue(""), "Number in highlight \"" + abbreviate(text) + "\"", notes);
            }
        }
    }

    private static void phrase(Source source, JsonNode value, String what, List<String> notes) {
        String text = value.stringValue("");
        if (!text.isBlank() && !source.containsPhrase(text)) {
            notes.add(what + " \"" + text + "\" was not found in the uploaded text. Check it.");
        }
    }

    private static void digits(Source source, String value, String what, List<String> notes) {
        String digits = value.replaceAll("\\D", "");
        if (!digits.isEmpty() && !source.digits.contains(digits)) {
            notes.add(what + ": " + value + " was not found in the uploaded text. Check it.");
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 79) + "…";
    }

    /** The uploaded text, normalised once: lower case, letters and digits only, single spaces. */
    private static final class Source {
        final String words;
        final Set<String> wordSet;
        final String digits;

        Source(String text) {
            // Re-join words hyphenated across a line break by the PDF layout: "con-\ncurrent" -> "concurrent"
            String joined = text.replaceAll("(\\p{L})-[ \\t]*\\R[ \\t]*(\\p{L})", "$1$2");
            this.words = " " + normalise(joined) + " ";
            this.wordSet = new HashSet<>(Arrays.asList(words.strip().split(" ")));
            this.digits = text.replaceAll("\\D", "");
        }

        boolean containsPhrase(String phrase) {
            String normalised = normalise(phrase);
            return normalised.isEmpty() || words.contains(" " + normalised + " ");
        }

        int wordCoveragePercent(String phrase) {
            String[] tokens = normalise(phrase).split(" ");
            if (tokens.length == 0) {
                return 100;
            }
            long found = Arrays.stream(tokens).filter(wordSet::contains).count();
            return (int) (100 * found / tokens.length);
        }

        static String normalise(String text) {
            return Normalizer.normalize(text, Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]+", " ")
                    .strip();
        }
    }
}

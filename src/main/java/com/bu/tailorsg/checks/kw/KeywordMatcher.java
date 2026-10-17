package com.bu.tailorsg.checks.kw;

import com.bu.tailorsg.jobs.JobSpec.JdSkill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds keywords in text the way spec 6C asks: case, punctuation and alias normalised, whole words or
 * phrases only, simple plurals ("microservice" matches "microservices").
 *
 * <ul>
 *   <li>Hyphens and spaces are interchangeable ("end-to-end" = "end to end").</li>
 *   <li>A term must not touch letters or digits on either side, and "C" must not match "C++" or "C#".</li>
 *   <li>Case-sensitive entries ("React", "Go") and terms of two characters or fewer are matched with
 *       their exact case, so "go live" is not the Go language.</li>
 * </ul>
 */
public final class KeywordMatcher {

    /** A keyword and every way of writing it. */
    public record Term(String label, List<Pattern> patterns) {

        public boolean foundIn(String text) {
            return text != null && patterns.stream().anyMatch(p -> p.matcher(normalise(text)).find());
        }

        /** Where the term occurs, as [start, end) offsets into the normalised text. */
        public List<int[]> spansIn(String text) {
            List<int[]> spans = new ArrayList<>();
            if (text != null) {
                String normalised = normalise(text);
                for (Pattern p : patterns) {
                    Matcher m = p.matcher(normalised);
                    while (m.find()) {
                        spans.add(new int[] {m.start(), m.end()});
                    }
                }
            }
            return spans;
        }

        public int countIn(String text) {
            if (text == null) {
                return 0;
            }
            String normalised = normalise(text);
            // Count non-overlapping matches of the longest variants first, by blanking each match out
            StringBuilder remaining = new StringBuilder(normalised);
            int count = 0;
            for (Pattern p : patterns) {
                Matcher m = p.matcher(remaining);
                List<int[]> spans = new ArrayList<>();
                while (m.find()) {
                    spans.add(new int[] {m.start(), m.end()});
                }
                for (int[] s : spans) {
                    for (int i = s[0]; i < s[1]; i++) {
                        remaining.setCharAt(i, ' ');
                    }
                }
                count += spans.size();
            }
            return count;
        }
    }

    /**
     * The terms found in text, longest match winning: "RESTful APIs" is the REST entry, not also "API design"
     * because it contains "APIs". A term is dropped when every place it occurs lies inside a longer match of
     * another term.
     */
    public static List<Term> longestMatches(List<Term> terms, String text) {
        List<Term> found = terms.stream().filter(t -> t.foundIn(text)).toList();
        Map<Term, List<int[]>> spans = new HashMap<>();
        found.forEach(t -> spans.put(t, t.spansIn(text)));
        List<Term> kept = new ArrayList<>();
        for (Term t : found) {
            boolean covered = !spans.get(t).isEmpty() && spans.get(t).stream().allMatch(s -> found.stream()
                    .filter(o -> o != t).flatMap(o -> spans.get(o).stream())
                    .anyMatch(o -> o[0] <= s[0] && o[1] >= s[1] && (o[1] - o[0]) > (s[1] - s[0])));
            if (!covered) {
                kept.add(t);
            }
        }
        return kept;
    }

    private final SkillDictionary dictionary;

    public KeywordMatcher(SkillDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /** A JD skill plus its dictionary aliases (if the term or one of its aliases is in the dictionary). */
    public Term term(JdSkill skill) {
        Set<String> exact = new LinkedHashSet<>();
        Set<String> insensitive = new LinkedHashSet<>();
        List<String> own = new ArrayList<>();
        own.add(skill.term());
        own.addAll(skill.aliases());
        var entry = own.stream().map(dictionary::lookup).flatMap(java.util.Optional::stream).findFirst();
        entry.ifPresent(e -> (e.caseSensitive() ? exact : insensitive).addAll(e.variants()));
        for (String v : own) {
            boolean cs = (entry.isPresent() && entry.get().caseSensitive()) || v.strip().length() <= 2;
            (cs ? exact : insensitive).add(v.strip());
        }
        return build(skill.term(), exact, insensitive);
    }

    /** A dictionary entry as a term (KW-02 stuffing, TRUTH-03 unsupported skills). */
    public Term term(SkillDictionary.Entry entry) {
        Set<String> exact = new LinkedHashSet<>();
        Set<String> insensitive = new LinkedHashSet<>();
        for (String v : entry.variants()) {
            (entry.caseSensitive() || v.length() <= 2 ? exact : insensitive).add(v);
        }
        return build(entry.canonical(), exact, insensitive);
    }

    private static Term build(String label, Set<String> exact, Set<String> insensitive) {
        List<Pattern> patterns = new ArrayList<>();
        // Longer variants first, so "Spring Boot" is counted before "Spring"
        exact.stream().sorted((a, b) -> b.length() - a.length()).forEach(v -> patterns.add(compile(v, true)));
        insensitive.stream().filter(v -> !exact.contains(v)).sorted((a, b) -> b.length() - a.length())
                .forEach(v -> patterns.add(compile(v, false)));
        return new Term(label, patterns);
    }

    static Pattern compile(String variant, boolean caseSensitive) {
        String v = normalise(variant).strip();
        String body = Pattern.quote(v).replace(" ", "\\E\\s+\\Q");
        boolean endsWithLetter = Character.isLetter(v.charAt(v.length() - 1));
        String plural = endsWithLetter ? "(?:s|es)?" : "";
        String regex = "(?<![\\p{L}\\p{N}])" + body + plural + "(?![\\p{L}\\p{N}+#])";
        return Pattern.compile(regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Hyphens between word characters become spaces; whitespace runs collapse. */
    static String normalise(String text) {
        return text.replaceAll("(?<=[\\p{L}\\p{N}])-(?=[\\p{L}\\p{N}])", " ").replaceAll("\\s+", " ");
    }
}

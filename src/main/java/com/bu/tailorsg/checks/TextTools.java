package com.bu.tailorsg.checks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, dependency-free text helpers shared by the rules. */
public final class TextTools {

    private TextTools() {}

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’+#./%$-]*");

    /**
     * LINT-02 "has a metric": spec regex {@code \d|%|\$|S\$|x\b|\bk\b|million|hours|ms|users|requests},
     * with {@code x\b} narrowed to "3x"-style multipliers (otherwise every word ending in x, like "Linux",
     * would count) and word boundaries added around the unit words.
     */
    public static final Pattern METRIC = Pattern.compile(
            "\\d|%|\\$|S\\$|\\b\\d+(\\.\\d+)?x\\b|\\bk\\b|\\bmillion\\b|\\bhours?\\b|\\bms\\b|\\busers\\b|\\brequests\\b",
            Pattern.CASE_INSENSITIVE);

    /** Words as a reader counts them: "p95", "CI/CD", "Node.js" and "35%" are one word each. */
    public static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(m.group().replaceAll("[.,;:]+$", ""));
        }
        return words;
    }

    public static int wordCount(String text) {
        return words(text).size();
    }

    /** First word without trailing punctuation, or "". */
    public static String firstWord(String text) {
        List<String> words = words(text);
        return words.isEmpty() ? "" : words.getFirst();
    }

    /** Splits prose into sentences on . ! ? followed by whitespace. */
    public static List<String> sentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.strip().split("(?<=[.!?])\\s+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public static boolean hasMetric(String text) {
        return text != null && METRIC.matcher(text).find();
    }

    /** Case-insensitive whole-phrase search (letters/digits must not touch the phrase on either side). */
    public static boolean containsPhrase(String text, String phrase) {
        return text != null && phrasePattern(phrase).matcher(text).find();
    }

    public static Pattern phrasePattern(String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** "Example Payments Pte Ltd" is written "Example Payments" in prose: drops the legal suffix. */
    public static String shortCompanyName(String company) {
        return company == null ? "" : company.replaceAll(
                "(?i)[\\s,]+(pte\\.?\\s*ltd\\.?|private limited|sdn\\.?\\s*bhd\\.?|ltd\\.?|inc\\.?|llc|limited|corp\\.?|corporation|co\\.)$", "").strip();
    }

    /** Shortens long evidence for display. */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }
}

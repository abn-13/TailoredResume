package com.bu.tailorsg.checks.cl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CL-01's auto-fix: replaces em dashes (U+2014) and en dashes (U+2013) without changing any words.
 * <ul>
 *   <li>a dash between numbers ("2–3 weeks") becomes "to" ("2 to 3 weeks");</li>
 *   <li>a dash before the end of a sentence (an aside: "Java — and Kotlin.") becomes a comma;</li>
 *   <li>any other dash between clauses becomes a comma;</li>
 *   <li>a pair of dashes around an aside ("Java — my strongest language — helped") becomes a pair of commas.</li>
 * </ul>
 * The UI shows before and after so the user can confirm (spec 6F: "show the diff").
 */
public final class DashFixer {

    private DashFixer() {}

    static final Pattern RANGE = Pattern.compile("(\\d)\\s*[\\u2013\\u2014]\\s*(\\d)");
    static final Pattern DASH = Pattern.compile("\\s*[\\u2013\\u2014]\\s*");

    public static String fix(String text) {
        if (text == null) {
            return null;
        }
        String out = RANGE.matcher(text).replaceAll("$1 to $2");
        Matcher m = DASH.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.start() == 0 ? "" : ", ");
        }
        m.appendTail(sb);
        // ", ," or ", ." can appear where a dash met other punctuation
        return sb.toString().replaceAll(",\\s*([,.;:!?])", "$1").replaceAll("\\s{2,}", " ").strip();
    }

    public static boolean hasDash(String text) {
        return text != null && (text.indexOf('–') >= 0 || text.indexOf('—') >= 0);
    }
}

package com.bu.tailorsg.checks;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singapore fair-employment patterns from spec section 6A, in one place so the master-resume validator
 * (Phase 1) and the SG checks on generated output (Phase 3) can never drift apart.
 *
 * <p>Where a spec regex had no word boundaries, they are added here to avoid false positives on
 * ordinary words ("st<b>orage</b> 20 GB", "A<b>dob</b>e"). Each change is noted on the constant.
 */
public final class SgPatterns {

    private SgPatterns() {}

    /**
     * SG-01: NRIC/FIN number, e.g. S1234567D. Spec regex, unchanged.
     * Basis: PDPC Advisory Guidelines on NRIC numbers (organisations should not collect them without
     * a legal need); TAFEP fair-employment guidance.
     */
    public static final Pattern NRIC_FIN = Pattern.compile("\\b[STFGM]\\d{7}[A-Z]\\b");

    /**
     * SG-02: date of birth or age. Spec regex with {@code \b} added around "DOB" and before "age".
     * Basis: TAFEP fair-employment guidance (age is not a selection criterion; do not ask for it).
     */
    public static final Pattern DOB_OR_AGE = Pattern.compile(
            "(date of birth|\\bDOB\\b|born on|\\bage:?\\s*\\d{2}\\b|\\b\\d{2}\\s*(years old|y/o))",
            Pattern.CASE_INSENSITIVE);

    /**
     * SG-03: a personal-particulars field line such as "Marital status: Single". Spec regex, applied per line.
     * Basis: TAFEP fair-employment guidance (no marital status, religion, race, gender or family details).
     */
    public static final Pattern PERSONAL_FIELD_LINE = Pattern.compile(
            "^\\s*(marital status|religion|race|gender|sex|no\\. of children|children)\\s*[:\\-]",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** SG-03 (header block only): marital-status words. Only applied to name/label-style fields. */
    public static final Pattern MARITAL_WORDS = Pattern.compile(
            "\\b(married|single|divorced|widowed)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * SG-05: a full residential address. Either "Singapore 123456", or a 6-digit postal code within
     * 60 characters of a block/street marker or unit number (#05-123). Basis: spec 6A SG-05
     * (keep "Singapore" only).
     */
    public static final Pattern RESIDENTIAL_ADDRESS = Pattern.compile(
            "\\bSingapore\\s*\\d{6}\\b"
                    + "|(\\b(blk|block|street|avenue|road)\\b|#\\d{2}-\\d{2,4}).{0,60}\\b\\d{6}\\b"
                    + "|\\b\\d{6}\\b.{0,60}(\\b(blk|block|street|avenue|road)\\b|#\\d{2}-\\d{2,4})",
            Pattern.CASE_INSENSITIVE);

    /** Returns the first match of {@code pattern} in {@code text}, for showing the offending fragment. */
    public static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }
}

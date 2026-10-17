package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.checks.SgPatterns;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes lines that look like personal data P6 says must never be stored (NRIC/FIN, date of birth or age,
 * marital status and similar fields, residential address) from uploaded resume text, <em>before</em> the
 * text is stored or sent to the LLM.
 *
 * <p>Whole lines are dropped rather than just the match: on a resume these are usually single
 * "label: value" lines, and a half-removed address would still identify the person.
 * Regexes are heuristics; the master-resume validator checks again after import.
 */
@Component
public class PersonalDataRedactor {

    private static final List<Map.Entry<String, Pattern>> CHECKS = List.of(
            Map.entry("an NRIC/FIN number", SgPatterns.NRIC_FIN),
            Map.entry("a date of birth or age", SgPatterns.DOB_OR_AGE),
            Map.entry("a personal-particulars field (marital status, religion, race, gender or children)",
                    SgPatterns.PERSONAL_FIELD_LINE),
            Map.entry("a residential address", SgPatterns.RESIDENTIAL_ADDRESS));

    /** @param notes one line per removal, without the removed content itself */
    public record Redacted(String text, List<String> notes) {}

    public Redacted redact(String text) {
        List<String> kept = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String reason = reason(lines[i]);
            if (reason == null) {
                kept.add(lines[i]);
            } else {
                notes.add("Removed line " + (i + 1) + " of the uploaded text because it looked like " + reason
                        + ". It was not stored or sent to Claude (P6).");
            }
        }
        return new Redacted(String.join("\n", kept), notes);
    }

    private static String reason(String line) {
        for (Map.Entry<String, Pattern> check : CHECKS) {
            if (check.getValue().matcher(line).find()) {
                return check.getKey();
            }
        }
        return null;
    }
}

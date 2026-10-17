package com.bu.tailorsg.master;

import com.bu.tailorsg.master.MasterResume.Highlight;
import com.bu.tailorsg.master.MasterResume.Skill;
import com.bu.tailorsg.master.ValidationError.Kind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-field rules a JSON Schema cannot express. Runs on the typed {@link MasterResume}, only after
 * schema validation has passed.
 *
 * <ul>
 *   <li>ERROR: every {@code skills[].evidenceIds} entry must be a highlight that exists, so "proven skill"
 *       claims always resolve (the basis for TRUTH-03 later).</li>
 *   <li>ERROR: an end date cannot be before its start date.</li>
 *   <li>Note: skills with no evidence are allowed but listed (spec 2.1 "unevidenced").</li>
 * </ul>
 */
@Component
public class MasterIntegrityValidator {

    /** @param notes informational, never block saving */
    public record Outcome(List<ValidationError> errors, List<String> notes) {}

    public Outcome check(MasterResume resume) {
        List<ValidationError> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Highlight> highlights = resume.highlightsById();

        for (int i = 0; i < resume.skills().size(); i++) {
            Skill skill = resume.skills().get(i);
            if (skill.evidenceIds().isEmpty()) {
                notes.add("Skill '" + skill.name() + "' (" + skill.id() + ") is unevidenced: no highlight shows it in use.");
            }
            for (int j = 0; j < skill.evidenceIds().size(); j++) {
                String ref = skill.evidenceIds().get(j);
                if (!highlights.containsKey(ref)) {
                    errors.add(new ValidationError("/skills/" + i + "/evidenceIds/" + j,
                            "Skill '" + skill.name() + "' cites " + ref + ", which is not a highlight in this resume.",
                            Kind.INTEGRITY));
                }
            }
        }

        checkDates("/work", resume.work().stream().map(w -> new Span(w.startDate(), w.endDate())).toList(), errors);
        checkDates("/volunteer", resume.volunteer().stream().map(v -> new Span(v.startDate(), v.endDate())).toList(), errors);
        checkDates("/projects", resume.projects().stream().map(p -> new Span(p.startDate(), p.endDate())).toList(), errors);
        checkDates("/education", resume.education().stream().map(e -> new Span(e.startDate(), e.endDate())).toList(), errors);
        return new Outcome(errors, notes);
    }

    private record Span(String start, String end) {}

    private static void checkDates(String pointer, List<Span> spans, List<ValidationError> errors) {
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            // YYYY-MM strings sort chronologically as plain text, so compareTo is enough.
            if (span.start() != null && span.end() != null && span.end().compareTo(span.start()) < 0) {
                errors.add(new ValidationError(pointer + "/" + i + "/endDate",
                        "Ends (" + span.end() + ") before it starts (" + span.start() + ").", Kind.INTEGRITY));
            }
        }
    }
}

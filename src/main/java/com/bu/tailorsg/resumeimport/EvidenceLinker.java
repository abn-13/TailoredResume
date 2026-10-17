package com.bu.tailorsg.resumeimport;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Links each imported skill to the highlights that show it in use ({@code skills[].evidenceIds}).
 *
 * <p>Done in code, not by the LLM: a highlight is evidence for a skill if it lists the skill in its own
 * {@code skills}, or names it as a whole word in its text. Very short names ("C", "Go", "R") are only
 * matched through the {@code skills} list, because as words they appear in ordinary sentences.
 *
 * <p>Every highlight gets a {@code NEW-n} placeholder ID so evidence can refer to it; the master pipeline
 * replaces placeholders with real IDs when the import is applied.
 */
@Component
public class EvidenceLinker {

    private static final List<String> SECTIONS = List.of("work", "projects", "education", "volunteer");
    static final int MIN_TEXT_MATCH_LENGTH = 3;

    /** @return the number of skills that ended up with at least one piece of evidence */
    public int link(ObjectNode document) {
        List<ObjectNode> highlights = new ArrayList<>();
        for (String section : SECTIONS) {
            document.path(section).values().forEach(item ->
                    item.path("highlights").values().forEach(h -> highlights.add((ObjectNode) h)));
        }
        int counter = 0;
        for (ObjectNode h : highlights) {
            h.put("id", "NEW-" + ++counter);
        }

        int evidenced = 0;
        for (JsonNode skill : document.path("skills").values()) {
            String name = skill.path("name").stringValue("");
            ArrayNode evidence = ((ObjectNode) skill).putArray("evidenceIds");
            for (ObjectNode h : highlights) {
                if (demonstrates(h, name)) {
                    evidence.add(h.get("id").stringValue());
                }
            }
            if (evidence.isEmpty()) {
                ((ObjectNode) skill).remove("evidenceIds");
            } else {
                evidenced++;
            }
        }
        return evidenced;
    }

    static boolean demonstrates(JsonNode highlight, String skillName) {
        String wanted = skillName.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return false;
        }
        for (JsonNode listed : highlight.path("skills").values()) {
            if (listed.stringValue("").strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        if (wanted.length() < MIN_TEXT_MATCH_LENGTH) {
            return false;
        }
        // Whole word or phrase: "Java" must not match inside "JavaScript".
        Pattern word = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(wanted) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return word.matcher(highlight.path("text").stringValue("")).find();
    }
}

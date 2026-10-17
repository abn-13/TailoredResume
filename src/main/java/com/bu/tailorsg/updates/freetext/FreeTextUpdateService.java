package com.bu.tailorsg.updates.freetext;

import com.bu.tailorsg.checks.kw.KeywordAnalyzer;
import com.bu.tailorsg.checks.kw.KeywordMatcher;
import com.bu.tailorsg.checks.truth.TruthChecks;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.resumeimport.PersonalDataRedactor;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
import com.bu.tailorsg.updates.PendingUpdateService.Proposal;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Free-text master updates (spec 5.2, Phase 8): "describe it in your own words" becomes a proposed JSON Patch
 * the user reviews like any other {@link PendingUpdate}. It is never applied automatically.
 *
 * <p>After the model answers, code checks it against what the user actually wrote: every number in the
 * proposed values must appear in the user's text (or already be in the master), and every new skill must be
 * named in the text. Anything else becomes a "check this" note, next to the model's clarifying questions.
 * Answering the questions produces a fresh proposal from the original text plus the answers.
 */
@Service
public class FreeTextUpdateService {

    public static final String PROMPT = "master-update";
    static final int MAX_CHARS = 6000;
    static final String QUESTION = "Question: ";

    private final ClaudeClient claude;
    private final MasterResumeService master;
    private final PendingUpdateService updates;
    private final PersonalDataRedactor redactor;
    private final KeywordAnalyzer keywords;
    private final JsonMapper json;

    public FreeTextUpdateService(ClaudeClient claude, MasterResumeService master, PendingUpdateService updates,
                                 PersonalDataRedactor redactor, KeywordAnalyzer keywords, JsonMapper json) {
        this.claude = claude;
        this.master = master;
        this.updates = updates;
        this.redactor = redactor;
        this.keywords = keywords;
        this.json = json;
    }

    /** @throws IllegalArgumentException for empty or overlong text, or when there is no master yet */
    public PendingUpdate propose(String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.length() < 15) {
            throw new IllegalArgumentException("Write a sentence or two about what you did.");
        }
        if (clean.length() > MAX_CHARS) {
            throw new IllegalArgumentException("Keep it under " + MAX_CHARS + " characters; split it into several updates.");
        }
        if (master.current().isEmpty()) {
            throw new IllegalArgumentException("Create your master resume first.");
        }
        // P6: lines with NRIC, date of birth and the like never reach the model or the database.
        PersonalDataRedactor.Redacted redacted = redactor.redact(clean);
        String userText = redacted.text();

        LlmResult result = claude.call(PROMPT, List.of(
                new TextBlock("<MASTER>\n" + json.writeValueAsString(master.currentDocumentOrEmpty()) + "\n</MASTER>", true),
                TextBlock.of("<USER_TEXT>\n" + userText + "\n</USER_TEXT>")));

        List<String> notes = new ArrayList<>(redacted.notes());
        ArrayNode patch = toPatch(result.json().path("patch"), notes);
        notes.addAll(verify(patch, userText, master.currentDocumentOrEmpty()));
        result.json().path("questions").values().forEach(q -> {
            if (!q.stringValue("").isBlank()) {
                notes.add(QUESTION + q.stringValue(""));
            }
        });
        String summary = result.json().path("summary").stringValue("Free-text update");
        return updates.create(new Proposal(PendingUpdate.Source.FREE_TEXT, userText, patch, summary.isBlank() ? "Free-text update" : summary,
                notes), false);
    }

    /**
     * The answer-and-regenerate loop: the original text plus the user's answers become a new proposal, and
     * the old one is rejected so only one stays open.
     */
    public PendingUpdate answer(long updateId, String answers) {
        PendingUpdate old = updates.get(updateId);
        if (old.getSourceType() != PendingUpdate.Source.FREE_TEXT || old.getStatus() != PendingUpdate.Status.PROPOSED) {
            throw new IllegalArgumentException("Only an open free-text update can be answered.");
        }
        if (answers == null || answers.isBlank()) {
            throw new IllegalArgumentException("Type your answers first.");
        }
        List<String> questions = updates.notesOf(old).stream().filter(n -> n.startsWith(QUESTION))
                .map(n -> n.substring(QUESTION.length())).toList();
        String combined = old.getRawInput() + "\n\nAnswers to your questions"
                + (questions.isEmpty() ? "" : " (" + String.join(" / ", questions) + ")") + ":\n" + answers.strip();
        PendingUpdate fresh = propose(combined.length() > MAX_CHARS ? combined.substring(0, MAX_CHARS) : combined);
        updates.reject(updateId);
        return fresh;
    }

    /** {op, path, from, valueJson} from the model into an RFC 6902 patch. Unparseable operations are dropped with a note. */
    ArrayNode toPatch(JsonNode ops, List<String> notes) {
        ArrayNode patch = json.createArrayNode();
        for (JsonNode op : ops.values()) {
            String name = op.path("op").stringValue("");
            String path = op.path("path").stringValue("");
            if (path.startsWith("/meta")) {
                notes.add("Ignored a change to " + path + ": the server manages it.");
                continue;
            }
            ObjectNode out = patch.addObject();
            out.put("op", name);
            out.put("path", path);
            String from = op.path("from").stringValue("");
            if (!from.isEmpty()) {
                out.put("from", from);
            }
            String value = op.path("valueJson").stringValue("");
            if (!name.equals("remove") && !name.equals("move") && !name.equals("copy")) {
                try {
                    out.set("value", value.isBlank() ? json.nullNode() : json.readTree(value));
                } catch (JacksonException e) {
                    patch.remove(patch.size() - 1);
                    notes.add("Dropped one change to " + path + ": its value was not valid JSON.");
                }
            }
        }
        return patch;
    }

    /**
     * Holds the proposal to the user's words (spec 5.2 rule 1): numbers and new skill names in the proposed
     * values must appear in the user's text. A {@code replace} may also keep numbers from the value it
     * replaces (rewording an existing highlight), but nothing else in the master counts.
     */
    List<String> verify(ArrayNode patch, String userText, JsonNode current) {
        List<String> notes = new ArrayList<>();
        Set<String> fromUser = TruthChecks.Truth02Numbers.numbers(userText, Set.of());
        Set<String> invented = new LinkedHashSet<>();
        List<String> newSkills = new ArrayList<>();
        for (JsonNode op : patch.values()) {
            JsonNode value = op.get("value");
            if (value == null) {
                continue;
            }
            Set<String> allowed = new java.util.HashSet<>(fromUser);
            if ("replace".equals(op.path("op").stringValue(""))) {
                JsonNode replaced = current.at(op.path("path").stringValue(""));
                if (!replaced.isMissingNode()) {
                    allowed.addAll(TruthChecks.Truth02Numbers.numbers(replaced.toString(), Set.of()));
                }
            }
            collectStrings(value, s -> {
                for (String n : TruthChecks.Truth02Numbers.numbers(s, Set.of())) {
                    if (!allowed.contains(n)) {
                        invented.add(n);
                    }
                }
            });
            if (op.path("path").stringValue("").matches("/skills/(-|\\d+)") && value.has("name")) {
                newSkills.add(value.path("name").stringValue(""));
            }
            for (String key : List.of("startDate", "endDate")) {
                if (value.has(key) && !value.path(key).stringValue("").isEmpty()) {
                    String year = value.path(key).stringValue("").substring(0, Math.min(4, value.path(key).stringValue("").length()));
                    if (!userText.contains(year)) {
                        notes.add("Check the date " + value.path(key).stringValue("") + ": the year is not in what you wrote.");
                    }
                }
            }
        }
        if (!invented.isEmpty()) {
            notes.add("Check these numbers; they are not in what you wrote: " + String.join(", ", invented) + ".");
        }
        KeywordMatcher matcher = keywords.matcher();
        for (String skill : newSkills) {
            if (!skill.isBlank() && !matcher.term(com.bu.tailorsg.jobs.JobSpec.JdSkill.of(skill)).foundIn(userText)) {
                notes.add("New skill \"" + skill + "\" is not mentioned in what you wrote. Remove it unless it is true.");
            }
        }
        return notes;
    }

    private static void collectStrings(JsonNode node, java.util.function.Consumer<String> sink) {
        if (node.isString()) {
            sink.accept(node.stringValue(""));
        } else if (node.isNumber()) {
            sink.accept(node.asString());
        } else if (node.isObject()) {
            node.properties().forEach(e -> {
                // IDs are not claims; dates are checked separately ("2026-05" holds the month as a number)
                if (!Set.of("id", "evidenceIds", "startDate", "endDate", "date").contains(e.getKey())) {
                    collectStrings(e.getValue(), sink);
                }
            });
        } else if (node.isArray()) {
            node.values().forEach(v -> collectStrings(v, sink));
        }
    }
}

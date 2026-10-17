package com.bu.tailorsg.master;

import com.bu.tailorsg.master.ValidationError.Kind;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives every item and highlight a stable ID and enforces the spec's ID rules (2.1):
 * "IDs are stable and never reused. Generate them server-side (prefix + incrementing counter)."
 *
 * <ul>
 *   <li>An item with no {@code id}, or a placeholder like {@code NEW-1}, gets the next ID for its
 *       section ({@code work-3}); a highlight gets {@code <parentId>.h<n>} ({@code work-3.h1}).</li>
 *   <li>References to placeholders in {@code skills[].evidenceIds} are rewritten to the real IDs, so
 *       one edit can add a highlight and the skill evidence pointing at it.</li>
 *   <li>An explicit ID is accepted if it is in the current version, or if its number was never
 *       issued. An ID whose number was issued before but is no longer present is <em>retired</em>
 *       and rejected, so an old application's sourceIds can never point at a different item.</li>
 * </ul>
 *
 * <p>Pure logic: counters come in as a map and the updates go out in the result; the service persists them.
 * Tolerant of malformed input (wrong types are skipped, and schema validation reports them afterwards).
 */
@Component
public class IdAssigner {

    static final Pattern PLACEHOLDER = Pattern.compile("^NEW-\\d+$");
    // Numbers are capped at 9 digits so they always fit in a long.
    static final Pattern ITEM_ID = Pattern.compile("^([a-z]+)-([1-9]\\d{0,8})$");
    static final Pattern HIGHLIGHT_ID = Pattern.compile("^([a-z]+-[1-9]\\d{0,8})\\.h([1-9]\\d{0,8})$");

    /** Every ID-bearing list in the document, with its ID prefix. */
    enum Section {
        SUMMARY_VARIANTS("/basics/summaryVariants", "sum", false),
        WORK("/work", "work", true),
        PROJECTS("/projects", "proj", true),
        EDUCATION("/education", "edu", true),
        VOLUNTEER("/volunteer", "vol", true),
        SKILLS("/skills", "skill", false),
        CERTIFICATES("/certificates", "cert", false),
        AWARDS("/awards", "award", false),
        EXTRAS("/extras", "extra", false);

        final String pointer;
        final String prefix;
        final boolean hasHighlights;

        Section(String pointer, String prefix, boolean hasHighlights) {
            this.pointer = pointer;
            this.prefix = prefix;
            this.hasHighlights = hasHighlights;
        }
    }

    /**
     * @param document       copy of the input with IDs filled in (the input is never modified)
     * @param assignedIds    newly issued IDs, in document order
     * @param counterUpdates counter prefix ("work", "work-1.h") -> new highest number; persist only if no errors
     * @param errors         ID rule violations; empty means the IDs are fine
     */
    public record Result(JsonNode document, List<String> assignedIds, Map<String, Long> counterUpdates,
                         List<ValidationError> errors) {}

    /**
     * @param input           candidate master resume
     * @param currentIds      every ID in the current version (empty for the first save)
     * @param lastIssued      highest number ever issued per counter prefix (missing = 0)
     * @param allowedRetired  IDs allowed even if retired; used when restoring an old version, whose
     *                        items are by definition the same items those IDs always meant
     */
    public Result assign(JsonNode input, Set<String> currentIds, Map<String, Long> lastIssued,
                         Set<String> allowedRetired) {
        Run run = new Run(input.deepCopy(), currentIds, lastIssued, allowedRetired);
        run.reserveExplicitIds();
        run.resolveAll();
        run.rewritePlaceholderReferences();
        return new Result(run.doc, run.assigned, run.counterUpdates(), run.errors);
    }

    /** State for one assign() call, so the component itself stays stateless and thread-safe. */
    private static final class Run {
        final JsonNode doc;
        final Set<String> currentIds;
        final Map<String, Long> lastIssued;
        final Set<String> allowedRetired;

        final Map<String, Long> highest = new HashMap<>(); // per prefix, including IDs written in this doc
        final Map<String, String> placeholders = new LinkedHashMap<>();
        final Set<String> seen = new HashSet<>();
        final List<String> assigned = new ArrayList<>();
        final List<ValidationError> errors = new ArrayList<>();

        Run(JsonNode doc, Set<String> currentIds, Map<String, Long> lastIssued, Set<String> allowedRetired) {
            this.doc = doc;
            this.currentIds = currentIds;
            this.lastIssued = lastIssued;
            this.allowedRetired = allowedRetired;
        }

        /** Pass 1: note every well-formed explicit ID, so new IDs never collide with ones typed by hand. */
        void reserveExplicitIds() {
            for (Section section : Section.values()) {
                forEachObject(doc.at(section.pointer), (item, i) -> {
                    Matcher m = ITEM_ID.matcher(text(item.get("id")));
                    if (m.matches() && m.group(1).equals(section.prefix)) {
                        bump(section.prefix, Long.parseLong(m.group(2)));
                    }
                    if (section.hasHighlights) {
                        forEachObject(item.get("highlights"), (h, j) -> {
                            Matcher hm = HIGHLIGHT_ID.matcher(text(h.get("id")));
                            if (hm.matches()) {
                                bump(hm.group(1) + ".h", Long.parseLong(hm.group(2)));
                            }
                        });
                    }
                });
            }
        }

        /** Pass 2: validate explicit IDs and assign missing / placeholder ones. */
        void resolveAll() {
            for (Section section : Section.values()) {
                JsonNode list = doc.at(section.pointer);
                if (!(list instanceof ArrayNode array)) {
                    continue; // absent or wrong type: schema validation will report it
                }
                for (int i = 0; i < array.size(); i++) {
                    if (!(array.get(i) instanceof ObjectNode item)) {
                        continue;
                    }
                    String pointer = section.pointer + "/" + i;
                    ObjectNode resolved = resolveId(item, section.prefix, pointer, ITEM_ID, section.prefix);
                    array.set(i, resolved);
                    String itemId = text(resolved.get("id"));
                    // Skip highlights under an item whose own id was rejected: their errors would only be noise.
                    if (section.hasHighlights && isOwnId(itemId, section.prefix)) {
                        resolveHighlights(resolved, itemId, pointer);
                    }
                }
            }
        }

        private void resolveHighlights(ObjectNode item, String itemId, String itemPointer) {
            if (!(item.get("highlights") instanceof ArrayNode highlights)) {
                return;
            }
            for (int j = 0; j < highlights.size(); j++) {
                if (highlights.get(j) instanceof ObjectNode h) {
                    String pointer = itemPointer + "/highlights/" + j;
                    highlights.set(j, resolveId(h, itemId + ".h", pointer, HIGHLIGHT_ID, itemId));
                }
            }
        }

        /**
         * Returns the node with a valid ID: either its own (checked) or a newly assigned one.
         *
         * @param counterPrefix "work" for items, "work-1.h" for highlights
         * @param format        ITEM_ID or HIGHLIGHT_ID
         * @param owner         what group(1) of the format must equal: the section prefix for items,
         *                      the parent ID for highlights
         */
        private ObjectNode resolveId(ObjectNode node, String counterPrefix, String pointer, Pattern format, String owner) {
            JsonNode idNode = node.get("id");
            if (idNode == null) {
                return assignNew(node, counterPrefix, format, pointer, null);
            }
            if (!idNode.isString()) {
                return node; // wrong type: the schema reports it
            }
            if (PLACEHOLDER.matcher(idNode.stringValue()).matches()) {
                return assignNew(node, counterPrefix, format, pointer, idNode.stringValue());
            }
            checkExplicit(idNode.stringValue(), counterPrefix, pointer, format, owner);
            return node;
        }

        /** Issues the next ID for the prefix; remembers the placeholder (if any) it replaces. */
        private ObjectNode assignNew(ObjectNode node, String counterPrefix, Pattern format, String pointer, String placeholder) {
            String newId = format == ITEM_ID ? counterPrefix + "-" + next(counterPrefix) : counterPrefix + next(counterPrefix);
            if (placeholder != null && placeholders.putIfAbsent(placeholder, newId) != null) {
                error(pointer + "/id", "Placeholder '" + placeholder + "' is used more than once. Use NEW-1, NEW-2, ...");
            }
            assigned.add(newId);
            seen.add(newId);
            return withIdFirst(node, newId);
        }

        /** An ID the user wrote: right format and owner, not duplicated, not retired. */
        private void checkExplicit(String id, String counterPrefix, String pointer, Pattern format, String owner) {
            Matcher m = format.matcher(id);
            if (!m.matches() || !m.group(1).equals(owner)) {
                if (format == HIGHLIGHT_ID && m.matches()) {
                    error(pointer + "/id", "Highlight '" + id + "' belongs to " + m.group(1) + " but sits under " + owner
                            + ". IDs cannot move between items: remove the id (or use NEW-1) to add it here as a new highlight.");
                } else {
                    String expected = format == ITEM_ID ? owner + "-<number>" : owner + ".h<number>";
                    error(pointer + "/id", "'" + id + "' is not a valid id here (expected " + expected
                            + "). Remove it (or use NEW-1) and the server will assign one.");
                }
                return;
            }
            if (!seen.add(id)) {
                error(pointer + "/id", "Duplicate id '" + id + "'. Each item needs its own id: remove it from the copy.");
                return;
            }
            long number = Long.parseLong(m.group(2));
            boolean retired = number <= lastIssued.getOrDefault(counterPrefix, 0L)
                    && !currentIds.contains(id) && !allowedRetired.contains(id);
            if (retired) {
                error(pointer + "/id", "'" + id + "' was used before and has been retired; IDs are never reused. "
                        + "Remove it (or use NEW-1) to get a new id.");
            }
        }

        /** Pass 3: point skills[].evidenceIds placeholders at the IDs just assigned. */
        void rewritePlaceholderReferences() {
            forEachObject(doc.at("/skills"), (skill, i) -> {
                if (!(skill.get("evidenceIds") instanceof ArrayNode evidence)) {
                    return;
                }
                for (int j = 0; j < evidence.size(); j++) {
                    String ref = text(evidence.get(j));
                    if (PLACEHOLDER.matcher(ref).matches()) {
                        String real = placeholders.get(ref);
                        if (real == null) {
                            error("/skills/" + i + "/evidenceIds/" + j,
                                    "'" + ref + "' does not match any placeholder id in this document.");
                        } else {
                            evidence.set(j, JsonNodeFactory.instance.stringNode(real));
                        }
                    }
                }
            });
        }

        Map<String, Long> counterUpdates() {
            Map<String, Long> updates = new HashMap<>();
            highest.forEach((prefix, value) -> {
                if (value > lastIssued.getOrDefault(prefix, 0L)) {
                    updates.put(prefix, value);
                }
            });
            return updates;
        }

        private long next(String prefix) {
            long value = Math.max(lastIssued.getOrDefault(prefix, 0L), highest.getOrDefault(prefix, 0L)) + 1;
            highest.put(prefix, value);
            return value;
        }

        private void bump(String prefix, long number) {
            highest.merge(prefix, number, Math::max);
        }

        private void error(String pointer, String message) {
            errors.add(new ValidationError(pointer, message, Kind.IDS));
        }
    }

    /** Every item and highlight ID in a document (used for "is this ID in the current version?"). */
    public static Set<String> collectIds(JsonNode document) {
        Set<String> ids = new HashSet<>();
        for (Section section : Section.values()) {
            forEachObject(document.at(section.pointer), (item, i) -> {
                if (item.path("id").isString()) {
                    ids.add(item.get("id").stringValue());
                }
                forEachObject(item.get("highlights"), (h, j) -> {
                    if (h.path("id").isString()) {
                        ids.add(h.get("id").stringValue());
                    }
                });
            });
        }
        return ids;
    }

    /** Rebuilds the object with "id" as its first key, which keeps the JSON editor readable. */
    private static ObjectNode withIdFirst(ObjectNode node, String id) {
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        copy.put("id", id);
        node.properties().forEach(e -> {
            if (!e.getKey().equals("id")) {
                copy.set(e.getKey(), e.getValue());
            }
        });
        return copy;
    }

    private static boolean isOwnId(String id, String sectionPrefix) {
        Matcher m = ITEM_ID.matcher(id);
        return m.matches() && m.group(1).equals(sectionPrefix);
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : "";
    }

    private interface IndexedConsumer {
        void accept(ObjectNode item, int index);
    }

    private static void forEachObject(JsonNode maybeArray, IndexedConsumer action) {
        if (maybeArray instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof ObjectNode item) {
                    action.accept(item, i);
                }
            }
        }
    }
}

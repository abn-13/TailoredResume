package com.bu.tailorsg.updates.form;

import com.bu.tailorsg.updates.form.EntryForm.NewSkill;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an "Add Info" form into an RFC 6902 JSON Patch against the current master resume. No LLM: every
 * value in the patch is exactly what the user typed (spec 3, "server builds the patch directly").
 *
 * <ul>
 *   <li>New items carry no IDs (or a {@code NEW-1} placeholder when something must refer to them); the
 *       master pipeline assigns real IDs on apply.</li>
 *   <li>Edits to existing entries are preceded by {@code test} guards on their IDs, so a patch that has
 *       gone stale (because the master changed) fails cleanly instead of editing the wrong entry.</li>
 *   <li>Dated entries are inserted in reverse-chronological order, newest first.</li>
 * </ul>
 */
@Component
public class FormPatchBuilder {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> HIGHLIGHT_PARENTS = List.of("work", "projects", "education", "volunteer");
    /** Placeholder for the highlight created by the highlight form, so skills can cite it as evidence. */
    static final String NEW_HIGHLIGHT = "NEW-1";

    /** @param summary one line for the update list and the eventual version note */
    public record Built(ArrayNode patch, String summary) {}

    /** A problem tied to one form field; the page shows it next to that input. */
    public static class FieldProblem extends RuntimeException {
        private final String field;

        public FieldProblem(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    public Built build(EntryForm form, JsonNode master) {
        return switch (form) {
            case EntryForm.Work w -> work(w, master);
            case EntryForm.Project p -> project(p, master);
            case EntryForm.Education e -> education(e, master);
            case EntryForm.Skill s -> skill(s, master);
            case EntryForm.Certificate c -> certificate(c, master);
            case EntryForm.Award a -> award(a, master);
            case EntryForm.Highlight h -> highlight(h, master);
            case EntryForm.Metric m -> metric(m, master);
        };
    }

    // ---------------------------------------------------------------- one method per entry type

    private Built work(EntryForm.Work f, JsonNode master) {
        checkDateOrder(f.getStartDate(), f.getEndDate());
        ObjectNode item = NODES.objectNode();
        text(item, "name", f.getName());
        text(item, "position", f.getPosition());
        text(item, "location", f.getLocation());
        text(item, "employmentType", f.getEmploymentType());
        text(item, "url", f.getUrl());
        text(item, "startDate", f.getStartDate());
        text(item, "endDate", f.getEndDate()); // blank = current role
        text(item, "summary", f.getSummary());
        highlightLines(item, f.getHighlights());
        ArrayNode patch = NODES.arrayNode();
        insertDated(patch, master, "/work", item);
        return new Built(patch, "Add work: " + clean(f.getPosition()) + " at " + clean(f.getName())
                + " (" + dates(f.getStartDate(), f.getEndDate()) + ")");
    }

    private Built project(EntryForm.Project f, JsonNode master) {
        checkDateOrder(f.getStartDate(), f.getEndDate());
        ObjectNode item = NODES.objectNode();
        text(item, "name", f.getName());
        text(item, "role", f.getRole());
        text(item, "url", f.getUrl());
        text(item, "repo", f.getRepo());
        text(item, "startDate", f.getStartDate());
        text(item, "endDate", f.getEndDate());
        text(item, "description", f.getDescription());
        highlightLines(item, f.getHighlights());
        list(item, "keywords", commaList(f.getKeywords()));
        ArrayNode patch = NODES.arrayNode();
        insertDated(patch, master, "/projects", item);
        return new Built(patch, "Add project: " + clean(f.getName()));
    }

    private Built education(EntryForm.Education f, JsonNode master) {
        checkDateOrder(f.getStartDate(), f.getEndDate());
        ObjectNode item = NODES.objectNode();
        text(item, "institution", f.getInstitution());
        text(item, "area", f.getArea());
        text(item, "studyType", f.getStudyType());
        text(item, "startDate", f.getStartDate());
        text(item, "endDate", f.getEndDate());
        text(item, "score", f.getScore());
        text(item, "scoreScale", f.getScoreScale());
        list(item, "courses", commaList(f.getCourses()));
        ArrayNode patch = NODES.arrayNode();
        insertDated(patch, master, "/education", item);
        return new Built(patch, "Add education: " + clean(f.getStudyType()) + ", " + clean(f.getInstitution()));
    }

    private Built skill(EntryForm.Skill f, JsonNode master) {
        rejectDuplicateSkill("name", f.getName(), master);
        Set<String> highlightIds = highlightIds(master);
        List<String> evidence = new ArrayList<>(new LinkedHashSet<>(f.getEvidenceIds()));
        evidence.removeIf(String::isBlank);
        for (String id : evidence) {
            if (!highlightIds.contains(id)) {
                throw new FieldProblem("evidenceIds", "Highlight " + id + " does not exist in your master resume.");
            }
        }
        ObjectNode item = NODES.objectNode();
        text(item, "name", f.getName());
        text(item, "category", f.getCategory());
        text(item, "level", f.getLevel());
        list(item, "evidenceIds", evidence);
        ArrayNode patch = NODES.arrayNode();
        append(patch, master, "/skills", item);
        return new Built(patch, "Add skill: " + clean(f.getName()) + " (" + f.getCategory() + ")"
                + (evidence.isEmpty() ? ", unevidenced" : ", evidence " + String.join(", ", evidence)));
    }

    private Built certificate(EntryForm.Certificate f, JsonNode master) {
        ObjectNode item = NODES.objectNode();
        text(item, "name", f.getName());
        text(item, "issuer", f.getIssuer());
        text(item, "date", f.getDate());
        text(item, "url", f.getUrl());
        ArrayNode patch = NODES.arrayNode();
        append(patch, master, "/certificates", item);
        return new Built(patch, "Add certificate: " + clean(f.getName()) + " (" + clean(f.getIssuer()) + ")");
    }

    private Built award(EntryForm.Award f, JsonNode master) {
        ObjectNode item = NODES.objectNode();
        text(item, "title", f.getTitle());
        text(item, "awarder", f.getAwarder());
        text(item, "date", f.getDate());
        text(item, "summary", f.getSummary());
        ArrayNode patch = NODES.arrayNode();
        append(patch, master, "/awards", item);
        return new Built(patch, "Add award: " + clean(f.getTitle()));
    }

    private Built highlight(EntryForm.Highlight f, JsonNode master) {
        Parent parent = findParent(master, f.getParentId())
                .orElseThrow(() -> new FieldProblem("parentId", "Choose where this highlight belongs."));

        // Validate skills first, so nothing is half-built when a problem is found.
        List<String> existingNames = new ArrayList<>(new LinkedHashSet<>(f.getSkills()));
        existingNames.removeIf(String::isBlank);
        List<Integer> existingIndexes = new ArrayList<>();
        for (String name : existingNames) {
            existingIndexes.add(skillIndex(master, name)
                    .orElseThrow(() -> new FieldProblem("skills", "'" + name + "' is not one of your skills.")));
        }
        List<NewSkill> newSkills = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < f.getNewSkills().size(); i++) {
            NewSkill s = f.getNewSkills().get(i);
            if (s.getName() == null || s.getName().isBlank()) {
                continue;
            }
            if (s.getCategory() == null || s.getCategory().isBlank()) {
                throw new FieldProblem("newSkills[" + i + "].category", "Choose a category for " + clean(s.getName()) + ".");
            }
            rejectDuplicateSkill("newSkills[" + i + "].name", s.getName(), master);
            if (!seen.add(s.getName().strip().toLowerCase(Locale.ROOT))) {
                throw new FieldProblem("newSkills[" + i + "].name", clean(s.getName()) + " is listed twice.");
            }
            newSkills.add(s);
        }
        boolean hasMetric = notBlank(f.getMetricValue());
        if (hasMetric && !notBlank(f.getMetricLabel())) {
            throw new FieldProblem("metricLabel", "Say what the number measures, e.g. \"latency reduction\".");
        }

        ObjectNode highlight = NODES.objectNode();
        highlight.put("id", NEW_HIGHLIGHT);
        text(highlight, "text", f.getText());
        if (hasMetric) {
            ObjectNode metric = NODES.objectNode();
            text(metric, "value", f.getMetricValue());
            text(metric, "unit", f.getMetricUnit());
            text(metric, "label", f.getMetricLabel());
            highlight.set("metrics", NODES.arrayNode().add(metric));
        }
        List<String> skillNames = new ArrayList<>(existingNames);
        newSkills.forEach(s -> skillNames.add(s.getName().strip()));
        list(highlight, "skills", skillNames);
        list(highlight, "tags", commaList(f.getTags()));
        if (f.getPriority() != null) {
            highlight.put("priority", f.getPriority());
        }

        ArrayNode patch = NODES.arrayNode();
        test(patch, parent.pointer() + "/id", parent.id());
        append(patch, master, parent.pointer() + "/highlights", highlight);

        // The new highlight becomes evidence for every skill it demonstrates.
        for (int k = 0; k < existingIndexes.size(); k++) {
            int index = existingIndexes.get(k);
            String skillPointer = "/skills/" + index;
            test(patch, skillPointer + "/id", master.at(skillPointer + "/id").stringValue(""));
            append(patch, master, skillPointer + "/evidenceIds", NODES.stringNode(NEW_HIGHLIGHT));
        }
        boolean skillsListExists = master.at("/skills").isArray();
        for (NewSkill s : newSkills) {
            ObjectNode skill = NODES.objectNode();
            text(skill, "name", s.getName());
            text(skill, "category", s.getCategory());
            skill.set("evidenceIds", NODES.arrayNode().add(NEW_HIGHLIGHT));
            if (!skillsListExists) {
                add(patch, "/skills", NODES.arrayNode());
                skillsListExists = true;
            }
            add(patch, "/skills/-", skill);
        }

        String preview = clean(f.getText());
        return new Built(patch, "Add highlight to " + parent.id() + " (" + parent.label() + "): \""
                + (preview.length() > 70 ? preview.substring(0, 69) + "…" : preview) + "\"");
    }

    private Built metric(EntryForm.Metric f, JsonNode master) {
        HighlightLocation loc = findHighlight(master, f.getHighlightId())
                .orElseThrow(() -> new FieldProblem("highlightId", "Highlight " + f.getHighlightId() + " does not exist."));
        String digits = f.getValue().replaceAll("\\D", "");
        if (!digits.isEmpty() && !f.getText().replaceAll("\\D", "").contains(digits)) {
            throw new FieldProblem("text", "Mention the number in the highlight text too, so tailored bullets can use it.");
        }
        ObjectNode metric = NODES.objectNode();
        text(metric, "value", f.getValue());
        text(metric, "unit", f.getUnit());
        text(metric, "label", f.getLabel());
        ArrayNode patch = NODES.arrayNode();
        test(patch, loc.pointer() + "/id", f.getHighlightId());
        append(patch, master, loc.pointer() + "/metrics", metric);
        if (!f.getText().strip().equals(loc.text())) {
            patch.addObject().put("op", "replace").put("path", loc.pointer() + "/text").put("value", f.getText().strip());
        }
        String unit = f.getUnit() == null || f.getUnit().isBlank() ? "" : (f.getUnit().strip().equals("%") ? "%" : " " + f.getUnit().strip());
        return new Built(patch, "Add metric to " + f.getHighlightId() + ": " + clean(f.getValue()) + unit + " (" + clean(f.getLabel()) + ")");
    }

    // ---------------------------------------------------------------- lookups

    /** Where a highlight lives, e.g. /work/0/highlights/2, and its current text. */
    public record HighlightLocation(String pointer, String text) {}

    public static Optional<HighlightLocation> findHighlight(JsonNode master, String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (String section : HIGHLIGHT_PARENTS) {
            JsonNode list = master.path(section);
            for (int i = 0; i < list.size(); i++) {
                JsonNode highlights = list.get(i).path("highlights");
                for (int j = 0; j < highlights.size(); j++) {
                    if (id.equals(highlights.get(j).path("id").stringValue(""))) {
                        return Optional.of(new HighlightLocation("/" + section + "/" + i + "/highlights/" + j,
                                highlights.get(j).path("text").stringValue("")));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Where a highlight can go: a work, project, education or volunteer entry. */
    public record Parent(String id, String pointer, String label) {}

    public static Optional<Parent> findParent(JsonNode master, String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (String section : HIGHLIGHT_PARENTS) {
            JsonNode list = master.path(section);
            for (int i = 0; i < list.size(); i++) {
                JsonNode item = list.get(i);
                if (id.equals(item.path("id").stringValue(""))) {
                    return Optional.of(new Parent(id, "/" + section + "/" + i, parentLabel(item)));
                }
            }
        }
        return Optional.empty();
    }

    /** All entries a highlight can be added to, newest sections first, for the form's select. */
    public static List<Parent> parents(JsonNode master) {
        List<Parent> parents = new ArrayList<>();
        for (String section : HIGHLIGHT_PARENTS) {
            JsonNode list = master.path(section);
            for (int i = 0; i < list.size(); i++) {
                JsonNode item = list.get(i);
                if (item.path("id").isString()) {
                    parents.add(new Parent(item.get("id").stringValue(), "/" + section + "/" + i, parentLabel(item)));
                }
            }
        }
        return parents;
    }

    static String parentLabel(JsonNode item) {
        if (item.has("position")) {
            return item.path("position").stringValue("") + ", " + item.path("name").stringValue("");
        }
        if (item.has("institution")) {
            return item.path("studyType").stringValue("") + ", " + item.path("institution").stringValue("");
        }
        return item.path("name").stringValue("");
    }

    private static Optional<Integer> skillIndex(JsonNode master, String name) {
        JsonNode skills = master.path("skills");
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).path("name").stringValue("").strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private static void rejectDuplicateSkill(String field, String name, JsonNode master) {
        skillIndex(master, name).ifPresent(i -> {
            JsonNode existing = master.path("skills").get(i);
            throw new FieldProblem(field, "You already have the skill " + existing.path("name").stringValue("")
                    + " (" + existing.path("id").stringValue("") + ").");
        });
    }

    private static Set<String> highlightIds(JsonNode master) {
        Set<String> ids = new LinkedHashSet<>();
        for (String section : HIGHLIGHT_PARENTS) {
            master.path(section).values().forEach(item ->
                    item.path("highlights").values().forEach(h -> ids.add(h.path("id").stringValue(""))));
        }
        return ids;
    }

    // ---------------------------------------------------------------- patch building helpers

    /** Adds to the end of a list, creating the list if the master does not have it yet. */
    private static void append(ArrayNode patch, JsonNode master, String listPointer, JsonNode value) {
        if (master.at(listPointer).isArray()) {
            add(patch, listPointer + "/-", value);
        } else {
            add(patch, listPointer, NODES.arrayNode().add(value));
        }
    }

    /** Inserts a dated entry before the first entry that started earlier (newest first). */
    private static void insertDated(ArrayNode patch, JsonNode master, String listPointer, ObjectNode item) {
        JsonNode list = master.at(listPointer);
        String start = item.path("startDate").stringValue("");
        if (!list.isArray()) {
            add(patch, listPointer, NODES.arrayNode().add(item));
            return;
        }
        int index = list.size();
        for (int i = 0; i < list.size() && !start.isEmpty(); i++) {
            String other = list.get(i).path("startDate").stringValue("");
            if (other.isEmpty() || other.compareTo(start) < 0) {
                index = i;
                break;
            }
        }
        add(patch, listPointer + "/" + (index == list.size() ? "-" : String.valueOf(index)), item);
    }

    private static void add(ArrayNode patch, String path, JsonNode value) {
        patch.addObject().put("op", "add").put("path", path).set("value", value);
    }

    private static void test(ArrayNode patch, String path, String expected) {
        patch.addObject().put("op", "test").put("path", path).put("value", expected);
    }

    /** Sets a trimmed text field, or leaves it out when blank. */
    private static void text(ObjectNode node, String key, String value) {
        if (notBlank(value)) {
            node.put(key, value.strip());
        }
    }

    private static void list(ObjectNode node, String key, List<String> values) {
        if (!values.isEmpty()) {
            ArrayNode array = node.putArray(key);
            values.forEach(array::add);
        }
    }

    /** One highlight per non-blank line; leading bullet characters and numbering are removed. */
    private static void highlightLines(ObjectNode item, String text) {
        if (!notBlank(text)) {
            return;
        }
        ArrayNode highlights = NODES.arrayNode();
        for (String line : text.split("\\R")) {
            String cleaned = line.strip().replaceFirst("^([•▪◦‣*\\-–]|\\d+[.)])\\s*", "").strip();
            if (!cleaned.isEmpty()) {
                highlights.addObject().put("text", cleaned);
            }
        }
        if (!highlights.isEmpty()) {
            item.set("highlights", highlights);
        }
    }

    /** "Java, Spring Boot ,, Java" becomes [Java, Spring Boot]. */
    static List<String> commaList(String text) {
        if (!notBlank(text)) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(Arrays.stream(text.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList()));
    }

    private static void checkDateOrder(String start, String end) {
        if (notBlank(start) && notBlank(end) && end.compareTo(start) < 0) {
            throw new FieldProblem("endDate", "The end date is before the start date.");
        }
    }

    private static String dates(String start, String end) {
        return (notBlank(start) ? start : "?") + " to " + (notBlank(end) ? end : "present");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}

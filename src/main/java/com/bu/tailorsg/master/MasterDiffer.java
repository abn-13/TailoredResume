package com.bu.tailorsg.master;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural diff between two master resume versions, for the history view.
 *
 * <p>Why not a generic JSON diff: arrays of items with IDs (work, highlights, skills...) are matched
 * <em>by ID</em>, not by position. Inserting a job at the top then reads as "added work-3", not as
 * every later job being "changed". Paths use IDs in place of indexes for the same reason:
 * {@code /work/work-1/highlights/work-1.h2/text}.
 *
 * <p>{@code meta.version} and {@code meta.updatedAt} change on every save, so they are ignored.
 */
@Component
public class MasterDiffer {

    public enum Type { ADDED, REMOVED, CHANGED, REORDERED }

    /**
     * @param before null for ADDED
     * @param after  null for REMOVED
     */
    public record Change(Type type, String path, JsonNode before, JsonNode after) {}

    private static final Set<String> IGNORED = Set.of("/meta/version", "/meta/updatedAt");

    public List<Change> diff(JsonNode before, JsonNode after) {
        List<Change> changes = new ArrayList<>();
        compare(before, after, "", changes);
        return changes;
    }

    private void compare(JsonNode before, JsonNode after, String path, List<Change> out) {
        if (IGNORED.contains(path) || before.equals(after)) {
            return;
        }
        if (before.isObject() && after.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            before.propertyNames().forEach(keys::add);
            after.propertyNames().forEach(keys::add);
            for (String key : keys) {
                String child = path + "/" + key;
                JsonNode b = before.get(key);
                JsonNode a = after.get(key);
                if (b == null) {
                    out.add(new Change(Type.ADDED, child, null, a));
                } else if (a == null) {
                    out.add(new Change(Type.REMOVED, child, b, null));
                } else {
                    compare(b, a, child, out);
                }
            }
        } else if (before.isArray() && after.isArray()) {
            compareArrays(before, after, path, out);
        } else {
            out.add(new Change(Type.CHANGED, path, before, after));
        }
    }

    private void compareArrays(JsonNode before, JsonNode after, String path, List<Change> out) {
        if (allHaveIds(before) && allHaveIds(after)) {
            Map<String, JsonNode> b = byId(before);
            Map<String, JsonNode> a = byId(after);
            b.forEach((id, node) -> {
                if (!a.containsKey(id)) {
                    out.add(new Change(Type.REMOVED, path + "/" + id, node, null));
                }
            });
            a.forEach((id, node) -> {
                if (!b.containsKey(id)) {
                    out.add(new Change(Type.ADDED, path + "/" + id, null, node));
                }
            });
            // Order among the items present in both versions
            List<String> keptBefore = b.keySet().stream().filter(a::containsKey).toList();
            List<String> keptAfter = a.keySet().stream().filter(b::containsKey).toList();
            if (!keptBefore.equals(keptAfter)) {
                out.add(new Change(Type.REORDERED, path, ids(keptBefore), ids(keptAfter)));
            }
            keptAfter.forEach(id -> compare(b.get(id), a.get(id), path + "/" + id, out));
        } else if (allScalars(before) && allScalars(after)) {
            // e.g. skills or tags lists: report individual values added / removed
            List<JsonNode> removed = new ArrayList<>(before.values());
            after.values().forEach(removed::remove);
            List<JsonNode> added = new ArrayList<>(after.values());
            before.values().forEach(added::remove);
            removed.forEach(v -> out.add(new Change(Type.REMOVED, path, v, null)));
            added.forEach(v -> out.add(new Change(Type.ADDED, path, null, v)));
            if (removed.isEmpty() && added.isEmpty()) {
                out.add(new Change(Type.REORDERED, path, before, after));
            }
        } else {
            // Small lists of objects without IDs (profiles, metrics, languages): show old vs new whole
            out.add(new Change(Type.CHANGED, path, before, after));
        }
    }

    private static boolean allHaveIds(JsonNode array) {
        return array.values().stream().allMatch(n -> n.isObject() && n.path("id").isString());
    }

    private static boolean allScalars(JsonNode array) {
        return array.values().stream().allMatch(JsonNode::isValueNode);
    }

    private static Map<String, JsonNode> byId(JsonNode array) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        array.values().forEach(n -> map.put(n.get("id").stringValue(), n));
        return map;
    }

    private static JsonNode ids(List<String> ids) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        ids.forEach(node::add);
        return node;
    }
}

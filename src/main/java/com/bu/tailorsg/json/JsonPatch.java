package com.bu.tailorsg.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * RFC 6902 JSON Patch (add, remove, replace, move, copy, test) on Jackson 3 trees.
 *
 * <p>Why hand-written: the common Java libraries (zjsonpatch, java-json-tools) are built on Jackson 2 and
 * have been dormant since 2023 or earlier. The RFC is small, and every rule here is covered by tests based
 * on its Appendix A examples.
 *
 * <p>Patches are atomic: they apply to a copy, and the copy is returned only if every operation
 * succeeds. The input document is never modified.
 *
 * <p>How TailorSG uses {@code test}: patches built by the server start with guards such as
 * {@code {"op":"test","path":"/work/0/id","value":"work-1"}}. If the master resume changed and index 0
 * is now a different job, the patch fails cleanly instead of editing the wrong entry.
 */
public final class JsonPatch {

    private JsonPatch() {}

    /**
     * @param patch    a JSON array of operations
     * @param document the document to patch (not modified)
     * @return the patched copy
     * @throws JsonPatchException on the first operation that cannot be applied
     */
    public static JsonNode apply(JsonNode patch, JsonNode document) {
        if (patch == null || !patch.isArray()) {
            throw new JsonPatchException("A JSON Patch must be an array of operations.");
        }
        JsonNode result = document.deepCopy();
        int number = 0;
        for (JsonNode op : patch.values()) {
            number++;
            result = applyOne(result, op, number);
        }
        return result;
    }

    private static JsonNode applyOne(JsonNode doc, JsonNode op, int number) {
        if (!op.isObject()) {
            throw new JsonPatchException("Operation " + number + " is not an object.");
        }
        String name = requiredText(op, "op", number);
        JsonPointer path = pointer(op, "path", number);
        String where = "Operation " + number + " (" + name + " " + path + ")";
        // Members other than op/path/from/value are ignored, as the RFC requires (Appendix A.11).
        return switch (name) {
            case "add" -> add(doc, path, requiredValue(op, number).deepCopy(), where);
            case "remove" -> remove(doc, path, where);
            case "replace" -> {
                get(doc, path, where); // must exist
                JsonNode value = requiredValue(op, number).deepCopy();
                yield path.isRoot() ? value : add(remove(doc, path, where), path, value, where);
            }
            case "move" -> {
                JsonPointer from = pointer(op, "from", number);
                if (from.equals(path)) {
                    yield doc;
                }
                if (from.isProperPrefixOf(path)) {
                    throw new JsonPatchException(where + ": cannot move a value into one of its own children.");
                }
                JsonNode value = get(doc, from, where);
                yield add(remove(doc, from, where), path, value, where);
            }
            case "copy" -> add(doc, path, get(doc, pointer(op, "from", number), where).deepCopy(), where);
            case "test" -> {
                JsonNode actual = get(doc, path, where);
                JsonNode expected = requiredValue(op, number);
                if (!sameValue(actual, expected)) {
                    throw new JsonPatchException(where + ": expected " + expected + " but found " + actual + ".");
                }
                yield doc;
            }
            default -> throw new JsonPatchException("Operation " + number + ": unknown op '" + name
                    + "'. Use add, remove, replace, move, copy or test.");
        };
    }

    private static JsonNode add(JsonNode doc, JsonPointer path, JsonNode value, String where) {
        if (path.isRoot()) {
            return value; // RFC 6902 4.1: the value becomes the whole document
        }
        JsonNode parent = path.parent().resolve(doc);
        String token = path.lastToken();
        if (parent instanceof ObjectNode object) {
            object.set(token, value);
        } else if (parent instanceof ArrayNode array) {
            if (token.equals("-")) {
                array.add(value);
            } else {
                int index = JsonPointer.arrayIndex(token);
                if (index < 0 || index > array.size()) {
                    throw new JsonPatchException(where + ": index '" + token + "' is out of range (array has "
                            + array.size() + " items).");
                }
                array.insert(index, value);
            }
        } else {
            throw new JsonPatchException(where + ": " + path.parent() + " does not exist or is not an object or array.");
        }
        return doc;
    }

    private static JsonNode remove(JsonNode doc, JsonPointer path, String where) {
        if (path.isRoot()) {
            throw new JsonPatchException(where + ": cannot remove the whole document.");
        }
        get(doc, path, where);
        JsonNode parent = path.parent().resolve(doc);
        if (parent instanceof ObjectNode object) {
            object.remove(path.lastToken());
        } else if (parent instanceof ArrayNode array) {
            array.remove(JsonPointer.arrayIndex(path.lastToken()));
        }
        return doc;
    }

    private static JsonNode get(JsonNode doc, JsonPointer path, String where) {
        JsonNode node = path.resolve(doc);
        if (node == null) {
            throw new JsonPatchException(where + ": " + (path.isRoot() ? "the document" : path) + " does not exist.");
        }
        return node;
    }

    /** JSON equality per RFC 6902 4.6: numbers compare by value (1 equals 1.0), objects ignore key order. */
    static boolean sameValue(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                JsonNode other = b.get(e.getKey());
                if (other == null || !sameValue(e.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<JsonNode> left = a.values().iterator();
            Iterator<JsonNode> right = b.values().iterator();
            while (left.hasNext()) {
                if (!sameValue(left.next(), right.next())) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static String requiredText(JsonNode op, String member, int number) {
        JsonNode node = op.get(member);
        if (node == null || !node.isString()) {
            throw new JsonPatchException("Operation " + number + " needs a string \"" + member + "\".");
        }
        return node.stringValue();
    }

    private static JsonNode requiredValue(JsonNode op, int number) {
        JsonNode value = op.get("value");
        if (value == null) {
            throw new JsonPatchException("Operation " + number + " needs a \"value\".");
        }
        return value;
    }

    private static JsonPointer pointer(JsonNode op, String member, int number) {
        try {
            return JsonPointer.parse(requiredText(op, member, number));
        } catch (IllegalArgumentException e) {
            throw new JsonPatchException("Operation " + number + ": " + e.getMessage());
        }
    }
}

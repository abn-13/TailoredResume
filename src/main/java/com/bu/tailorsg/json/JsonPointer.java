package com.bu.tailorsg.json;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An RFC 6901 JSON Pointer, e.g. {@code /work/0/highlights/-}. Immutable.
 *
 * <p>Jackson has its own pointer class, but JSON Patch needs a few extra operations (parent, last
 * token, "is a prefix of") and strict errors, so this small version is easier to reason about.
 */
public final class JsonPointer {

    /** Array indexes per RFC 6901: "0" or no leading zeros. */
    private static final Pattern INDEX = Pattern.compile("0|[1-9][0-9]{0,8}");

    private final List<String> tokens;

    private JsonPointer(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static final JsonPointer ROOT = new JsonPointer(List.of());

    /** @throws IllegalArgumentException if the text is not a valid pointer */
    public static JsonPointer parse(String text) {
        if (text.isEmpty()) {
            return ROOT;
        }
        if (!text.startsWith("/")) {
            throw new IllegalArgumentException("A JSON Pointer must be empty or start with '/': \"" + text + "\"");
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : text.substring(1).split("/", -1)) {
            if (raw.matches(".*~([^01]|$).*")) {
                throw new IllegalArgumentException("Invalid escape in JSON Pointer \"" + text + "\": use ~0 for ~ and ~1 for /");
            }
            // Order matters (RFC 6901 section 4): ~1 first, then ~0, so "~01" becomes "~1", not "/".
            tokens.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return new JsonPointer(tokens);
    }

    public boolean isRoot() {
        return tokens.isEmpty();
    }

    public JsonPointer parent() {
        if (isRoot()) {
            throw new IllegalStateException("The root has no parent");
        }
        return new JsonPointer(tokens.subList(0, tokens.size() - 1));
    }

    public String lastToken() {
        return tokens.getLast();
    }

    public JsonPointer append(String token) {
        List<String> longer = new ArrayList<>(tokens);
        longer.add(token);
        return new JsonPointer(longer);
    }

    /** True if {@code other} is strictly inside this location (e.g. /a is a proper prefix of /a/b). */
    public boolean isProperPrefixOf(JsonPointer other) {
        return other.tokens.size() > tokens.size() && other.tokens.subList(0, tokens.size()).equals(tokens);
    }

    /** The node at this location, or null if any step is missing. */
    public JsonNode resolve(JsonNode document) {
        JsonNode current = document;
        for (String token : tokens) {
            if (current == null) {
                return null;
            }
            if (current.isObject()) {
                current = current.get(token);
            } else if (current.isArray()) {
                int index = arrayIndex(token);
                current = index >= 0 && index < current.size() ? current.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    /** Parses an array index token; -1 if it is not a valid index (including "-"). */
    public static int arrayIndex(String token) {
        return INDEX.matcher(token).matches() ? Integer.parseInt(token) : -1;
    }

    public static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        tokens.forEach(t -> sb.append('/').append(escape(t)));
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonPointer other && tokens.equals(other.tokens);
    }

    @Override
    public int hashCode() {
        return tokens.hashCode();
    }
}

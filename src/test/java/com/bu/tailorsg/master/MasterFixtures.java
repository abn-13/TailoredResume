package com.bu.tailorsg.master;

import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Test helpers: the bundled fake sample, plus small JSON Pointer edits so each test shows exactly
 * what it changes (e.g. {@code set(doc, "/basics/phone", "\"91234567\"")}).
 */
final class MasterFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private MasterFixtures() {}

    static String sampleText() {
        try {
            return new DefaultResourceLoader().getResource(MasterResumeService.SAMPLE)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A fresh, mutable copy of the sample every time. */
    static ObjectNode sample() {
        return (ObjectNode) JSON.readTree(sampleText());
    }

    /** Smallest valid document: the server fills meta. */
    static ObjectNode minimal() {
        return (ObjectNode) JSON.readTree("""
                {"basics": {"name": "Pat Lee", "email": "pat@example.com"}}""");
    }

    /**
     * Sets the value at {@code pointer} (parsed from JSON text). For arrays, the last segment may be
     * an index or "-" to append.
     */
    static ObjectNode set(ObjectNode doc, String pointer, String jsonValue) {
        JsonNode value = JSON.readTree(jsonValue);
        int slash = pointer.lastIndexOf('/');
        JsonNode parent = doc.at(pointer.substring(0, slash));
        String key = pointer.substring(slash + 1);
        if (parent instanceof ObjectNode object) {
            object.set(key, value);
        } else if (parent instanceof ArrayNode array) {
            if (key.equals("-")) {
                array.add(value);
            } else {
                array.set(Integer.parseInt(key), value);
            }
        } else {
            throw new IllegalArgumentException("No object or array at parent of " + pointer);
        }
        return doc;
    }

    static ObjectNode remove(ObjectNode doc, String pointer) {
        int slash = pointer.lastIndexOf('/');
        JsonNode parent = doc.at(pointer.substring(0, slash));
        String key = pointer.substring(slash + 1);
        if (parent instanceof ObjectNode object) {
            object.remove(key);
        } else if (parent instanceof ArrayNode array) {
            array.remove(Integer.parseInt(key));
        }
        return doc;
    }

    static String text(JsonNode doc) {
        return JSON.writeValueAsString(doc);
    }
}

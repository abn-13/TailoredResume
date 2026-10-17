package com.bu.tailorsg.json;

/**
 * A JSON Patch could not be applied. The document is left untouched (patches are all-or-nothing).
 * Messages are written for people: they name the operation number, its type and path.
 */
public class JsonPatchException extends RuntimeException {

    public JsonPatchException(String message) {
        super(message);
    }
}

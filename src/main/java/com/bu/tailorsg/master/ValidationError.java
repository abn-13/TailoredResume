package com.bu.tailorsg.master;

/**
 * One reason a master resume was rejected.
 *
 * @param path    JSON Pointer to the offending value, e.g. {@code /work/0/highlights/1/text}
 *                ("" is the document root). Same format the schema validator uses.
 * @param message what is wrong and, where possible, how to fix it
 * @param kind    which validation stage found it
 */
public record ValidationError(String path, String message, Kind kind) {

    public enum Kind {
        /** Not parseable JSON. */
        SYNTAX,
        /** P6: personal data that must never be stored. */
        PRIVACY,
        /** ID rules: format, uniqueness, never reused. */
        IDS,
        /** JSON Schema violation. */
        SCHEMA,
        /** Cross-field rules: references resolve, dates in order. */
        INTEGRITY
    }

    /** Path for display: "/" instead of an empty root pointer. */
    public String displayPath() {
        return path.isEmpty() ? "/" : path;
    }
}

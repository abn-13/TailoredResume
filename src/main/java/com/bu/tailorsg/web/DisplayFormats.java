package com.bu.tailorsg.web;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatting for values shown in pages, kept out of templates so it is testable and consistent. */
public final class DisplayFormats {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK).withZone(ZoneId.systemDefault());

    private DisplayFormats() {}

    /** e.g. "15 Oct 2026, 17:02" in the machine's time zone. */
    public static String dateTime(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant);
    }

    /** Strings as plain text, anything else as compact JSON, null as empty. */
    public static String value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isString() ? node.stringValue() : node.toString();
    }

    /** A JSON Pointer made readable: "/work/work-1/position" becomes "work › work-1 › position". */
    public static String path(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "(whole document)";
        }
        return String.join(" › ", pointer.substring(1).split("/"))
                .replace("~1", "/").replace("~0", "~");
    }
}

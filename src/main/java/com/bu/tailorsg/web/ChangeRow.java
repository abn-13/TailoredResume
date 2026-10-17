package com.bu.tailorsg.web;

import com.bu.tailorsg.master.MasterDiffer.Change;

import java.util.List;

/**
 * One row of a "what changes" table, ready for a template: readable path, values as text.
 * Long values (e.g. a whole imported section) are shortened; the full result is shown elsewhere.
 */
public record ChangeRow(String type, String path, String before, String after) {

    static final int MAX_VALUE_CHARS = 400;

    public static List<ChangeRow> of(List<Change> changes) {
        return changes.stream()
                .map(c -> new ChangeRow(c.type().name(), DisplayFormats.path(c.path()),
                        shorten(DisplayFormats.value(c.before())), shorten(DisplayFormats.value(c.after()))))
                .toList();
    }

    private static String shorten(String text) {
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS - 1) + "…";
    }
}

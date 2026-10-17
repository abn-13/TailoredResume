package com.bu.tailorsg.json;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and printing of JSON that a person edits by hand (the master resume editor now, the JobSpec
 * and TailoredResume editors later).
 */
@Component
public class JsonText {

    private final ObjectReader strictReader;
    private final ObjectWriter prettyWriter;

    public JsonText(JsonMapper jsonMapper) {
        // Hand-edited JSON easily ends up with the same key twice; by default the last one silently wins.
        this.strictReader = jsonMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        // Match JSON.stringify(value, null, 2): `"key": value` and one array element per line.
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator(""))
                .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        this.prettyWriter = jsonMapper.writer().with(printer);
    }

    /**
     * Parses text that must be one JSON value.
     *
     * @throws JsonSyntaxException with the line and column of the problem, and the text just before it
     */
    public JsonNode parseStrict(String text) {
        try {
            return strictReader.readTree(text);
        } catch (JacksonException e) {
            TokenStreamLocation at = e.getLocation();
            int line = at == null ? -1 : at.getLineNr();
            int column = at == null ? -1 : at.getColumnNr();
            throw new JsonSyntaxException(plainMessage(e.getOriginalMessage()), line, column, textBefore(text, line, column));
        }
    }

    private static final Pattern CONTROL_CHAR = Pattern.compile("Illegal unquoted character \\(\\(CTRL-CHAR, code (\\d+)\\)\\)");

    /**
     * Jackson's wording for the commonest hand-editing mistake, in plain English: a line break typed or pasted
     * inside quoted text. (Browsers send textarea line breaks as CR LF, so it is usually reported as code 13.)
     */
    static String plainMessage(String jacksonMessage) {
        String message = jacksonMessage == null ? "" : jacksonMessage;
        // Commas are the other common slip: left behind after deleting the last field or item, or forgotten.
        if (message.startsWith("Unexpected character ('}'") && message.contains("double-quote to start property name")) {
            return "An extra comma before }: delete the last comma before the }.";
        }
        if (message.startsWith("Unexpected character (']'") && message.contains("expected a valid value")) {
            return "An extra comma before ]: delete the last comma before the ].";
        }
        if (message.contains("was expecting comma to separate")) {
            return "A missing comma: add one between the two entries (usually at the end of the line above).";
        }
        Matcher control = CONTROL_CHAR.matcher(message);
        if (!control.find()) {
            return jacksonMessage;
        }
        String code = control.group(1);
        return code.equals("10") || code.equals("13")
                ? "A line break inside quoted text. Each text value must stay on one line: join it back up with the line below."
                : "An invisible control character (code " + code + ") inside quoted text. Delete it.";
    }

    private static final int EXCERPT_CHARS = 40;

    /**
     * Up to 40 characters just before the problem, so the user can find it with Ctrl+F / Cmd+F. When the line
     * holds only indentation before the problem (e.g. a lone "}"), the end of the previous non-blank line is
     * quoted instead: that's where a stray or missing comma is.
     */
    static String textBefore(String text, int line, int column) {
        if (text == null || line < 1 || column < 1) {
            return null;
        }
        String[] lines = text.split("\r\n|\r|\n", -1); // the same line breaks Jackson counts
        if (line > lines.length) {
            return null;
        }
        String content = lines[line - 1];
        String before = tail(content, Math.min(content.length(), column - 1));
        for (int previous = line - 2; before.isEmpty() && previous >= 0; previous--) {
            before = tail(lines[previous], lines[previous].length());
        }
        return before.isEmpty() ? null : before;
    }

    /** The last 40 or so characters of {@code content} before {@code end}, starting at a word boundary. */
    private static String tail(String content, int end) {
        int start = Math.max(0, end - EXCERPT_CHARS);
        if (start > 0 && !Character.isWhitespace(content.charAt(start - 1))) {
            int nextSpace = content.indexOf(' ', start);
            start = nextSpace < 0 || nextSpace >= end ? start : nextSpace; // don't start mid-word
        }
        return content.substring(start, end).strip();
    }

    public String pretty(JsonNode node) {
        return prettyWriter.writeValueAsString(node);
    }

    /**
     * Invalid JSON text. Line and column are 1-based, or -1 when unknown. {@code textBefore} is the text just
     * before the problem on that line (null if none), because the editors don't show line numbers.
     */
    public static class JsonSyntaxException extends RuntimeException {
        private final int line;
        private final int column;
        private final String textBefore;

        public JsonSyntaxException(String message, int line, int column) {
            this(message, line, column, null);
        }

        public JsonSyntaxException(String message, int line, int column, String textBefore) {
            super(message);
            this.line = line;
            this.column = column;
            this.textBefore = textBefore;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }

        /** e.g. "Line 12, column 5: Unexpected character ... Search the editor for: "skills": [" */
        public String describe() {
            return (line > 0 ? "Line " + line + ", column " + column + ": " : "") + getMessage()
                    + (textBefore == null ? "" : " Search the editor for: " + textBefore);
        }
    }
}

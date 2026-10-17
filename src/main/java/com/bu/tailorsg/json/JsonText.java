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
     * @throws JsonSyntaxException with the line and column of the problem
     */
    public JsonNode parseStrict(String text) {
        try {
            return strictReader.readTree(text);
        } catch (JacksonException e) {
            TokenStreamLocation at = e.getLocation();
            throw new JsonSyntaxException(e.getOriginalMessage(),
                    at == null ? -1 : at.getLineNr(), at == null ? -1 : at.getColumnNr());
        }
    }

    public String pretty(JsonNode node) {
        return prettyWriter.writeValueAsString(node);
    }

    /** Invalid JSON text. Line and column are 1-based, or -1 when unknown. */
    public static class JsonSyntaxException extends RuntimeException {
        private final int line;
        private final int column;

        public JsonSyntaxException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }

        /** e.g. "Line 12, column 5: Unexpected character ..." */
        public String describe() {
            return (line > 0 ? "Line " + line + ", column " + column + ": " : "") + getMessage();
        }
    }
}

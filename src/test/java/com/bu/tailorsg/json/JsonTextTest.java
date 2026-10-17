package com.bu.tailorsg.json;

import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Syntax errors in hand-edited JSON must say what is wrong and where, in words a person can act on. */
class JsonTextTest {

    private final JsonText jsonText = new JsonText(JsonMapper.builder().build());

    /**
     * Regression (2026-10-17): a line break typed into a bullet in the patch editor gave "Illegal unquoted
     * character ((CTRL-CHAR, code 13))" with a line number the editor doesn't show. Browsers send textarea
     * line breaks as CR LF, hence code 13.
     */
    @Test
    void aLineBreakInsideQuotedTextIsExplainedAndQuoted() {
        String edited = "[\r\n  {\r\n    \"text\": \"Graduate Certificates in Digital Solutions Development: Web\r\nApplications\"\r\n  }\r\n]";

        JsonSyntaxException e = catchThrowableOfType(JsonSyntaxException.class, () -> jsonText.parseStrict(edited));

        assertThat(e.line()).isEqualTo(3);
        assertThat(e.describe())
                .startsWith("Line 3, column")
                .contains("A line break inside quoted text")
                .doesNotContain("CTRL-CHAR")
                .endsWith("Search the editor for: in Digital Solutions Development: Web");
    }

    /** Regression (2026-10-17): deleting an entry's last field left "...", before "}" with no hint where. */
    @Test
    void aCommaBeforeAClosingBraceIsExplainedAndTheLineAboveIsQuoted() {
        String edited = "{\n  \"extras\": [\n    {\n      \"type\": \"publication\",\n      \"text\": \"Applied Thermal Engineering, 308, 133491\",\n    }\n  ]\n}";

        JsonSyntaxException e = catchThrowableOfType(JsonSyntaxException.class, () -> jsonText.parseStrict(edited));

        assertThat(e.describe())
                .startsWith("Line 6, column 5: An extra comma before }")
                .endsWith("Search the editor for: Thermal Engineering, 308, 133491\",");
    }

    @Test
    void otherCommaMistakesAreExplainedToo() {
        assertThat(catchThrowableOfType(JsonSyntaxException.class, () -> jsonText.parseStrict("[\"Java\",\n]")).describe())
                .contains("An extra comma before ]").contains("Search the editor for: [\"Java\",");
        assertThat(catchThrowableOfType(JsonSyntaxException.class, () -> jsonText.parseStrict("{\"a\": 1\n \"b\": 2}")).describe())
                .contains("A missing comma");
    }

    @Test
    void otherErrorsKeepJacksonsMessageAndStillQuoteTheText() {
        JsonSyntaxException e = catchThrowableOfType(JsonSyntaxException.class,
                () -> jsonText.parseStrict("{\n  \"skills\": [\"Java\",, \"Go\"]\n}"));

        assertThat(e.describe()).startsWith("Line 2, column").contains("Search the editor for: \"skills\": [\"Java\",");
    }

    @Test
    void textBeforeIsOmittedWhenThereIsNothingToQuote() {
        assertThat(JsonText.textBefore("{\"a\": 1}", 5, 1)).isNull();       // no such line
        assertThat(JsonText.textBefore("   x", 1, 3)).isNull();            // only spaces, and no line above
        assertThat(new JsonSyntaxException("Bad", -1, -1).describe()).isEqualTo("Bad");
    }
}

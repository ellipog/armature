package dev.ellipog.armature.api.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Where a parsed file says its values are, and where a broken one points.
 *
 * <p>Positions are the whole feature of {@link JsonDocument} — a validator that reports
 * {@code stone_age.json:14:9} and means it — so the line table and the error carets are pinned
 * directly rather than only through a validator's messages. That includes the awkward inputs:
 * tabs, CRLF endings, and the rewound unterminated string, whose caret has its own history.
 */
@DisplayName("a parsed file's line table and error positions")
class JsonDocumentTest {

    @Test
    @DisplayName("lineText reads each line back, tabs expanded, out-of-range empty")
    void lineTextReadsLines() throws JsonParseException {
        JsonDocument document = JsonDocument.parse("a.json", "{\n\t\"x\": 1\n}");
        assertEquals("{", document.lineText(1));
        assertEquals("    \"x\": 1", document.lineText(2), "a tab expands, or the caret misaligns");
        assertEquals("}", document.lineText(3));
        assertEquals("", document.lineText(0));
        assertEquals("", document.lineText(4));
    }

    @Test
    @DisplayName("CRLF endings do not move the text")
    void crlfEndings() throws JsonParseException {
        JsonDocument document = JsonDocument.parse("a.json", "{\r\n\"x\": 1\r\n}");
        assertEquals("{", document.lineText(1).strip());
        assertEquals(2, document.location("$.x").orElseThrow().line());
    }

    @Test
    @DisplayName("a value's location names its line")
    void valueLocations() throws JsonParseException {
        JsonDocument document = JsonDocument.parse("a.json", "{\n\"a\": 1,\n\"b\": [\n10\n]\n}");
        assertEquals(2, document.location("$.a").orElseThrow().line());
        assertEquals(4, document.location("$.b[0]").orElseThrow().line());
    }

    @Test
    @DisplayName("a syntax error points at the offending line and column")
    void syntaxErrorPosition() {
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> JsonDocument.parse("a.json", "{\n\"a\": 1,\n\"b\": ,\n}"));
        assertEquals(3, thrown.location().line());
    }

    @Test
    @DisplayName("an unterminated string crossing a newline points at the line after the break")
    void unterminatedStringPosition() {
        // The parser rewinds to the newline, and the caret is reported on the line the string ran
        // onto with the run-on text beside it. Pinned exactly as-is: the position machinery was
        // rewritten around this case, and any drift here reads as a fault in the wrong line.
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> JsonDocument.parse("a.json", "{\"a\": \"one\ntwo\"}"));
        assertEquals(2, thrown.location().line());
        assertEquals("two\"}", documentLineOf(thrown));
    }

    @Test
    @DisplayName("trailing text after the document points at it")
    void trailingTextPosition() {
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> JsonDocument.parse("a.json", "{\"a\": 1}\nxyz"));
        assertEquals(2, thrown.location().line());
    }

    /** The offending line the message carries: its second line, unindented. */
    private static String documentLineOf(JsonParseException thrown) {
        return thrown.getMessage().split("\n")[1].strip();
    }
}

package dev.ellipog.armature.client.ui.inspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed fields' rules: what each kind accepts, what it refuses, and what it says when it refuses.
 *
 * <h2>Why the refusals are asserted as much as the accepts</h2>
 *
 * <p>Because the failure that matters is quiet otherwise: a panel that commits "twenty" as a count, or
 * "" as a flag, has not drawn anything wrong -- it has written a file the loader will refuse later,
 * three screens away from the field that caused it. The parse failing <b>here</b>, with a message that
 * names the field, is the whole reason the contract has an error half.
 */
@DisplayName("the inspector's typed fields")
class InspectFieldTest {

    @Test
    @DisplayName("a text field takes anything, and formats null as empty")
    void textTakesAnything() {
        InspectField<String> field = InspectField.text("Title");

        assertTrue(field.parse("The Stone Age").ok());
        assertEquals("The Stone Age", field.parse("The Stone Age").value());
        assertTrue(field.parse("").ok(), "an empty string is the caller's call to accept or refuse");
        assertEquals("", field.format(null), "a missing value is an empty field, not the word null");
        assertEquals("kept", field.format("kept"));
    }

    @Test
    @DisplayName("an integer field parses, bounds, and refuses what is not a number")
    void integerFieldBounds() {
        InspectField<Integer> field = InspectField.integer("X", 0, 4096);

        assertEquals(0, field.parse("0").value());
        assertEquals(4096, field.parse("4096").value());
        assertEquals(12, field.parse(" 12 ").value(), "surrounding space is not part of the number");
        assertNull(field.parse("4100").value(), "above the bound is refused");
        assertFalse(field.parse("4100").ok());
        assertNull(field.parse("-12").value(), "and so is below it");
        assertFalse(field.parse("-12").ok());
        assertFalse(field.parse("twenty").ok(), "a word is not a number");
        assertFalse(field.parse("2.5").ok(), "and neither is a decimal");
        assertFalse(field.parse("").ok(), "an empty field is not a value either");
        assertTrue(field.parse("").error().contains("X"), "the refusal names the field");
    }

    @Test
    @DisplayName("an integer field refuses a reversed range when it is built, not when it is parsed")
    void integerFieldRejectsABadRange() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> InspectField.integer("X", 10, 0));
    }

    @Test
    @DisplayName("a flag field takes the usual spellings and refuses a typo rather than guessing")
    void flagFieldSpellings() {
        InspectField<Boolean> field = InspectField.flag("Optional");

        for (String yes : new String[] {"true", "TRUE", "yes", "on", "1"}) {
            assertEquals(Boolean.TRUE, field.parse(yes).value(), () -> "accepted spelling: " + yes);
        }
        for (String no : new String[] {"false", "No", "OFF", "0"}) {
            assertEquals(Boolean.FALSE, field.parse(no).value(), () -> "accepted spelling: " + no);
        }
        assertFalse(field.parse("ture").ok(), "a typo is not a third state to be guessed at");
        assertFalse(field.parse("").ok(), "and neither is nothing");
        assertEquals("false", field.format(Boolean.FALSE));
        assertEquals("", field.format(null), "an absent flag formats as absent");
    }

    @Test
    @DisplayName("a decimal field takes a fraction, and refuses what is not a finite number")
    void decimalFieldTakesFractions() {
        InspectField<Double> field = InspectField.decimal("Icon scale", 0.25, 4.0);

        assertEquals(1.5, field.parse("1.5").value());
        assertEquals(1.0, field.parse(" 1 ").value(), "a whole number is a decimal with no fraction");
        assertEquals(0.25, field.parse("0.25").value());
        assertNull(field.parse("5").value(), "above the bound is refused");
        assertFalse(field.parse("5").ok());
        assertFalse(field.parse("scale").ok());
        assertFalse(field.parse("NaN").ok(), "not-a-number is not a value");
        assertFalse(field.parse("Infinity").ok(), "and neither is infinity");
        assertFalse(field.parse("").ok());
        assertTrue(field.parse("2.5").ok(), "and a value inside the bounds parses");
    }

    @Test
    @DisplayName("what a field formats, it parses back to the same value")
    void formatAndParseAgree() {
        InspectField<Integer> integer = InspectField.integer("Size", 12, 128);
        InspectField<Boolean> flag = InspectField.flag("Sequential");

        for (int value : new int[] {12, 32, 128}) {
            InspectField.Result<Integer> back = integer.parse(integer.format(value));
            assertTrue(back.ok());
            assertEquals(value, back.value(), "the shown text is the text the parse accepts");
        }
        for (boolean value : new boolean[] {true, false}) {
            InspectField.Result<Boolean> back = flag.parse(flag.format(value));
            assertTrue(back.ok());
            assertEquals(value, back.value(), "the shown text is the text the parse accepts");
        }
    }

    @Test
    @DisplayName("a result is exactly one of value or error, never both and never neither")
    void resultsAreExactlyOneAnswer() {
        InspectField.Result<String> ok = InspectField.Result.ok("fine");
        InspectField.Result<String> bad = InspectField.Result.bad("not fine");

        assertTrue(ok.ok() && ok.error() == null && ok.value() != null, "an ok carries a value");
        assertFalse(bad.ok() && bad.value() != null, "a refusal carries a message and no value");
        assertEquals("not fine", bad.error());
    }
}

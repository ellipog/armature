package dev.ellipog.armature.client.ui.inspect;

import java.util.Objects;

/**
 * One row of a property inspector: a label on the left, and what that label is about on the right.
 *
 * <h2>What this is, and what it is deliberately not</h2>
 *
 * <p>A description of a panel's contents, the way a chapter list is a description of its rows: the
 * drawing and the input are the screen's, this is the part a test can hold. It knows nothing about what
 * the values mean -- a caller describes its own object one row at a time, and the same nine kinds have
 * now described colours and entries without either of them teaching this class a field name.
 *
 * <p>Not a widget, and not a value holder: {@link #value} is the text to <i>show</i>. A caller that
 * wants a row edited hands the field's strip to a text field of its own and commits on the field's
 * submit -- which is the whole of "per field, on commit", and the reason there is no pending-edit state
 * here to go stale.
 *
 * <h2>The kinds, and where the control sits</h2>
 *
 * <p>Three shapes, told apart by where the control is. A {@link Kind#FIELD} or {@link Kind#TOGGLE} row
 * is a label with a control in a strip at its right -- the strip is {@link #STRIP_WIDTH} wide and
 * reserved by the layout, so the label can never run under it. A {@link Kind#ACTION} row is itself the
 * control, which is what an "Add" wants. A {@link Kind#VALUE} row is a label with text after it and no
 * control at all. And a {@link Kind#HEADING} or {@link Kind#WARNING} is a section's name -- the warning
 * being the same thing in the voice that says something is wrong, which is what the fallback under an
 * unknown type asks for.
 *
 * <p>{@code STEPPER} was a kind here until the settings page stopped using rows: it described a
 * label with a stepped number in its strip, the dock never produced one, and a kind nothing draws is a
 * kind a reader has to check is unused before changing anything. The settings page's sliders and
 * steppers are its own, where the arithmetic that says where an arrow is lives beside the drawing
 * that reads it.
 */
public record InspectRow(String key, Kind kind, String label, String value) {

    /** How wide a row's control strip is, and its inset from the row's right edge. */
    public static final int STRIP_WIDTH = 96;
    public static final int STRIP_INSET = 2;

    public enum Kind {
        /** A label with an editable value in the strip at its right. */
        FIELD,
        /** A label with a read-only value after it, and no control. */
        VALUE,
        /** A label with a two-state control in the strip. */
        TOGGLE,
        /** The whole row is one control. */
        ACTION,
        /** A section's name. */
        HEADING,
        /** A section's name for one entry of a list, with its own controls in the strip. */
        ENTRY,
        /** A section's name, in the voice that says something is wrong. */
        WARNING,
        /** A label holding a value too long or too strange for a field, editable as a whole. */
        RAW
    }

    public InspectRow {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
    }

    /** A label with an editable value; {@code value} is what the field starts showing. */
    public static InspectRow field(String key, String label, String value) {
        return new InspectRow(key, Kind.FIELD, label, value == null ? "" : value);
    }

    /** A label with a read-only value after it. */
    public static InspectRow value(String key, String label, String value) {
        return new InspectRow(key, Kind.VALUE, label, value == null ? "" : value);
    }

    /** A label with a two-state control; the label is the state, the button is the change. */
    public static InspectRow toggle(String key, String label) {
        return new InspectRow(key, Kind.TOGGLE, label, "");
    }

    /** A row that is itself one control, labelled. */
    public static InspectRow action(String key, String label) {
        return new InspectRow(key, Kind.ACTION, label, "");
    }

    /** A section's name. */
    public static InspectRow heading(String key, String label) {
        return new InspectRow(key, Kind.HEADING, label, "");
    }

    /**
     * One entry of a list -- a task, a reward -- named by its own heading and carrying its own
     * controls in the strip: duplicate and remove, or whatever the list's owner places there.
     */
    public static InspectRow entry(String key, String label) {
        return new InspectRow(key, Kind.ENTRY, label, "");
    }

    /** A section's name that is bad news, under which a fallback's rows go. */
    public static InspectRow warning(String key, String label) {
        return new InspectRow(key, Kind.WARNING, label, "");
    }

    /**
     * A value shown as it stands and edited as a whole -- the raw-JSON fallback's row.
     *
     * <p>The label is what the thing is; the value is everything this build cannot take apart, shown
     * rather than summarised, because a summary of a field the caller did not write is a field silently
     * dropped.
     */
    public static InspectRow raw(String key, String label, String value) {
        return new InspectRow(key, Kind.RAW, label, value == null ? "" : value);
    }

    /** Whether this row's control goes in the strip at its right, which the layout reserves room for. */
    public boolean hasStrip() {
        return kind == Kind.FIELD || kind == Kind.TOGGLE || kind == Kind.RAW
                || kind == Kind.ENTRY;
    }

    /** Whether the whole row is the control. */
    public boolean isControl() {
        return kind == Kind.ACTION;
    }

    /** Whether this row is a section's name, of either voice. */
    public boolean isHeading() {
        return kind == Kind.HEADING || kind == Kind.WARNING;
    }

    /** Whether this row names one entry of a list, and carries that entry's controls. */
    public boolean isEntry() {
        return kind == Kind.ENTRY;
    }

    /** Whether this row is a warning -- the voice that says something is wrong. */
    public boolean isWarning() {
        return kind == Kind.WARNING;
    }
}

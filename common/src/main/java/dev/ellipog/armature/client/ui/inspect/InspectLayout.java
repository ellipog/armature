package dev.ellipog.armature.client.ui.inspect;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;

import java.util.List;
import java.util.Objects;

/**
 * The property inspector's composition: rows into a stack, and where each row's control sits.
 *
 * <h2>Why this is a layout and not a panel</h2>
 *
 * <p>Because everything worth being wrong about is arithmetic on integers -- does a row's strip fit its
 * row, does the stack count every row, does a section's name sit inside the column it names -- and
 * arithmetic on integers is the thing this project's tests can hold without a client. The screen that
 * draws an inspector takes a {@link Layout} from here and asks it where each row went; the drawing
 * itself is the screen's, because a drawing method cannot be called without a running game.
 *
 * <p>The same shape as the party roster's and the tools panel's lists, and that is the point: this is
 * the third list in this codebase to want "a labelled row with a control in it", so the mechanics stop
 * being a panel's private detail and become the toolkit's. Gaps go <i>before</i> each row rather than
 * after, so a fold -- which is a caller rebuilding its row list without the folded section -- leaves no
 * stray gap where the section used to be.
 */
public final class InspectLayout {

    /** One row of values, or a heading. */
    public static final int ROW_HEIGHT = 18;

    /** A section's name, which is drawn larger by being its own band. */
    public static final int HEADING_HEIGHT = 15;

    /** Between a section and what follows it, and between two sections. */
    public static final int SECTION_GAP = 7;

    /** Between two ordinary rows. */
    public static final int ROW_GAP = 1;

    private InspectLayout() {
    }

    /**
     * The rows as an unbuilt stack.
     *
     * <p>A heading's gap is the section gap, an ordinary row's is the row gap, and a heading following a
     * heading -- a section directly inside another's tail -- still reads as separated, because the gap
     * before a row is decided by the row that follows it and not by the one before.
     */
    public static Stack stack(List<InspectRow> rows) {
        Objects.requireNonNull(rows, "rows");
        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            InspectRow row = rows.get(i);
            if (i > 0) {
                stack.gap(row.isHeading() ? SECTION_GAP : ROW_GAP);
            }
            switch (row.kind()) {
                case HEADING, WARNING -> stack.row(row.key(), HEADING_HEIGHT);
                case ACTION, VALUE -> stack.row(row.key(), ROW_HEIGHT);
                case FIELD, TOGGLE, STEPPER, RAW -> stack.row(row.key(), ROW_HEIGHT, stripRoom());
                // An entry's name is heading-tall and carries a strip: its controls belong on the line
                // that says what they act on, not on a row of their own.
                case ENTRY -> stack.row(row.key(), HEADING_HEIGHT, stripRoom());
            }
        }
        return stack;
    }

    /** The rows laid out into a column of the given width. */
    public static Layout build(List<InspectRow> rows, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(rows).build(Math.max(0, width), measure);
    }

    /** How wide a row's control strip is, and its inset from the row's right edge. */
    public static Insets stripRoom() {
        return new Insets(0, 0, InspectRow.STRIP_WIDTH + InspectRow.STRIP_INSET * 2, 0);
    }

    /**
     * Where a row's control goes: in the gap its row reserved, against the column's edge.
     *
     * <p>A {@code FIELD} strip is narrowed by the two insets, so a field's box sits inside the reserved
     * room rather than flush against the row beside it. The strip is capped at the row's own width, so a
     * column too narrow for the full strip loses strip rather than label.
     */
    public static Slot strip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(InspectRow.STRIP_WIDTH, Math.max(0, row.width() - InspectRow.STRIP_INSET));
        return new Slot(row.key(), row.right() - width, row.y(), width, row.height());
    }

    /**
     * A row's control strip, split into two equal buttons.
     *
     * <p>For a row that carries more than one control -- an entry's Copy and Remove -- drawn by the
     * panel and hit-tested by the screen, the same shape as the radius stepper: one derivation, so what
     * is drawn is what is pressed. A list row hosts one widget (the view matches a widget to a row by
     * key), so a row with two controls draws them itself rather than pretending to host both.
     */
    public static List<Slot> stripHalves(Slot row) {
        Objects.requireNonNull(row, "row");
        Slot strip = strip(row);
        int half = Math.max(0, (strip.width() - HALF_GAP) / 2);
        Slot left = new Slot(row.key() + "#left", strip.x(), strip.y(), half, strip.height());
        Slot right = new Slot(row.key() + "#right", strip.x() + half + HALF_GAP, strip.y(),
                Math.max(0, strip.width() - half - HALF_GAP), strip.height());
        return List.of(left, right);
    }

    /** Between the two halves of a split strip. */
    public static final int HALF_GAP = 2;

    /**
     * A slot where it will be drawn: the list's own coordinates put through the viewport it is drawn in.
     *
     * <p>The one mapping, for the same reason it is one mapping everywhere else in this codebase: a
     * row's slot is in the list's space -- its y is a distance down the content, not down the screen --
     * and a caller that forgets places its controls in a panel nobody can see. The tools panel carried
     * this method first; when that list moves onto this class, its copy goes.
     */
    public static Slot onScreen(Viewport view, Slot slot) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(slot, "slot");
        return new Slot(slot.key(), view.screenX(slot.x()), view.screenY(slot.y()),
                slot.width(), slot.height());
    }
}

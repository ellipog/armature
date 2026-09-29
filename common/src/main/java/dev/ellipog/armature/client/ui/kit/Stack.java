package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A vertical stack of elements, laid out in one pass.
 *
 * <h2>What it replaces</h2>
 *
 * <p>{@code int y = bodyTop - scroll; y = drawParagraphs(...); y += 8; y = heading(...); y +=
 * ROW_ADVANCE;} -- repeated in a screen, once to draw and once to measure, with a comment asking the
 * two to be kept in step. That is a layout engine, and it is a layout engine living in a UI class
 * where no test can reach it, because the class needs a running game to instantiate.
 *
 * <h2>The rule that makes measure and draw agree</h2>
 *
 * <p>{@link #build} places every element and returns the positions <b>and</b> the height together.
 * There is no measure-only mode to keep in step, because there is nothing to keep in step: asking how
 * tall the content is and asking where the rows are are the same call. A two-pass API would be the
 * original defect with better spelling.
 *
 * <h2>Alignment is per element, and it is not decoration</h2>
 *
 * <p>{@link Align#STRETCH} versus {@link Align#LEFT} is the difference between a row that fills the
 * column and a label that sits at the left of it, and that difference is what a layout is <i>for</i>.
 * Retrofitting it would touch every call site, so it is here from the first version -- along with
 * {@link Insets} per element, a minimum height, and an explicit width for a block that should not fill
 * its column.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>No horizontal flow.</b> Rows of items side by side belong in a row layout, not bolted on
 *       here. {@link #block} takes an explicit width and an alignment, which covers the cases that
 *       actually come up without spending a flexible-box model on them.</li>
 *   <li><b>No wrapping of the column itself.</b> A stack is given a width and uses it.</li>
 *   <li><b>No drawing.</b> It produces {@link Slot}s. What draws them is the caller's, which is what
 *       keeps this file and everything it depends on free of the game.</li>
 * </ul>
 *
 * <p><b>Game-free by design.</b> An element is a string, some numbers, and an alignment.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * Layout layout = Stack.stack()
 *         .paragraph(entry.description())
 *         .gap(8)
 *         .heading("TASKS")
 *         .row("task:0", 22)
 *         .row("task:1", 22)
 *         .gap(10)
 *         .heading("REWARDS")
 *         .row("reward:0", 22)
 *         .build(bodyWidth, measure);
 * </pre>
 */
public final class Stack {

    /** Where an element sits across the column. */
    public enum Align {

        /** Fills the column, less the insets. The default for anything a caller can click. */
        STRETCH,

        /** As wide as its content, at the left of the column. */
        LEFT,

        /** As wide as its content, centred. */
        CENTRE,

        /** As wide as its content, at the right of the column. */
        RIGHT
    }

    /**
     * One pending element.
     *
     * <p>A private record rather than four parallel lists, because parallel lists drifting out of step
     * is the class of bug this whole file is about.
     */
    private record Element(Kind kind, Object key, String text, int width, int height,
                           Insets insets, Align align) {
    }

    private enum Kind {
        /** Text, wrapped to the column and measured. */
        TEXT,
        /** A fixed-height box, usually where a widget goes. */
        BLOCK,
        /** Height and nothing else. */
        GAP,
        /** A one-pixel rule across the column. */
        DIVIDER
    }

    private final List<Element> elements = new ArrayList<>();

    private Stack() {
    }

    /** A new, empty stack. */
    public static Stack stack() {
        return new Stack();
    }

    // ------------------------------------------------------------------
    // Adding elements. All of these return this, so a layout reads as one expression.
    // ------------------------------------------------------------------

    /** Empty space of a given height. */
    public Stack gap(int height) {
        return add(new Element(Kind.GAP, null, "", 0, height, Insets.NONE, Align.STRETCH));
    }

    /** A one-pixel rule across the column, plus the space either side of it. */
    public Stack divider(int spaceAbove, int spaceBelow) {
        return gap(spaceAbove)
                .add(new Element(Kind.DIVIDER, null, "", 0, 1, Insets.NONE, Align.STRETCH))
                .gap(spaceBelow);
    }

    /** A section label: space above it, and a little below so it does not touch its content. */
    public Stack heading(String label) {
        return gap(8).text(null, label, Align.LEFT).gap(4);
    }

    /**
     * A paragraph of prose, wrapped to the column.
     *
     * <p>No space above it, which is the caller's to add: two paragraphs in a row should not each carry
     * their own leading margin, or the gap between them is twice what it reads as intending.
     */
    public Stack paragraph(String prose) {
        return text(null, prose, Align.LEFT);
    }

    /** A paragraph a caller can click. See {@link #row} for how keys work. */
    public Stack paragraph(Object key, String prose) {
        return text(key, prose, Align.LEFT);
    }

    /** Text with a key and an alignment of your choosing. The general form of the three above. */
    public Stack text(Object key, String text, Align align) {
        return add(new Element(Kind.TEXT, key, Objects.requireNonNull(text, "text"),
                0, 0, Insets.NONE, Objects.requireNonNull(align, "align")));
    }

    /**
     * A full-width row of a given height, usually where a widget goes.
     *
     * <p>The key is what a later {@link Layout#at} or {@link Layout#slot} finds it by, and it is what
     * ties a placed rectangle to the thing the caller will draw there. Not required: a row that is
     * drawn but never clicked, such as a reward's icon strip, passes {@code null}.
     */
    public Stack row(Object key, int height) {
        return block(key, 0, height, Align.STRETCH);
    }

    /** A full-width row with padding of its own. */
    public Stack row(Object key, int height, Insets insets) {
        return block(key, 0, height, Align.STRETCH, insets);
    }

    /**
     * A box that does not fill the column: an explicit width, and where it sits.
     *
     * <p>A {@code width} of zero means "as wide as the column allows", which is only useful together
     * with {@link Align#STRETCH}; for any other alignment it would be meaningless, so it is treated as
     * filling.
     */
    public Stack block(Object key, int width, int height, Align align) {
        return block(key, width, height, align, Insets.NONE);
    }

    /** A box with an explicit width, a position and its own padding. */
    public Stack block(Object key, int width, int height, Align align, Insets insets) {
        Objects.requireNonNull(align, "align");
        Objects.requireNonNull(insets, "insets");
        if (height < 0) {
            throw new IllegalArgumentException("height must not be negative: " + height);
        }
        if (width < 0) {
            throw new IllegalArgumentException("width must not be negative: " + width);
        }
        return add(new Element(Kind.BLOCK, key, "", width, height, insets, align));
    }

    // ------------------------------------------------------------------
    // Laying out
    // ------------------------------------------------------------------

    /**
     * Places every element in a column of {@code width}, and reports the height.
     *
     * @param width   the column's width in pixels
     * @param measure how wide the text is. Ignored by every element but {@link Kind#TEXT}.
     * @throws IllegalArgumentException if the width is negative
     */
    public Layout build(int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        if (width < 0) {
            throw new IllegalArgumentException("width must not be negative: " + width);
        }

        List<Slot> slots = new ArrayList<>(elements.size());
        int y = 0;

        for (Element element : elements) {
            int outerHeight;
            int x;
            int slotWidth;

            switch (element.kind()) {
                case GAP -> {
                    y += element.height();
                    continue;
                }
                case DIVIDER -> {
                    x = 0;
                    slotWidth = width;
                    outerHeight = 1;
                }
                case TEXT -> {
                    // The wrap decides the height, and the height is the wrap's own answer -- not a
                    // second estimate. See the class note.
                    List<String> lines = TextWrap.wrap(element.text(), Math.max(0, width), measure);
                    int contentWidth = 0;
                    for (String line : lines) {
                        contentWidth = Math.max(contentWidth, measure.width(line));
                    }
                    outerHeight = lines.size() * measure.lineHeight() + element.insets().vertical();
                    slotWidth = element.align() == Align.STRETCH ? width : contentWidth;
                    x = alignedX(element.align(), width, slotWidth, element.insets());
                }
                case BLOCK -> {
                    outerHeight = element.height() + element.insets().vertical();
                    slotWidth = element.width() == 0 || element.align() == Align.STRETCH
                            ? width - element.insets().horizontal()
                            : element.width();
                    x = alignedX(element.align(), width, slotWidth, element.insets());
                }
                default -> throw new IllegalStateException("unhandled element kind: " + element.kind());
            }

            if (slotWidth < 0) {
                // Only reachable when an element's own insets are wider than the column. Clamped rather
                // than thrown, because a window can be dragged to nothing and a negative-width slot
                // would place a rectangle that reads correctly everywhere it is used.
                slotWidth = 0;
            }

            int slotY = y + element.insets().top();
            int innerHeight = element.kind() == Kind.BLOCK ? element.height() : outerHeight;
            slots.add(new Slot(element.key(), x, slotY, slotWidth, innerHeight));

            y += outerHeight;
        }

        return Layout.of(slots);
    }

    /**
     * Where an element's outer edge sits, given how wide it is.
     *
     * <p>One method for both {@code TEXT} and {@code BLOCK}, because "centred" and "right-aligned" must
     * mean the same thing for a heading and for a row that contains one. Two copies of this arithmetic
     * is how a centred label and its centred button end up a pixel apart.
     */
    private static int alignedX(Align align, int columnWidth, int slotWidth, Insets insets) {
        return switch (align) {
            case STRETCH, LEFT -> insets.left();
            case CENTRE -> (columnWidth - slotWidth) / 2;
            case RIGHT -> columnWidth - slotWidth - insets.right();
        };
    }

    private Stack add(Element element) {
        elements.add(element);
        return this;
    }
}

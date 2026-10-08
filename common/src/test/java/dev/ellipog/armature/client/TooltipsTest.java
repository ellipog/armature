package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Measure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tooltip box: which tokens it is made of, whose theme those tokens are read from, and the one rule
 * about it that is a promise rather than a look — <b>it is always inside the screen</b>.
 *
 * <h2>Why this is asserted rather than looked at</h2>
 *
 * <p>The first bug this feature exists to fix was exactly a token that nothing read: the theme carried
 * {@code tooltipFill} and {@code tooltipEdge}, the editor offered rows for them, and the game's tooltips
 * were drawn from {@code panel()} and {@code controlEdgeBright()} — so a theme could set its tooltip
 * colours and see no change anywhere. A recorder answers "which colour was actually used" in a way a
 * screenshot cannot: a tooltip that ignored the token still looks like a tooltip.
 *
 * <h2>And why containment is a sweep</h2>
 *
 * <p>Because "never" is not an example. A clamp cannot contain a box bigger than its container, and the
 * boxes that overflow are the extreme ones — a screen a few pixels across, a pointer outside the window, a
 * list taller than the screen — so the last third of this file is a property over a grid of sizes,
 * positions and content shapes rather than four hand-picked screens.
 */
@DisplayName("Tooltips")
class TooltipsTest {

    @BeforeEach
    void resetTheme() {
        ArmatureTheme.resetCurrent();
    }

    @Test
    @DisplayName("the box is the tooltip tokens, all three of them")
    void theBoxUsesTheTooltipTokens() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.draw(r, List.of("Line one", "Line two"), 50, 50, 800, 600);

        assertTrue(r.fills().stream().anyMatch(call -> call.argb() == Themes.MODERN.tooltipFill()),
                "the box's fill must be the tooltip fill token");
        assertTrue(r.fills().stream().anyMatch(call -> call.argb() == Themes.MODERN.tooltipEdge()),
                "and its border the tooltip edge token");
        assertTrue(r.texts().stream().anyMatch(call -> "Line one".equals(call.text())
                        && call.argb() == Themes.MODERN.tooltipText()),
                "and its text the tooltip text token");
    }

    @Test
    @DisplayName("a scope reaches the box, so a chapter's tooltips wear the chapter's tokens")
    void aScopeThemesTheBox() {
        Theme chapter = Themes.MONOCHROME;
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(chapter)) {
            Tooltips.draw(r, List.of("Line"), 50, 50, 800, 600);
        }

        assertTrue(r.fills().stream().anyMatch(call -> call.argb() == chapter.tooltipFill()),
                "inside the scope the box is the chapter's fill");
        assertFalse(r.fills().stream().anyMatch(call -> call.argb() == Themes.MODERN.tooltipFill()),
                "and not the main theme's");
    }

    @Test
    @DisplayName("it moves rather than leaving the window, and clamps when no placement fits")
    void itMovesAtTheEdges() {
        ArmatureTheme.setCurrent(Themes.MODERN);

        // Near the right edge with room below: the box opens to the left of the pointer — the flip the
        // first version had, and the one a row near the panel's edge needs.
        RecordingRenderer flip = RecordingRenderer.create();
        Tooltips.draw(flip, List.of("A fairly wide tooltip line"), 790, 300, 800, 600);

        RecordingRenderer.Call text = flip.texts().get(0);
        assertTrue(text.x() < 790, "at the right edge the box opens to the left of the pointer: "
                + text.x());
        assertTrue(flip.fills().stream().allMatch(call -> call.x2() <= 800 && call.y2() <= 600),
                "and nothing is drawn past the window");

        // Near the bottom with room to the right: it opens above the pointer, clear of it rather than
        // mirrored onto it. See `POINTER_INSET` for why that distinction is the whole of this case.
        RecordingRenderer up = RecordingRenderer.create();
        Tooltips.draw(up, List.of("A fairly wide tooltip line"), 100, 595, 800, 600);

        int boxTop = up.texts().get(0).y() - Tooltips.PAD;
        assertTrue(boxTop + Tooltips.height(up, 1) <= 595 - Tooltips.POINTER_INSET,
                "at the bottom edge the box opens above the pointer: " + up.texts().get(0));

        // And the bottom-right *corner* has no placement at all — the box is wider than the room to the
        // left and taller than the room above — so the clamp answers, which is still inside the window.
        RecordingRenderer corner = RecordingRenderer.create();
        Tooltips.draw(corner, List.of("A fairly wide tooltip line"), 790, 595, 800, 600);

        assertTrue(corner.texts().get(0).y() < 595,
                "the corner box still sits above the pointer: " + corner.texts().get(0).y());
        assertTrue(corner.fills().stream().allMatch(call -> call.x2() <= 800 && call.y2() <= 600),
                "and nothing is drawn past the window");
    }

    @Test
    @DisplayName("no lines is no box")
    void noLinesDrawsNothing() {
        RecordingRenderer r = RecordingRenderer.create();
        Tooltips.draw(r, List.of(), 50, 50, 800, 600);

        assertEquals(0, r.fills().size());
        assertEquals(0, r.texts().size());
    }

    // ------------------------------------------------------------------
    // Folding
    // ------------------------------------------------------------------

    /** A sentence long enough to need folding, in words so the wrap has somewhere to break. */
    private static String sentence(int words) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append("word").append(i);
        }
        return text.toString();
    }

    @Test
    @DisplayName("a long line is folded rather than run off the left of the window")
    void longLinesFold() {
        // The report: a tooltip's box was as wide as its widest line, so one long sentence made it wider
        // than the window -- and the flip that keeps it on screen then put its left edge *off* the screen,
        // where the text simply was not. Folding is what makes the box bounded by construction.
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.draw(r, List.of(sentence(30)), 700, 300, 800, 600);

        assertTrue(r.texts().size() > 1, "a 30-word line should have folded, got " + r.texts().size()
                + " line(s)");
        for (RecordingRenderer.Call text : r.texts()) {
            assertTrue(r.textWidth(text.text()) <= Tooltips.MAX_LINE_WIDTH,
                    "a folded line is wider than the cap: " + r.textWidth(text.text()) + " for '"
                            + text.text() + "'");
            assertTrue(text.x() >= 0 && text.x() + r.textWidth(text.text()) <= 800,
                    "and every line is drawn inside the window, at x=" + text.x());
        }
        assertTrue(r.fills().stream().allMatch(call -> call.x() >= 0 && call.x2() <= 800),
                "the box itself stays inside the window");
    }

    @Test
    @DisplayName("a line with nowhere to break is split rather than overflowed")
    void aLongWordIsSplit() {
        // A namespaced id, a URL, a path -- one token with no space in it. TextWrap's rule is to split it
        // rather than draw through the edge or drop it, which is the rule this box now inherits.
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.draw(r, List.of("x".repeat(200)), 10, 10, 800, 600);

        assertTrue(r.texts().size() > 1, "an unbroken 200-character token should have been split");
        for (RecordingRenderer.Call text : r.texts()) {
            assertTrue(r.textWidth(text.text()) <= Tooltips.MAX_LINE_WIDTH,
                    "a split fragment is wider than the cap: " + r.textWidth(text.text()));
        }
    }

    @Test
    @DisplayName("the fold is bounded by the window, so a narrow one folds earlier")
    void theWindowBoundsTheFold() {
        // A 200-pixel window cannot hold a 240-pixel line, so the room is what the window leaves rather
        // than the cap. Without this the box would be wider than the screen it is drawn on.
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.draw(r, List.of(sentence(20)), 100, 50, 200, 100);

        int room = 200 - Tooltips.MARGIN * 2 - Tooltips.PAD * 2;
        for (RecordingRenderer.Call text : r.texts()) {
            assertTrue(r.textWidth(text.text()) <= room,
                    "a line is wider than the narrow window leaves: " + r.textWidth(text.text())
                            + " > " + room);
        }
        assertTrue(r.fills().stream().allMatch(call -> call.x() >= 0 && call.x2() <= 200),
                "and nothing is drawn past the window's edge");
    }

    @Test
    @DisplayName("a short line is left alone, and a blank line is still a line")
    void shortLinesAndSpacers() {
        // Two properties worth pinning rather than assuming. A short line must not be rebuilt (a tooltip
        // of three words should be a box of three words, not of a fixed width), and a blank line must
        // survive: it is how a caller separates two facts, and a wrap rule that drops it would silently
        // join them.
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();
        List<String> lines = List.of("Short", "", "Also short");

        assertEquals(lines, Tooltips.folded(r, lines, 800),
                "nothing here needed folding, so nothing should have changed");

        Tooltips.draw(r, lines, 50, 50, 800, 600);

        assertEquals(lines, r.texts().stream().map(RecordingRenderer.Call::text).toList(),
                "and the three lines are drawn as three, the blank one included");
        // The box's extent is the folded lines', which is the half of this that keeps a scrollbar or a
        // clamp from disagreeing with the drawing -- see TextWrap's own note on the same split. Measured
        // as the box's own bounds rather than as one call's height: a rounded panel is drawn as bands.
        int top = r.fills().stream().mapToInt(RecordingRenderer.Call::y).min().orElseThrow();
        int bottom = r.fills().stream().mapToInt(RecordingRenderer.Call::y2).max().orElseThrow();
        int left = r.fills().stream().mapToInt(RecordingRenderer.Call::x).min().orElseThrow();
        int right = r.fills().stream().mapToInt(RecordingRenderer.Call::x2).max().orElseThrow();

        assertEquals(Tooltips.height(r, lines.size()), bottom - top, "the box's height is three lines");
        assertEquals(Tooltips.width(r, lines), right - left,
                "and its width is its widest line plus the padding, not a fixed size");
    }

    // ------------------------------------------------------------------
    // Containment: the promise, as a property
    // ------------------------------------------------------------------

    /** Screens to sweep. The small end is the end that matters: a clamp cannot fix a box bigger than one. */
    private static final int[] WIDTHS = {1, 2, 5, 9, 12, 13, 20, 40, 80, 160, 240, 400};

    private static final int[] HEIGHTS = {1, 2, 8, 12, 19, 20, 40, 80, 160, 300};

    /** The content shapes: a short line, a paragraph, one unbreakable token, a long list, a list with spacers. */
    private static List<List<String>> contents() {
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("Short"));
        out.add(List.of(sentence(30)));
        out.add(List.of("x".repeat(200)));
        out.add(lines(40));
        out.add(List.of("First", "", "Third", sentence(12)));
        return out;
    }

    /** N short lines: the list case, which folding cannot make shorter because it cannot merge two lines. */
    private static List<String> lines(int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add("line" + i);
        }
        return out;
    }

    /** Every recorded pixel of a box and its text, inside the screen. */
    private static void assertContained(RecordingRenderer r, int width, int height, String what) {
        for (RecordingRenderer.Call fill : r.fills()) {
            assertTrue(fill.x() >= 0 && fill.y() >= 0 && fill.x2() <= width && fill.y2() <= height,
                    what + ": a fill left the screen: " + fill);
        }
        for (RecordingRenderer.Call text : r.texts()) {
            assertTrue(text.x() >= 0 && text.y() >= 0,
                    what + ": a line was drawn at a negative coordinate: " + text);
            assertTrue(text.x() + r.textWidth(text.text()) <= width,
                    what + ": a line ran past the right edge: " + text);
            assertTrue(text.y() + r.lineHeight() <= height,
                    what + ": a line ran past the bottom edge: " + text);
        }
    }

    @Test
    @DisplayName("no box and no line ever leaves the screen, at any size, for any content")
    void nothingEverLeavesTheScreen() {
        // The requirement as a property rather than as examples, and the pointer is swept *outside* the
        // window on purpose: this screen really does hand a tooltip an inert pointer while an overlay owns
        // it, and a stale or hostile coordinate must not be a hole in the promise.
        ArmatureTheme.setCurrent(Themes.MODERN);

        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (List<String> lines : contents()) {
                    for (int[] at : new int[][] {{0, 0}, {width / 2, height / 2}, {width - 1, height - 1},
                            {-40, -40}, {width + 50, height + 50}}) {
                        String what = lines.size() + " line(s) at " + at[0] + "," + at[1]
                                + " on " + width + "x" + height;

                        RecordingRenderer pointed = RecordingRenderer.create();
                        Tooltips.draw(pointed, lines, at[0], at[1], width, height);
                        assertContained(pointed, width, height, "draw: " + what);

                        RecordingRenderer anchored = RecordingRenderer.create();
                        Tooltips.drawAt(anchored, lines, at[0], at[1], 20, 12, width, height);
                        assertContained(anchored, width, height, "drawAt: " + what);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a screen with no room for one line draws nothing at all")
    void nothingFitsSoNothingIsDrawn() {
        ArmatureTheme.setCurrent(Themes.MODERN);

        // Too short: ten pixels of line, four of padding top and bottom, two of margin either side.
        RecordingRenderer squat = RecordingRenderer.create();
        Tooltips.draw(squat, List.of("Line"), 5, 5, 200, 19);
        assertEquals(0, squat.fills().size(), "a box was drawn with no room for a line");
        assertEquals(0, squat.texts().size());

        // Too narrow: the padding and the margins alone take twelve.
        RecordingRenderer thin = RecordingRenderer.create();
        Tooltips.draw(thin, List.of("Line"), 5, 5, 12, 200);
        assertEquals(0, thin.fills().size(), "a box was drawn with no room for a character");
        assertEquals(0, thin.texts().size());

        // And exactly enough draws one, so the boundary is a comparison rather than a guess.
        RecordingRenderer exact = RecordingRenderer.create();
        Tooltips.draw(exact, List.of("Line"), 5, 5, 13, 20);
        assertTrue(exact.fills().size() > 0, "13x20 is the smallest screen that can hold one line");
        assertContained(exact, 13, 20, "the smallest screen");
    }

    @Test
    @DisplayName("a box too tall for the screen keeps the lines that fit and says there is more")
    void aBoxTallerThanTheScreenSaysThereIsMore() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.draw(r, lines(40), 50, 40, 200, 60);

        List<String> drawn = r.texts().stream().map(RecordingRenderer.Call::text).toList();
        assertFalse(drawn.isEmpty(), "the box was dropped rather than capped");
        assertTrue(drawn.size() < 40, "everything fitted, so this case proves nothing: " + drawn.size());
        assertEquals(Tooltips.ELLIPSIS, drawn.get(drawn.size() - 1),
                "the last line a capped box draws says there is more: " + drawn);
        assertContained(r, 200, 60, "a capped box");

        // And a box that fits ends with its own last line: the ellipsis is a promise about what was
        // dropped, not a full stop on every tooltip.
        RecordingRenderer small = RecordingRenderer.create();
        Tooltips.draw(small, lines(3), 50, 40, 200, 60);
        assertEquals(List.of("line0", "line1", "line2"),
                small.texts().stream().map(RecordingRenderer.Call::text).toList());
    }

    @Test
    @DisplayName("the first placement that fits is the one taken")
    void thePlacementsAreTriedInOrder() {
        ArmatureTheme.setCurrent(Themes.MODERN);

        // Room everywhere: right and below the pointer, which is where a tip has always opened.
        RecordingRenderer open = RecordingRenderer.create();
        Tooltips.draw(open, List.of("Short"), 100, 100, 400, 300);
        RecordingRenderer.Call text = open.texts().get(0);
        assertEquals(100 + Tooltips.OFFSET + Tooltips.PAD, text.x(), "the default is right of the pointer");
        assertEquals(100 - Tooltips.POINTER_INSET + Tooltips.PAD, text.y(), "and below its line");
    }

    @Test
    @DisplayName("a caption sits under its control, and moves when there is no room")
    void aCaptionMovesAroundItsControl() {
        ArmatureTheme.setCurrent(Themes.MODERN);

        // Under the control, which is where a caption has always been.
        RecordingRenderer under = RecordingRenderer.create();
        Tooltips.drawAt(under, List.of("Expand"), 100, 100, 12, 12, 400, 300);
        RecordingRenderer.Call text = under.texts().get(0);
        assertEquals(100 + Tooltips.PAD, text.x());
        assertEquals(100 + 12 + Tooltips.ANCHOR_GAP + Tooltips.PAD, text.y());

        // A control at the foot of the screen: above it instead.
        RecordingRenderer above = RecordingRenderer.create();
        Tooltips.drawAt(above, List.of("Expand"), 100, 285, 12, 12, 400, 300);
        int boxTop = above.texts().get(0).y() - Tooltips.PAD;
        assertTrue(boxTop + Tooltips.height(above, 1) <= 285 - Tooltips.ANCHOR_GAP,
                "a caption at the screen's foot goes above its control: " + above.texts().get(0));

        // A control at the right edge: beside it, to the left.
        RecordingRenderer beside = RecordingRenderer.create();
        Tooltips.drawAt(beside, List.of("Expand"), 390, 100, 8, 12, 400, 300);
        assertTrue(beside.texts().get(0).x() < 390,
                "a caption at the right edge goes to the left: " + beside.texts().get(0));
        assertContained(beside, 400, 300, "a caption beside its control");
    }

    @Test
    @DisplayName("a long caption folds instead of running off the panel it belongs to")
    void aLongCaptionFolds() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.drawAt(r, List.of("minecraft:enchanted_golden_apple_of_the_ancients"), 380, 100, 8, 12,
                400, 300);

        assertTrue(r.texts().size() > 1, "the id should have folded rather than run off: "
                + r.texts().size());
        for (RecordingRenderer.Call text : r.texts()) {
            assertFalse(text.text().isEmpty(), "a folded fragment is never empty: " + r.texts());
        }
        assertContained(r, 400, 300, "a long caption");
    }

    @Test
    @DisplayName("fit is the whole placement, as arithmetic: at the preference, else inside the screen")
    void fitIsPureAndContained() {
        Measure measure = Measure.monospace(6, 10);

        Tooltips.Placed room = Tooltips.fit(List.of("Line"), 50, 60, 400, 300, measure);
        assertEquals(50, room.x(), "with room, the preferred corner is the answer");
        assertEquals(60, room.y());
        assertEquals(List.of("Line"), room.lines());

        Tooltips.Placed pushed = Tooltips.fit(List.of("Line"), 395, 295, 400, 300, measure);
        assertTrue(pushed.x() >= Tooltips.MARGIN && pushed.y() >= Tooltips.MARGIN,
                "a preference off the screen is pushed inside: " + pushed);
        assertTrue(pushed.right() <= 400 - Tooltips.MARGIN && pushed.bottom() <= 300 - Tooltips.MARGIN,
                "on both axes: " + pushed);

        assertNull(Tooltips.fit(List.of("Line"), 0, 0, 12, 300, measure), "no room, no box");
        assertNull(Tooltips.fit(List.of(), 0, 0, 400, 300, measure), "no lines, no box");
    }
}

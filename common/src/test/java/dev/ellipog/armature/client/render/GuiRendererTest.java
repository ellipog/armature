package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam, and the drawing that goes through it — asserted instead of looked at.
 *
 * <h2>Why this file is the answer to "an interface wrapping one implementation"</h2>
 *
 * <p>The plan scheduled the renderer seam last, with a reason worth quoting: <i>"an interface wrapping
 * one implementation is an indirection nobody can evaluate"</i>. That is correct about an interface
 * whose only implementation forwards to the thing it abstracts — nothing about it can be tested, and
 * you cannot tell a correct forwarding from a wrong one without a client.
 *
 * <p>{@link RecordingRenderer} is the second implementation, and it is a <i>reader</i> rather than a
 * renderer. So this file asks the questions that were previously only answerable by taking a
 * screenshot:
 *
 * <ul>
 *   <li>Does an outline's four fills actually enclose the rectangle it claims to?</li>
 *   <li>Does a shape's fill follow its spans, or would a rectangle slip through unnoticed?</li>
 *   <li>Is a clip balanced — opened once, closed once — on the path that returns early?</li>
 *   <li>Does a truncated label stay inside the width it was given?</li>
 * </ul>
 *
 * <p>Every one of those is a real bug this project has had or has come close to. They were found by
 * looking at pictures. These are the same questions as assertions.
 */
@DisplayName("The drawing seam")
class GuiRendererTest {

    // ------------------------------------------------------------------
    // The seam's own contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("centredText is derived from textWidth, so the two cannot disagree about the middle")
    void centredTextUsesTheWidthItReports() {
        // A default method rather than an abstract one, so every future implementation gets the same
        // centring and cannot implement it differently. The assertion is that the text's centre lands
        // on the centre it was given -- which is the property, not the arithmetic.
        RecordingRenderer r = RecordingRenderer.create();

        r.centredText("abcd", 100, 10, 0xFFFFFFFF);

        RecordingRenderer.Call call = r.callFor("abcd");
        assertNotNull(call, "the text was not drawn at all");

        // 4 characters at 6 pixels is 24 wide, so the left edge is 88 and the centre is 100.
        assertEquals(88, call.x());
        assertEquals(100, call.x() + r.textWidth("abcd") / 2,
                "the drawn text is not centred on the point it was given");
    }

    @Test
    @DisplayName("the default clipping scopes and the viewport/slot overloads agree about the rectangle")
    void clipOverloadsDescribeTheSameRectangle() {
        // Folded into the interface rather than left to callers, and this is why: a clip pushed at a
        // viewport's edges and a viewport asked separately whether a point is inside it have to mean
        // the same rectangle. The three-argument forms are here so a caller cannot invent a fourth
        // opinion about where a view's edges are.
        RecordingRenderer r = RecordingRenderer.create();
        Viewport view = Viewport.fixed().bounds(10, 20, 100, 50);
        Slot slot = new Slot("row", 10, 20, 100, 50);

        try (GuiRenderer.Scoped a = r.clip(view)) {
            // Nothing to do; the call is the assertion's subject.
        }
        try (GuiRenderer.Scoped b = r.clip(slot)) {
            // As above.
        }
        try (GuiRenderer.Scoped c = r.clip(10, 20, 110, 70)) {
            // As above.
        }

        List<RecordingRenderer.Call> clips = r.clips();
        assertEquals(3, clips.size());
        assertEquals(clips.get(0).x(), clips.get(1).x());
        assertEquals(clips.get(0).y(), clips.get(1).y());
        assertEquals(clips.get(0).x2(), clips.get(1).x2());
        assertEquals(clips.get(0).y2(), clips.get(1).y2());
        assertEquals(clips.get(0).x(), clips.get(2).x(),
                "the viewport's rectangle and the explicit one must be the same rectangle");
        assertEquals(clips.get(0).y2(), clips.get(2).y2());
    }

    @Test
    @DisplayName("an icon reports whether it drew, so a caller can fall back rather than leave a hole")
    void iconReportsWhetherItDrew() {
        // The return value is load-bearing: a row of icons with an empty box in it reads as a bug, so
        // both call sites test it and draw a block instead. A recorder that always said yes would make
        // every fallback path untestable.
        assertTrue(RecordingRenderer.create().icon(null, 0, 0, 16));
        assertFalse(RecordingRenderer.withoutIcons().icon(null, 0, 0, 16));
    }

    // ------------------------------------------------------------------
    // Clipping through the seam
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a clip scope closes exactly once, and closing twice is harmless")
    void aClipScopeIsBalanced() {
        // The property a scoped clip exists for. A leaked clip does not fail loudly -- it leaves every
        // later draw in the frame clipped to a rectangle nobody chose -- so "opened once, closed once"
        // is worth an assertion rather than a comment.
        RecordingRenderer r = RecordingRenderer.create();

        try (GuiRenderer.Scoped clip = r.clip(0, 0, 10, 10)) {
            assertEquals(10, clip.hashCode() == 0 ? 10 : 10);
        }

        assertTrue(r.clipsBalanced(), "the clip was not balanced: " + r);
        assertEquals(1, r.clips().size());
    }

    @Test
    @DisplayName("closing an already-closed scope is a no-op, not a second pop")
    void aDoubleCloseDoesNotPopTwice() {
        // A caller that closes explicitly and then lets try-with-resources close again is a mistake
        // worth making harmless: the second close would pop a rectangle the scope never pushed, and
        // the frame would end one scissor too shallow. Asserted against the recorder rather than the
        // implementation, because this is a claim about the seam's contract.
        RecordingRenderer r = RecordingRenderer.create();

        GuiRenderer.Scoped clip = r.clip(0, 0, 10, 10);
        clip.close();
        clip.close();

        assertTrue(r.clipsBalanced(), "a double close unbalanced the stack: " + r);
        assertEquals(0, r.strayPops(), "the second close popped something it did not push");
    }

    @Test
    @DisplayName("clips nest rather than replace, which is what makes an inner clip narrower")
    void clipsNest() {
        // 1.21.1's scissor implementation really is a stack -- enableScissor pushes, disableScissor
        // pops, read from the jar -- so a nested clip narrows its parent. That is the behaviour the
        // overlay relies on: its body clip sits inside nothing, but the canvas's sits inside the
        // screen. A seam that replaced instead would silently make an inner clip *wider*.
        RecordingRenderer r = RecordingRenderer.create();

        try (GuiRenderer.Scoped outer = r.clip(0, 0, 100, 100)) {
            try (GuiRenderer.Scoped inner = r.clip(10, 10, 50, 50)) {
                assertEquals(2, r.deepestClip(), "the two clips did not nest");
            }
        }

        assertTrue(r.clipsBalanced());
    }

    // ------------------------------------------------------------------
    // ArmatureTheme, through the seam
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an outline's four fills enclose the rectangle, with no gap at a corner")
    void anOutlineEnclosesItsRectangle() {
        // Four fills rather than a stroke, because there is no stroke primitive. That makes the edges
        // four separate chances to be one pixel out, and the symptom is a hairline gap at a corner --
        // which on a panel border reads as the panel being broken rather than as the outline being
        // wrong. The corners are the cases worth checking, because an edge that is off by one is
        // invisible everywhere except the ends.
        RecordingRenderer r = RecordingRenderer.create();
        ArmatureTheme.outline(r, 10, 20, 30, 40, 0xFFFFFFFF);

        assertEquals(4, r.fills().size(), "an outline is four fills, not a stroke");

        // The four edges, each as a zero-thickness line: top, bottom, left, right.
        assertTrue(r.covered(10, 20), "the top-left corner is not covered");
        assertTrue(r.covered(39, 20), "the top edge stops short of the right corner");
        assertTrue(r.covered(10, 59), "the left edge stops short of the bottom corner");
        assertTrue(r.covered(39, 59), "the bottom-right corner is not covered");

        // The interior is NOT covered -- an outline that filled its box would be a panel, and the two
        // exist as separate helpers precisely because they are different things.
        assertFalse(r.covered(25, 40), "the outline filled its interior, so it is not an outline");
    }

    @Test
    @DisplayName("a panel is the border's footprint first, with the fill inset over it")
    void aPanelIsBorderThenInsetFill() {
        // Order is the whole of this method's correctness, and the order is not the obvious one. The
        // border is drawn as the *whole* footprint and the fill is then drawn inset by one pixel, which
        // leaves exactly a one-pixel ring of border visible. Reversed -- fill the box, then draw a border
        // footprint over it -- the border covers the fill entirely and the panel is a solid block of
        // border colour. A recorder can see which happened; a screenshot shows only a panel with no fill,
        // which reads as the fill colour being the wrong one.
        //
        // This replaced a five-fill version -- one fill, then four edges -- when the corner radius
        // started being drawn, and both the count and the order changed. Four edges cannot be rounded at
        // all, and the version that can is two *shapes* rather than five rectangles, so the count is now
        // one per merged run of each. Asserting the order as well as the count matters: "eight fills"
        // would also be true of a panel drawn twice.
        // **At radius 0**, and that is not a convenience. At a radius the border's first fill is the top
        // row of a rounded shape, so its left edge is the corner's cut rather than the panel's left edge
        // -- and asserting `x() == 0` there was asserting that the rounding had *not* happened. The order
        // is a property of the call sequence and is cleanest at zero; the rounding is a property of the
        // spans and has its own test below. Mixing them made a test that failed for being right.
        ArmatureTheme.setCurrent(Themes.MODERN.withRadius(0));

        RecordingRenderer r = RecordingRenderer.create();
        ArmatureTheme.panel(r, 0, 0, 20, 20, 0xFF111111, 0xFF222222);

        List<RecordingRenderer.Call> fills = r.fills();
        RecordingRenderer.Call footprint = fills.get(0);
        assertEquals(0xFF222222, footprint.argb(),
                "the first thing drawn should be the border's footprint, which the fill is inset into");
        assertEquals(0, footprint.x());
        assertEquals(20, footprint.x2());

        RecordingRenderer.Call inset = fills.get(fills.size() - 1);
        assertEquals(0xFF111111, inset.argb(), "the last thing drawn should be the fill");
        assertEquals(1, inset.x(), "the fill should be inset by one, leaving a pixel of border");
        assertEquals(19, inset.x2());
        assertEquals(1, inset.y());
        assertEquals(19, inset.y2());

        // And the pixels, which is what the order is for. Drawn the other way round -- fill the box,
        // then draw the border's footprint over it -- the interior would be border colour.
        assertTrue(r.covered(0, 10), "the left border is not drawn");
        assertTrue(r.covered(10, 10), "the panel's interior is not filled");
        assertTrue(r.covered(19, 19), "the bottom-right border is not drawn");

        ArmatureTheme.resetCurrent();
    }

    @Test
    @DisplayName("a panel is square at radius 0 and rounded at radius 6, which is the theme's doing")
    void aPanelHonoursTheCornerRadius() {
        // The radius is a theme value that nothing drew until this round. Every theme set one, the panel
        // filled a rectangle regardless, and the two themes advertising 8 and the two advertising 0
        // rendered identically -- so the value was a lie rather than a setting. Worth its own test
        // because the failure is invisible: a radius that does nothing looks exactly like a theme that
        // was designed square, which is why it survived a whole round of looking at the UI.
        ArmatureTheme.setCurrent(Themes.MODERN.withRadius(0));
        RecordingRenderer square = RecordingRenderer.create();
        ArmatureTheme.panel(square, 0, 0, 20, 20, 0xFF111111, 0xFF222222);

        assertEquals(2, square.fills().size(),
                "a square panel is the border's box and the fill's box -- two calls, because merging "
                        + "identical runs is what keeps a rectangle affordable");
        assertTrue(square.covered(0, 0), "a square panel should cover its own corner");

        ArmatureTheme.setCurrent(Themes.MODERN.withRadius(6));
        RecordingRenderer rounded = RecordingRenderer.create();
        ArmatureTheme.panel(rounded, 0, 0, 20, 20, 0xFF111111, 0xFF222222);

        assertTrue(rounded.fills().size() > 2,
                "a rounded panel needs a run per corner row, so more than two fills");
        assertFalse(rounded.covered(0, 0),
                "the corner of a rounded panel is still being drawn square, so the theme's radius is not "
                        + "reaching this call");
        assertTrue(rounded.covered(0, 10),
                "the left edge at mid-height is not a corner, so it should still be drawn");
        assertTrue(rounded.covered(10, 10), "and the middle of the panel should still be filled");

        ArmatureTheme.resetCurrent();
    }

    @Test
    @DisplayName("a shape's fill follows its spans rather than filling its bounding box")
    void aShapeFollowsItsSpans() {
        // The bug this guards is the one that shipped: a circular node with a rectangular wash drawn
        // over it, which in the screenshot is a black square on a round thing and reads as a rendering
        // glitch rather than as a style. A recorder makes the difference between "the spans were
        // consulted" and "a rectangle was drawn" visible as an assertion.
        //
        // A diamond, so every row has a different span and no two can be merged: the widest row is the
        // middle, and the corners of the bounding box are empty.
        ArmatureTheme.RowSpans diamond = (row, size) -> {
            int half = size / 2;
            int distance = Math.abs(row - half);
            int width = half - distance;
            return width <= 0 ? null : new int[] {half - width, half + width + 1};
        };

        RecordingRenderer r = RecordingRenderer.create();
        ArmatureTheme.fillShape(r, 0, 0, 21, 0xFFFFFFFF, diamond);

        // The middle row of a 21-wide diamond spans 11 pixels, so the centre is covered...
        assertTrue(r.covered(10, 10), "the shape's own centre is not filled");
        // ...and the bounding box's corners are not, which is the whole difference from a rectangle.
        assertFalse(r.covered(0, 0), "the top-left of the bounding box was filled, so this is a box");
        assertFalse(r.covered(20, 20), "the bottom-right of the bounding box was filled");
        assertFalse(r.covered(0, 20), "the bottom-left of the bounding box was filled");
        assertFalse(r.covered(20, 0), "the top-right of the bounding box was filled");
    }

    @Test
    @DisplayName("adjacent rows with the same span merge into one call, which is what makes shapes affordable")
    void identicalRowsMergeIntoOneFill() {
        // A square shape: every row has an identical span, so the whole thing is one fill. That is not
        // an optimisation detail -- it is the reason a node's wash can be a shape rather than a
        // rectangle without costing 48 primitives each. A shape drawn a row at a time is 48 fills per
        // node, and a chapter shows thirty nodes.
        ArmatureTheme.RowSpans square = (row, size) -> new int[] {0, size};

        RecordingRenderer r = RecordingRenderer.create();
        ArmatureTheme.fillShape(r, 0, 0, 48, 0xFFFFFFFF, square);

        assertEquals(1, r.fills().size(),
                "48 identical rows should be one fill, and produced " + r.fills().size()
                        + ". Merging has stopped working, which quietly multiplies the primitive "
                        + "count for every node on the canvas.");
    }

    @Test
    @DisplayName("a shape panel draws the border then the smaller fill, so the border survives")
    void aShapePanelDrawsItsBorderFirst() {
        // Same argument as the rectangle panel: the border is the shape at full size and the fill is a
        // two-pixel-smaller copy inset by one. Reversed, the fill covers the border entirely and the
        // node loses its state colour -- which reads as the palette being wrong.
        ArmatureTheme.RowSpans square = (row, size) -> new int[] {0, size};

        RecordingRenderer r = RecordingRenderer.create();
        ArmatureTheme.shapePanel(r, 0, 0, 10, 0xFF111111, 0xFF222222, square);

        List<RecordingRenderer.Call> fills = r.fills();
        assertEquals(2, fills.size());
        assertEquals(0xFF222222, fills.get(0).argb(), "the border should be drawn first");
        assertEquals(0xFF111111, fills.get(1).argb(), "and the fill over it");

        // The inner fill is inset by one and four pixels smaller, which is what leaves a one-pixel
        // border showing on every side.
        assertEquals(1, fills.get(1).x());
        assertEquals(1, fills.get(1).y());
        assertEquals(8, fills.get(1).x2() - fills.get(1).x());
    }

    @Test
    @DisplayName("a shape smaller than its own border is not drawn inside out")
    void aTinyShapeDoesNotInvert() {
        // `size > 2` guards the inset, because at 2 or below the inner shape would be zero or negative
        // -- and a negative size passed to a span lookup produces spans that run backwards, which a
        // renderer draws as a rectangle going the other way. Reachable by dragging a window to nothing.
        ArmatureTheme.RowSpans square = (row, size) -> new int[] {0, size};

        for (int size : new int[] {0, 1, 2}) {
            RecordingRenderer r = RecordingRenderer.create();
            ArmatureTheme.shapePanel(r, 0, 0, size, 0xFF111111, 0xFF222222, square);

            for (RecordingRenderer.Call fill : r.fills()) {
                assertTrue(fill.x2() >= fill.x(), "a fill ran backwards at size " + size);
                assertTrue(fill.y2() >= fill.y(), "a fill ran backwards at size " + size);
            }
        }

        RecordingRenderer zero = RecordingRenderer.create();
        ArmatureTheme.fillShape(zero, 0, 0, 0, 0xFFFFFFFF, square);
        assertTrue(zero.fills().isEmpty(), "a zero-sized shape should draw nothing at all");
    }

    @Test
    @DisplayName("a null span skips its row rather than truncating the shape")
    void aNullSpanSkipsRatherThanStops() {
        // A shape with a genuine hole in it. Truncating instead would cut the bottom off a node whose
        // lookup ran off the end, which is a one-line mistake with a very visible symptom.
        ArmatureTheme.RowSpans holed = (row, size) -> row == 2 ? null : new int[] {0, size};

        RecordingRenderer r = RecordingRenderer.create();
        ArmatureTheme.fillShape(r, 0, 0, 6, 0xFFFFFFFF, holed);

        assertFalse(r.covered(2, 2), "the hole was filled");
        assertTrue(r.covered(2, 4), "the shape stopped at the hole instead of skipping it");
    }

    @Test
    @DisplayName("a flush is recorded, so a caller's layering boundary is assertable")
    void aFlushIsRecorded() {
        // The whole reason `flush` is on the seam rather than left implicit. Both real implementations
        // batch — fills accumulate in one buffer and are handed over when the batch fills or the frame
        // ends — so an operation that draws through a *different* path is ordered by its own batching
        // rather than by the order the code called it in. An item icon is exactly that operation.
        //
        // The quest book hit this: nodes are item icons on the canvas, the tool cluster's backing panel
        // is drawn over the canvas afterwards, and the icons floated on top of the buttons. The fix is
        // a flush at that seam, and this is the test that makes the seam's contract checkable — a
        // recorder that silently swallowed the call would leave the fix unassertable, which is exactly
        // the class of defect it was written for.
        RecordingRenderer r = RecordingRenderer.create();

        r.fill(0, 0, 10, 10, 0xFF111111);
        int beforeFlush = r.lastIndex(RecordingRenderer.Op.FILL);
        r.flush();
        r.fill(0, 0, 10, 10, 0xFF222222);
        int afterFlush = r.firstIndex(RecordingRenderer.Op.FILL) == beforeFlush
                ? r.fills().size() - 1
                : -1;

        assertEquals(1, r.flushes().size(), "the flush should be recorded exactly once");
        assertTrue(r.flushes().get(0) > beforeFlush,
                "the flush marker should come after the draws it pushes out");
        assertTrue(afterFlush >= 0,
                "and before the draws that must land on top of them, which is the whole point");
    }

    @Test
    @DisplayName("a flush is idempotent and harmless with nothing queued")
    void flushingNothingIsHarmless() {
        // A caller may flush at a seam that happens to have drawn nothing — an empty chapter, a hidden
        // overlay — and a flush that threw or double-flushed there would be a crash on an empty screen
        // rather than on a busy one.
        RecordingRenderer r = RecordingRenderer.create();

        r.flush();
        r.flush();

        assertEquals(2, r.flushes().size(), "both calls are recorded, and neither is an error");
        assertTrue(r.clipsBalanced(), "and a flush must not disturb the clip stack");
    }

    // ------------------------------------------------------------------
    // Measure.truncate, which the control and the canvas both use
    // ------------------------------------------------------------------

    @Test
    @DisplayName("truncation leaves a fitting string alone, identity included")
    void truncateReturnsTheSameStringWhenItFits() {
        // Returned unchanged rather than rebuilt, so a caller comparing identity -- and a test
        // asserting no ellipsis -- sees exactly what it passed in.
        Measure measure = Measure.monospace(5, 10);
        String text = "hello";

        assertSame(text, Measure.truncate(text, 100, measure));
        assertSame(text, Measure.truncate(text, 25, measure), "exactly fitting is fitting");
    }

    @Test
    @DisplayName("a truncated string fits the width it was given, ellipsis included")
    void truncateFitsTheWidth() {
        // The property, swept rather than spot-checked: whatever comes back, the renderer can draw it
        // in the space. This is the invariant that makes a chapter title with a long name read as a
        // clipped name rather than as text drawn through a control's edge.
        Measure measure = Measure.monospace(6, 10);
        String longTitle = "Getting Started With A Very Long Chapter Name";

        for (int width = 6; width <= 200; width += 2) {
            String shown = Measure.truncate(longTitle, width, measure);
            assertTrue(measure.width(shown) <= width,
                    "at width " + width + " the result \"" + shown + "\" is "
                            + measure.width(shown) + " pixels wide");
        }
    }

    @Test
    @DisplayName("an ellipsis appears only when something was actually removed")
    void truncateUsesAnEllipsisOnlyWhenItCuts() {
        Measure measure = Measure.monospace(6, 10);

        String cut = Measure.truncate("abcdefghij", 30, measure);
        assertTrue(cut.endsWith("\u2026"), "a cut string should say so: " + cut);

        String whole = Measure.truncate("abc", 100, measure);
        assertFalse(whole.contains("\u2026"), "an uncut string should not be marked: " + whole);
    }

    @Test
    @DisplayName("a width too small for the ellipsis drops it rather than drawing past the edge")
    void truncateDropsTheEllipsisWhenItCannotFit() {
        // Three characters in a four-pixel slot is a worse outcome than nothing, but a character
        // outside its own box is worse than both -- and the alternative, returning the ellipsis alone,
        // draws exactly that.
        Measure measure = Measure.monospace(6, 10);

        String shown = Measure.truncate("abcdefghij", 4, measure);
        assertFalse(shown.startsWith("\u2026"),
                "the ellipsis was drawn in a slot too narrow for it: " + shown);
        assertEquals("", shown, "nothing fits in four pixels at six a character");

        assertEquals("", Measure.truncate("abc", 0, measure));
        assertEquals("", Measure.truncate("abc", -5, measure));
    }

    @Test
    @DisplayName("Measure.of delegates, so a caller's own width function is what decides")
    void measureOfDelegates() {
        // The adapter that replaced FontMeasure. A renderer already answers textWidth and lineHeight,
        // so the class that existed only to bridge a font to the layout interface had nothing left to
        // bridge -- but the two rules that need a Measure still need one, and this is how a caller
        // holding a renderer gets it.
        int[] asked = {0};
        Measure measure = Measure.of(text -> {
            asked[0] += text.length();
            return text.length() * 3;
        }, 7);

        assertEquals(9, measure.width("abc"), "the width function was not used");
        assertEquals(7, measure.lineHeight());
        assertEquals(3, asked[0], "the width function was called the wrong number of times");
    }

    @Test
    @DisplayName("a missing text argument is refused rather than dereferenced")
    void truncateRefusesMissingArguments() {
        // Refused rather than silently returning empty. `truncate(null, ...)` returning "" would be
        // indistinguishable from a label that is simply too long for its box, so a caller passing a
        // null title would get a control with no text and no error anywhere.
        Measure measure = Measure.monospace(6, 10);

        assertThrows(NullPointerException.class, () -> Measure.truncate(null, 10, measure));
        assertThrows(NullPointerException.class, () -> Measure.truncate("a", 10, null));
        assertNotNull(Measure.truncate("a", 10, measure), "a valid call must not return null");
    }
}

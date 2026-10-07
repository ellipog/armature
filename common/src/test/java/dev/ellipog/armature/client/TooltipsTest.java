package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tooltip box: which tokens it is made of, and whose theme those tokens are read from.
 *
 * <h2>Why this is asserted rather than looked at</h2>
 *
 * <p>The bug this feature exists to fix was exactly a token that nothing read: the theme carried
 * {@code tooltipFill} and {@code tooltipEdge}, the editor offered rows for them, and the game's tooltips
 * were drawn from {@code panel()} and {@code controlEdgeBright()} — so a theme could set its tooltip
 * colours and see no change anywhere. A recorder answers "which colour was actually used" in a way a
 * screenshot cannot: a tooltip that ignored the token still looks like a tooltip.
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
    @DisplayName("it flips left and up rather than leaving the window")
    void itFlipsAtTheEdges() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        RecordingRenderer r = RecordingRenderer.create();

        Tooltips.draw(r, List.of("A fairly wide tooltip line"), 790, 595, 800, 600);

        RecordingRenderer.Call text = r.texts().get(0);
        assertTrue(text.x() < 790, "at the right edge the box opens to the left of the pointer: "
                + text.x());
        assertTrue(text.y() < 595, "and at the bottom it opens above it: " + text.y());
        assertTrue(r.fills().stream().allMatch(call -> call.x2() <= 800 && call.y2() <= 600),
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
}

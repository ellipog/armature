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
}

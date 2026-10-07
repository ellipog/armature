package dev.ellipog.armature.client;

import dev.ellipog.armature.MinecraftTestBootstrap;
import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.Themes;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A control's label ink, and the box its sprite is drawn in.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>The ink used to be captured in the constructor — {@code textColour = ArmatureTheme.title()} — and
 * the screen builds its controls once and then draws them inside a chapter's scope. So a themed card
 * showed the chapter's fills under the main theme's label ink, and the fix is that the role is resolved
 * at draw time. A recorder sees which colour was actually written, which is the only way to tell a live
 * read from a captured one.
 *
 * <h2>Why the icon's box is here too</h2>
 *
 * <p>Because the same recorder can see it, and the rule is one number a caller can now set: the sprite
 * is inset by {@code iconInset} on all four sides. It was {@code height - 6} and {@code getX() + 3} —
 * the same three written twice — so a control could not ask for a two-pixel inset without the two
 * numbers disagreeing, which is exactly the pair this test holds together.
 */
@DisplayName("ArmatureButton")
class ArmatureButtonTest {

    @BeforeEach
    void resetTheme() {
        ArmatureTheme.resetCurrent();
    }

    @Test
    @DisplayName("a label's ink is resolved while drawing, so a scope reaches a button built outside it")
    void theInkFollowsTheScope() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        ArmatureButton button = new ArmatureButton(0, 0, 60, 20, Component.literal("Done"), () -> {
        });

        RecordingRenderer outside = RecordingRenderer.create();
        button.draw(outside, 0L);
        assertEquals(Themes.MODERN.title(), outside.texts().get(0).argb(),
                "at rest the label is the main theme's title ink");

        RecordingRenderer inside = RecordingRenderer.create();
        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(Themes.MONOCHROME)) {
            button.draw(inside, 0L);
        }
        assertEquals(Themes.MONOCHROME.title(), inside.texts().get(0).argb(),
                "and inside a scope it wears the scope's ink, though the button was built outside it");
    }

    @Test
    @DisplayName("the body role follows the theme too, and a pinned colour stays pinned")
    void theBodyRoleAndThePin() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        ArmatureButton body = new ArmatureButton(0, 0, 60, 20, Component.literal("Back"), () -> {
        }).ink(ArmatureButton.Ink.BODY);
        ArmatureButton pinned = new ArmatureButton(0, 0, 60, 20, Component.literal("Raw"), () -> {
        }).textColour(0xFF123456);

        RecordingRenderer r = RecordingRenderer.create();
        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(Themes.MONOCHROME)) {
            body.draw(r, 0L);
            pinned.draw(r, 0L);
        }

        assertEquals(Themes.MONOCHROME.body(), r.texts().get(0).argb(),
                "the body role resolves against the scope");
        assertEquals(0xFF123456, r.texts().get(1).argb(),
                "a colour a screen really meant to pin is not overridden by a scope");
    }

    @Test
    @DisplayName("the sprite is inset by the control's own inset on every side, three by default")
    void theIconInsetIsOneNumberForAllFourSides() {
        // A real item, so the drawing path is the one a screen uses: the sprite's box is decided while
        // drawing, and a test that only recomputed the arithmetic could not tell a live read from a
        // constant that had drifted from it.
        MinecraftTestBootstrap.boot();

        RecordingRenderer shipped = RecordingRenderer.create();
        new ArmatureButton(0, 0, 20, 20, Component.literal("Book"), () -> {
        }).icon(new ItemStack(Items.STONE)).draw(shipped, 0L);

        RecordingRenderer element = RecordingRenderer.create();
        new ArmatureButton(0, 0, 16, 16, Component.literal("Book"), () -> {
        }).icon(new ItemStack(Items.STONE)).iconInset(2).draw(element, 0L);

        // The recorder holds the box in its coordinates: x, y and the size, which is the pair this rule
        // is about. A test of the appearance would need a client; a test of the inset does not.
        RecordingRenderer.Call plain = shipped.icons().get(0);
        assertEquals(3, plain.x(), "the default inset is three, which is what every control drew before");
        assertEquals(3, plain.y(), "vertically too, because the box is the height less twice the inset");
        assertEquals(14, plain.x2(), "so a 20-pixel control draws a 14-pixel sprite");

        RecordingRenderer.Call tight = element.icons().get(0);
        assertEquals(2, tight.x(), "an inset of two puts the sprite two pixels in");
        assertEquals(2, tight.y(), "on every side, not two on the left and one on the right");
        assertEquals(12, tight.x2(), "and a 16-pixel control draws a 12-pixel sprite");
    }
}

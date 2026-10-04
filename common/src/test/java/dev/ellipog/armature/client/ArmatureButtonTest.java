package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.Themes;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A control's label ink, and whose theme it comes from.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>The ink used to be captured in the constructor — {@code textColour = ArmatureTheme.title()} — and
 * the screen builds its controls once and then draws them inside a chapter's scope. So a themed card
 * showed the chapter's fills under the main theme's label ink, and the fix is that the role is resolved
 * at draw time. A recorder sees which colour was actually written, which is the only way to tell a live
 * read from a captured one.
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
}

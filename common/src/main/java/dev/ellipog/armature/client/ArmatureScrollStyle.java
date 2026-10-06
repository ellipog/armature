package dev.ellipog.armature.client;

import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.ScrollBar;

/**
 * What a scrollbar looks like in each of its states — the appearance half of {@link ScrollBar}, kept
 * beside {@code ArmatureControlStyle} and for the same reason.
 *
 * <h2>Why the states are derived rather than declared</h2>
 *
 * <p>The theme has two scrollbar colours: the groove, and the grip that runs in it. It does not have
 * four, and this class is the argument for why it should not.
 *
 * <p>A hover and a held appearance are not new colours in the sense the tokens are for — nothing here
 * is "the blue that means available" or "the surface a tooltip sits on", chosen by a person against
 * the palette it lives in. They are <b>the same colour, more so</b>: the grip, further from the
 * groove, because that is the only thing a hover on a three-pixel bar can usefully say. Spelling that
 * as two more tokens would mean two more hand-chosen values in each of the sixteen built-ins — thirty
 * values to keep consistent with a pair they are derived from anyway — and the failure mode of
 * getting one wrong is a theme whose grip <i>loses</i> contrast exactly when the pointer arrives on
 * it, which is the one moment it has to be findable.
 *
 * <p>{@code Colour.shade} exists for this and says so in its own words — <i>"make this a bit darker
 * without deciding what darker is"</i>, the operation a theme's derived shades need. The only thing
 * it cannot answer is <b>which way</b>, and that is a question about two colours rather than about
 * taste, so it is asked: {@link Colour#luminance} of the grip against the groove, and the shade goes
 * away from the track. A dark theme brightens its grip on hover; a light theme darkens it; both get a
 * grip that reads harder, and neither needed a number from anybody.
 *
 * <h2>No state, and no clock</h2>
 *
 * <p>Like {@code ArmatureControlStyle}, this answers a question rather than holding an answer: the
 * ease between resting and hovered lives in the bar, which is the thing being pointed at, and the
 * colours live here, where the theme is read. A class that remembered a state would be a second place
 * a state is kept, which is how two places come to disagree about which state is on screen.
 */
public final class ArmatureScrollStyle {

    /**
     * How far a hovered grip is moved from the resting one, as a fraction towards white or black.
     *
     * <p>Visible on three pixels without being a different colour, which is the whole budget: a grip
     * that changed hue on hover would read as a selection rather than as an acknowledgement.
     */
    public static final float HOVER_LIFT = 0.18F;

    /**
     * The same for a grip being dragged.
     *
     * <p>Nearly twice the hover, because the two have to be told apart at a glance while the pointer
     * is on the bar — the hover is what a grip looks like when you might grab it, and this is what it
     * looks like when you have.
     */
    public static final float ACTIVE_LIFT = 0.34F;

    private ArmatureScrollStyle() {
    }

    /**
     * The four colours a bar is drawn from, from the theme in force.
     *
     * <p>Built once per frame at each site rather than cached: a theme can change while a screen is
     * open — that is what the appearance editor is for — and a cached skin would be the one thing on
     * screen still in the old theme.
     */
    public static ScrollBar.Skin skin() {
        int track = ArmatureTheme.scrollTrack();
        int thumb = ArmatureTheme.scrollThumb();
        // Away from the groove, whichever way that is. See the class note: this is the one decision
        // a theme cannot be asked for, because it is a relationship between two of its own colours
        // rather than a third choice.
        float away = Colour.luminance(thumb) >= Colour.luminance(track) ? 1F : -1F;
        return new ScrollBar.Skin(track, thumb,
                Colour.shade(thumb, away * HOVER_LIFT),
                Colour.shade(thumb, away * ACTIVE_LIFT));
    }
}

package dev.ellipog.armature.client;

import dev.ellipog.armature.client.ui.Theme;

/**
 * What a control looks like in each of its states.
 *
 * <h2>Why this is a class and not four lines inside a button</h2>
 *
 * <p>Because a button is not the only thing that has to draw one. A preview, a screenshot comparison or
 * a second control type all need to answer the same question — <i>what does a selected row look like
 * right now</i> — and the answer has to be <b>the same answer</b>. Two descriptions of one appearance
 * drift, and the direction they drift in is always the same: the one that is not on screen is the one
 * that is wrong.
 *
 * <p>That is not a theory. The preview that draws this UI outside the game had a hand-written copy of
 * these rules, and for a whole round it drew the selected chapter as a hover-coloured box with an
 * accent border while the game drew no box at all. So there were two screens in the world: the one
 * people played and the one the pictures showed. Which of them a person believed depended only on
 * whether they had launched the game.
 *
 * <p>So the rules live in one place that production code calls and tooling reads, and
 * {@code .utils/GeometryDump.java} prints this class's own output as a table the preview draws from.
 * The tool is not allowed to have an opinion.
 *
 * <h2>Precedence, and the one decision in here worth disagreeing with</h2>
 *
 * <ol>
 *   <li><b>Disabled beats everything.</b> A control that cannot be used must not look selected, because
 *       "you can press this" and "you cannot" is the more urgent of the two facts.</li>
 *   <li><b>Selected beats held and hovered.</b> A selected fill does not change when the pointer
 *       arrives or when it is pressed.</li>
 *   <li><b>Held beats hovered.</b> Holding is the more specific state.</li>
 *   <li><b>Accent beats hovered</b> for its fill, which is why an accent control looks the same whether
 *       or not the pointer is over it — deliberate, since it is already the loudest thing on the screen
 *       and a hover that brightens it further reads as a flicker.</li>
 * </ol>
 *
 * <p>Rule 2 is the arguable one: it means the row you are on gives no press feedback. The alternative
 * is a fifth fill for "selected and hovered", and then a sixth for "selected and held", and a seventh
 * for the same pair on an accent control — a state matrix that grows by hand and that nobody can hold
 * in their head. The trade taken instead: <b>the selected fill is constant, so it can never be confused
 * with a transient one.</b> The feedback for choosing a row is the selection moving onto it, which it
 * does on the same frame. If that ever reads as unresponsive, the fix is one value and one line in
 * {@link #fill} — and it will change the preview too, which is the point of this class.
 *
 * <h2>The edge deliberately ignores the interaction</h2>
 *
 * <p>{@link #edge} takes all four parameters and uses two. That is not an oversight, and the signature
 * is not accidental: it is the same shape as {@link #fill} so a caller passes the same four things to
 * both and neither can be updated without the other being looked at. What it does with them is
 * deliberate — the border says <b>what this control is</b>, and hover must not be able to erase that.
 * A selected row whose border faded on hover would be a row that stops looking selected exactly when
 * you are about to click it.
 */
public final class ArmatureControlStyle {

    private ArmatureControlStyle() {
    }

    /** What kind of control this is. At most one applies; the order in the class comment decides. */
    public enum Variant {

        /** An ordinary control. */
        PLAIN,

        /** The one the screen is about — one per screen, no more. */
        ACCENT,

        /** The current one — the chapter being shown, the tab being read. */
        SELECTED,

        /** No fill at all: text that behaves like a control. A link, a section header. */
        FLAT
    }

    /** Whether this variant is drawn with a fill and a border. False only for {@link Variant#FLAT}. */
    public static boolean drawsBox(Variant variant) {
        return variant != Variant.FLAT;
    }

    /**
     * The fill for a control in this state.
     *
     * @param variant what kind of control it is
     * @param enabled whether it can be used at all
     * @param held    whether the pointer is down on it
     * @param hovered whether the pointer is over it
     */
    public static int fill(Variant variant, boolean enabled, boolean held, boolean hovered) {
        Theme.Controls c = ArmatureTheme.controls();
        if (!enabled) {
            return c.disabled();
        }
        if (variant == Variant.SELECTED) {
            // Constant, and that is the decision. See the class comment.
            return c.selected();
        }
        if (held) {
            return c.held();
        }
        if (variant == Variant.ACCENT) {
            return c.accent();
        }
        if (hovered) {
            return c.hover();
        }
        return c.fill();
    }

    /**
     * The fill for a control in this state, at a hover that is part-way through its transition.
     *
     * <h2>Why this is computed from {@link #fill} rather than beside it</h2>
     *
     * <p>The resting and hovered fills are taken from the method above and blended. That is not
     * laziness — it is the property that makes animating this safe: <b>the endpoints are exactly the
     * colours that were there before</b>, so a settled transition is bit-identical to the old
     * behaviour. Nothing about this class's appearance changes; the only new thing is the frames in
     * between.
     *
     * <p>It also means the precedence rules stay in one place. Writing the animated version as its own
     * chain of conditions would be a second description of which state wins, and the two would drift
     * the first time a variant was added — which is precisely the failure this class was extracted to
     * stop. See the class comment on the preview that had its own copy of these rules.
     *
     * <h2>What is deliberately not animated</h2>
     *
     * <p><b>Held.</b> A press is a discrete event and its feedback has to be immediate — a button that
     * eases into its pressed colour reads as lag, which is the one thing a control must never do. So
     * {@code held} is checked before the blend, and a press snaps. The hover is the state that eases,
     * because it is the one the pointer arrives at gradually.
     *
     * @param hoverProgress 0 at rest, 1 fully hovered, and anything between mid-transition. Clamped,
     *     because an {@link dev.ellipog.armature.client.ui.kit.Easing#BACK_OUT} curve passes 1.
     */
    public static int fillAt(Variant variant, boolean enabled, boolean held, float hoverProgress) {
        int resting = fill(variant, enabled, held, false);
        int hovered = fill(variant, enabled, held, true);
        if (resting == hovered) {
            // Selected, accent, disabled and held all resolve to the same colour either way, so there
            // is nothing to blend and no allocation of a lerp's arithmetic. Worth the branch: it is
            // the common case, since most controls on this screen are not hovered.
            return resting;
        }
        return dev.ellipog.armature.client.ui.kit.Colour.lerp(resting, hovered, hoverProgress);
    }

    /**
     * The border for a control in this state.
     *
     * <p>Ignores {@code held} and {@code hovered} on purpose — see the class comment. The parameters are
     * here so that a caller passes the same four values to both methods.
     */
    public static int edge(Variant variant, boolean enabled, boolean held, boolean hovered) {
        Theme.Controls c = ArmatureTheme.controls();
        if (!enabled) {
            return ArmatureTheme.panelEdge();
        }
        if (variant == Variant.SELECTED) {
            return c.edgeSelected();
        }
        if (variant == Variant.ACCENT) {
            return c.edgeAccent();
        }
        return c.edge();
    }

    /**
     * The colour a label should be drawn in.
     *
     * <p>With the fills, because the two are one decision: a label bright enough for a dark plain fill
     * is not automatically right on a bright selected one, and a screen that picked its text colour
     * separately from its background is how you get text you cannot read.
     */
    public static int text(Variant variant, boolean enabled) {
        if (!enabled) {
            return ArmatureTheme.blocked();
        }
        return variant == Variant.ACCENT || variant == Variant.SELECTED
                ? ArmatureTheme.title()
                : ArmatureTheme.body();
    }
}

package dev.ellipog.armature.client.render;

import java.util.Objects;

/**
 * How an item icon is best drawn in a box: as a flat sprite, or through the game's own item pipeline.
 *
 * <h2>The choice, and why it is worth making</h2>
 *
 * <p>{@code GuiGraphics.renderItem} runs the whole 3-D item path: it resolves a baked model, sets up the
 * item's own transforms and lighting, walks every quad of every layer, and — in a GUI — ends the current
 * batch to do it, because the item render types are not the GUI's. It also writes depth at Z = 150, which
 * is why this mod needs a raised-Z chrome layer to draw anything over an icon at all.
 *
 * <p>A blit of the model's own particle sprite is one textured quad through the GUI pipeline. For an item
 * whose appearance <i>is</i> one textured quad — which is what most items are — that is the same picture
 * for a fraction of the work: no model resolution, no transforms, no per-quad walk, no batch boundary,
 * and no depth written.
 *
 * <h2>What must go through the pipeline, and how each is detected</h2>
 *
 * <p>Every one of these was read out of the 1.21.1 sources rather than recalled, and every accessor named
 * here exists in the version this project compiles against:
 *
 * <ul>
 *   <li><b>A tinted model</b> — a quad whose tint index <i>actually carries a colour</i> for this stack.
 *       Potions, tipped arrows, dyed leather, spawn eggs and modded tiered tools are <i>all</i> a greyscale
 *       texture plus a colour from {@code ItemColors}; a flat sprite would draw them grey. This is the gate
 *       that matters most, because it is the one that would look like a palette bug rather than a missing
 *       overlay — and it is why the fact is a colour lookup rather than {@code BakedQuad.isTinted}:
 *       {@code ItemModelGenerator} passes the <i>layer number</i> as the tint index, so every quad of every
 *       {@code item/generated} item reads as tinted, and a stick would be refused along with a potion.</li>
 *   <li><b>A second sprite</b> — {@code oneSprite}. A blit draws the particle icon and nothing else, so a
 *       model whose quads do not all use that sprite loses whatever the others were: a two-layer item would
 *       be drawn as its base with the overlay missing. Not a tint and not a missing texture — a missing
 *       layer, which is its own reason.</li>
 *   <li><b>A 3-D model</b> — {@code isGui3d()}. <b>Read carefully: this is a <i>lighting</i> flag, not a
 *       geometry one.</b> {@code BlockModel.getGuiLight()} answers {@code SIDE} when nothing says otherwise
 *       and {@code GuiLight.SIDE.lightLikeBlock()} is true, so this is the "light it like a block" bit; an
 *       {@code item/generated} model declares {@code front} and is therefore false. It does <i>not</i> mean
 *       "has a cube in it" — an earlier version of this file treated it as such and was wrong about every
 *       item in the game.</li>
 *   <li><b>A model that reads block light</b> — {@code usesBlockLight()}. It wants the world's lighting,
 *       which a GUI blit cannot supply.</li>
 *   <li><b>A custom renderer</b> — {@code isCustomRenderer()}. A chest, a shulker box, a banner: the
 *       model is a placeholder and the picture comes from a block entity renderer that a sprite blit
 *       would never call.</li>
 *   <li><b>A missing model</b> — the model manager's own placeholder, whose sprite is the
 *       missing-texture chequer. Blitting it would turn "this item is not installed" into a confident
 *       purple square.</li>
 * </ul>
 *
 * <h2>What is deliberately not a gate</h2>
 *
 * <p><b>Animation.</b> The plan for this listed an "animated" fact, and this version has one — but it is
 * private to {@code ItemRenderer} and it means exactly {@code compass or clock}, where it exists to scale
 * the enchantment glint rather than to decide how the icon is drawn. It is not needed here: the model is
 * resolved per call, a compass's own model overrides pick the texture for the angle it is showing, so the
 * sprite blitted is the same frame the pipeline would have drawn. An animated <i>texture</i> is likewise
 * no obstacle — the sprite is an atlas region and it animates in place.
 *
 * <p><b>Layers.</b> It also listed a quad-layer count, on the reasoning that a multi-pass model would
 * lose its secondary overlay. This version's {@code BakedModel} has no such API — there is no
 * {@code getRenderPasses} and no layered flag. What covers the case is {@code oneSprite} above, which is
 * the same concern asked of the quads themselves.
 *
 * <h2>Game-free on purpose</h2>
 *
 * <p>The facts arrive as booleans, so the rule can be asserted without a client and the version-specific
 * accessors stay in the one file that has to change when the version does.
 */
public enum IconPlan {

    /** The model is one textured quad: blit its sprite. */
    FLAT,

    /** The item has to go through the pipeline: draw it. */
    LIVE;

    /**
     * What the rule decides from — every fact is about the <b>model</b>, never about the box it is going
     * into. A size rule would be a second, invisible reason for an icon to change appearance; the caller
     * already decides detail by zoom, and this decides it by capability.
     *
     * @param tinted whether a tint index on this model actually carries a colour for this stack — the
     *     colour lookup, not {@code isTinted}, which is true for every generated item
     * @param oneSprite whether every quad of the model uses the particle icon's own sprite, so a blit of
     *     that one sprite is the whole picture rather than its base layer
     */
    public record Facts(boolean customRenderer, boolean gui3d, boolean usesBlockLight, boolean tinted,
                        boolean missingModel, boolean hasParticleIcon, boolean oneSprite) {

        public Facts {
            Objects.requireNonNull(customRenderer, "customRenderer");
        }
    }

    /** Which way one item icon is drawn. */
    public static IconPlan of(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        if (facts.customRenderer() || facts.gui3d() || facts.usesBlockLight() || facts.tinted()
                || facts.missingModel() || !facts.hasParticleIcon() || !facts.oneSprite()) {
            return LIVE;
        }
        return FLAT;
    }

    // ------------------------------------------------------------------
    // The experiment
    // ------------------------------------------------------------------

    /**
     * Which way a flat icon is drawn, for the arm-by-arm experiment that identifies why the first form drew
     * nothing. Zero — the pipeline — is the default and the correct one.
     *
     * <h2>Why a system property rather than an API</h2>
     *
     * <p>Because this is a diagnostic that has to be flippable in a running game by the person looking at the
     * screen, and because the answer is a fact about Minecraft's blit rather than about this library: when it
     * is known, this method and its three arms are deleted. A cleaner switch would be a seam call with a
     * caller in the mod, which would make a temporary experiment into permanent API — the wrong trade for
     * something whose whole purpose is to be removed.
     *
     * <p>Read per call rather than cached: the whole point is that setting it takes effect immediately, and
     * the cost is a property lookup beside a model resolution and a quad walk.
     */
    public static int arm() {
        return Integer.getInteger("armature.iconmode", 0);
    }

    // ------------------------------------------------------------------
    // The counts, for the dev log
    // ------------------------------------------------------------------

    private static long flat;
    private static long live;

    /** How many decisions of each kind have been taken. See {@link #drain}. */
    public record Counts(long flat, long live) {
    }

    /** Records one decision. Called where the decision is taken, so nothing can be drawn uncounted. */
    public static void counted(IconPlan plan) {
        if (plan == FLAT) {
            flat++;
        }
        else {
            live++;
        }
    }

    /**
     * The counts since the last drain, and resets them.
     *
     * <p>A drain rather than a read, because the only reader is a once-a-second log line and what it
     * wants is "how many this second" — a cumulative total would say a client had drawn a million icons
     * without saying whether it was still drawing any. The arithmetic lives here rather than in the
     * reader so that two readers cannot disagree about where a second begins.
     */
    public static Counts drain() {
        Counts counts = new Counts(flat, live);
        flat = 0;
        live = 0;
        return counts;
    }
}

package dev.ellipog.armature.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The themes that ship, and the lookup that finds one by name.
 *
 * <h2>Why this is not {@code Theme} itself</h2>
 *
 * <p>Because a record's own file should be its shape, not a thousand lines of hex. The previous
 * arrangement had the three built-ins as {@code public static final} fields on {@code Theme}, which
 * meant the class that defines what a theme <i>is</i> was mostly a list of examples — and every theme
 * added made the definition harder to find. The catalogue is a different job.
 *
 * <h2>Every theme here is a patch, and that is the point rather than a convenience</h2>
 *
 * <p>The sixteen are written as {@link ThemePatch}es over a base, using the same mechanism a data
 * file uses to reskin a group. Two things fall out of that, and the second is the one that matters:
 *
 * <ul>
 *   <li>A derived theme states only what it changes, so it reads as a description of its own character.
 *       {@link #AMETHYST} is thirty lines rather than forty-three, and the lines it has are the ones that
 *       make it amethyst.</li>
 *   <li><b>The built-in set is itself the proof that the override system is expressive enough.</b> If
 *       sixteen themes of quite different characters can all be expressed as patches, then a pack
 *       author with the same mechanism is not being given a lesser version of it. Had the catalogue been
 *       hand-written full palettes while patches were the "custom" path, the two would have diverged and
 *       the divergence would only show up as a file that could not do something a built-in could.</li>
 * </ul>
 *
 * <h2>Every built-in states every token, and that is checked</h2>
 *
 * <p>{@link #MODERN} is the theme every screenshot in this repository was taken with, so its forty-three
 * values are all stated — a theme system that quietly moved one pixel of the palette would make every
 * screenshot before it incomparable with every one after, and would make a rendering regression
 * indistinguishable from a colour decision. A test asserts that its map is complete, so a new token can
 * never silently inherit a zero.
 *
 * <p>The other fifteen are stated in full too, and the guard in {@link #set} throws at class
 * initialisation if one is not — the same completeness as the reference palette, enforced rather than
 * remembered, because a token that silently inherited {@code modern}'s value is a decision nobody made.
 * A theme in a file is the opposite by design: it is a diff, and everything it leaves out comes from
 * the theme it names.
 */
public final class Themes {

    private Themes() {
    }

    /**
     * The seed a first theme is built from.
     *
     * <p>All zeros, and that is safe <b>only</b> because the patch that fills it is asserted complete.
     * Worth being explicit about, since an all-transparent seed with an incomplete patch would produce a
     * theme that draws nothing at all — the most confusing possible failure, and one that looks like a
     * renderer bug rather than a missing colour. The assertion lives in {@code ThemesTest}.
     */
    private static Theme seed(String name) {
        return Theme.from(name, new int[ThemeToken.ALL.size()], 4, 140L,
                dev.ellipog.armature.client.ui.kit.Easing.QUAD_OUT, CanvasBackground.NONE);
    }

    /**
     * A colour map from alternating {@code "id", "#hex"} pairs.
     *
     * <p>Varargs of strings rather than a map literal because the thing being written is a list of
     * colours, and {@code "panel", "#24242E"} reads as one. It also means the palette in this file looks
     * like the palette in a theme file, which is the form an author will actually be copying.
     *
     * <p>Throws on an odd number of strings or an unreadable hex value, at class initialisation. A
     * shipped palette that cannot be parsed should not be a warning in a log — it is this project's own
     * data, and the only reason it could be wrong is a typo made in this file.
     */
    private static Map<String, Integer> set(String name, String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("a colour list needs id/value pairs, got "
                    + pairs.length + " strings");
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String id = pairs[i];
            String hex = pairs[i + 1];
            if (!ThemeToken.exists(id)) {
                throw new IllegalArgumentException("'" + id + "' in the shipped theme '" + name
                        + "' is not a colour token. The ones there are: " + ThemeToken.ids());
            }
            Integer argb = dev.ellipog.armature.client.ui.kit.Colour.fromHex(hex);
            if (argb == null) {
                throw new IllegalArgumentException("'" + hex + "' is not a hex colour, in the value for '"
                        + id + "'");
            }
            if (out.put(id, argb) != null) {
                throw new IllegalArgumentException("'" + id + "' is set twice in one theme");
            }
        }
        // Every shipped theme states every token, and this is the guard that keeps it true rather
        // than a convention: an omitted token silently becomes modern's value, and a token added to
        // the registry later would be inherited by all sixteen themes with nobody deciding it. So a
        // theme that leaves one out fails at class initialisation, naming itself and the holes --
        // the same shape as `Theme.allColours`'s length check, and the loudest way for it to be wrong.
        List<String> missing = new ArrayList<>();
        for (ThemeToken token : ThemeToken.ALL) {
            if (!out.containsKey(token.id())) {
                missing.add(token.id());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the shipped theme '" + name + "' does not state "
                    + missing + ". A built-in states every token: see the guard's comment.");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The reference: modern
    // ------------------------------------------------------------------

    /**
     * The appearance this project has shipped with, unchanged.
     *
     * <p>Every one of the forty-three values is stated, and the colours are byte-identical to the constants
     * they came from. That matters more than it looks: this is the theme every screenshot in the
     * repository was taken with, so if it moved, a rendering regression and a palette decision would
     * look the same in a diff.
     *
     * <p>Seven values here are new with the token registry — the four node borders and the three
     * scrollbar and tooltip colours — and every one of them is deliberately a colour that already
     * existed in this theme rather than a new invention. A theme system that changed the default
     * appearance on the day it arrived would be indistinguishable from one that broke it.
     */
    public static final Map<String, Integer> MODERN_COLOURS = set("modern", 
            // --- surfaces, deepest first ---
            "dim", "#B80A0A0D",
            "canvas", "#FF0A0A0E",
            "recessed", "#FF191920",
            "panel", "#FF24242E",
            "raised", "#FF30303C",
            "panelEdge", "#FF46465A",

            // --- text ---
            "title", "#FFFFFFFF",
            "body", "#FFC6C6D4",
            "faint", "#FF80808F",
            "heading", "#FF9E9EB0",

            // --- progression: each is a state's word AND its node's border ---
            "available", "#FF7FB4E8",
            "inProgress", "#FFE8C868",
            "complete", "#FF86CE8A",
            "blocked", "#FF66666F",

            // --- the graph ---
            "nodeFill", "#FF2E2E3A",
            "nodeDim", "#9C000000",
            "nodeDoneWash", "#3086CE8A",
            "nodeEdgeBlocked", "#FF43434F",
            "nodeEdgeAvailable", "#FF7FB4E8",
            "nodeEdgeInProgress", "#FFE8C868",
            "nodeEdgeComplete", "#FF86CE8A",
            "line", "#FF505064",
            "lineDone", "#FF5F8A62",
            "selectedRing", "#FFFFFFFF",
            "hoverRing", "#80FFFFFF",
            "canvasPattern", "#3380808F",

            // --- rows and labels ---
            "rowHover", "#22FFFFFF",
            "labelBackdrop", "#F00A0A0E",

            // --- scrollbar ---
            "scrollTrack", "#FF1C1C24",
            // Dimmer than it was, on the same feedback that produced the tokens in the first place.
            // `#4C4C62` is a mid slate that reads as a solid object beside a list of buttons; a grip
            // wants to be discoverable and then get out of the way, and it is the one element on the
            // screen that is pure chrome. Dimmer than this and it stops being findable, which is the
            // other half of the report to avoid.
            "scrollThumb", "#FF3E3E50",

            // --- tooltips ---
            "tooltipFill", "#F00A0A0E",
            "tooltipEdge", "#FF5C5C74",
            // The same ink as body, which is what tooltips were drawn in before this token existed:
            // a theme that wants its tooltips louder or quieter now has somewhere to say so.
            "tooltipText", "#FFC6C6D4",

            // --- controls ---
            "fill", "#FF3E3E4C",
            "hover", "#FF4A4A5A",
            "held", "#FF565668",
            "selected", "#FF5A86CC",
            "disabled", "#FF2A2A34",
            "accent", "#FF33597F",
            "edge", "#FF4C4C62",
            "edgeAccent", "#FF6A9AC8",
            "edgeSelected", "#FF9CC4F0",
            "edgeBright", "#FF5C5C74");

    /** The reference theme. Every value stated; see {@link #MODERN_COLOURS}. */
    public static final Theme MODERN = ThemePatch.of("modern", MODERN_COLOURS)
            .applyTo(seed("modern"))
            .withRadius(4)
            .withMotion(140L)
            .withEasing(dev.ellipog.armature.client.ui.kit.Easing.QUAD_OUT);

    /**
     * The theme a client starts with. {@link #MODERN}'s palette, square corners, and the same easing.
     *
     * <h2>What this is, and why it is not just {@code modern}</h2>
     *
     * <p>Identical to {@link #MODERN} in every colour and in its motion, with one difference: a corner
     * radius of zero. It exists so that the shipped appearance can change without changing what
     * {@code modern} means — because {@code modern} is the reference the assertions and the screenshots
     * are written against, and a theme whose name says "the current look" cannot be both that and a fixed
     * reference. Two constants, one of which happens to derive from the other.
     *
     * <h2>Derived from {@code MODERN} rather than restating its palette</h2>
     *
     * <p>Because it <i>is</i> modern's palette, and a copy is a copy that can drift. Written out, the two
     * would agree until somebody adjusted one of them, and the disagreement would show up as the default
     * theme being a slightly different blue from the reference theme with no comment saying why. Built by
     * derivation, there is one list of colours and this theme cannot disagree with it.
     *
     * <p><b>Square, but still animated.</b> That combination is deliberate and it is the reason this is
     * not simply {@code vanilla_plus} with different colours. {@code vanilla_plus} is square <i>and</i>
     * still — no animation at all — which is a statement about motion; this theme makes no such statement
     * and keeps modern's 140ms eased curve. So the corners are Minecraft's and the feel is not, and that
     * is the shipped default rather than either of the two themes it sits between.
     *
     * <h2>Why the default is square</h2>
     *
     * <p>A rounded corner is a decision about this interface's own style, and this is a library that will
     * grow to support other mods' screens. Square is the neutral choice: it is what vanilla draws, it is
     * what a pack author's own textures will assume, and it is the one setting no theme has to opt out of.
     * A pack that wants rounded corners asks for {@code modern} or writes its own — which is exactly the
     * kind of decision a default should be leaving to somebody else.
     */
    public static final Theme DEFAULT = MODERN.withName("default").withRadius(0);

    // ------------------------------------------------------------------
    // The original three
    // ------------------------------------------------------------------

    /**
     * Named for the book the screen is, rather than the tool it is made of: a dark library.
     *
     * <p>Two values differ from {@link #MODERN} by more than a tint, and both are deliberate:
     *
     * <p><b>The corner radius is 8 against 4.</b> It is the theme that proves the radius is a parameter
     * and not a constant — with two themes at the same radius, a radius threaded through the wrong place
     * would look correct.
     *
     * <p><b>The selected fill is brass rather than blue</b>, because a parchment interface with a bright
     * blue selection chip is two palettes arguing. That is the kind of thing only a second theme finds: a
     * colour that looked fine on its own turns out to have been chosen against the surfaces around it.
     */
    public static final Theme TOME = ThemePatch.of("tome", set("tome", 
            "dim", "#B2100C08",
            "canvas", "#FF12100C",
            "recessed", "#FF1E1A14",
            "panel", "#FF2A241B",
            "raised", "#FF37301F",
            "panelEdge", "#FF54492F",
            "title", "#FFF4E8D0",
            "body", "#FFD8C9A8",
            "faint", "#FF9A8B6C",
            "heading", "#FFB8A582",
            "available", "#FF8FB4D9",
            "inProgress", "#FFE8C868",
            "complete", "#FF9CC97F",
            "blocked", "#FF6E6552",
            "nodeFill", "#FF332C22",
            "nodeDim", "#9C0A0806",
            "nodeDoneWash", "#309CC97F",
            "nodeEdgeBlocked", "#FF4A4132",
            "nodeEdgeAvailable", "#FF8FB4D9",
            "nodeEdgeInProgress", "#FFE8C868",
            "nodeEdgeComplete", "#FF9CC97F",
            "line", "#FF5C5140",
            "lineDone", "#FF6E8A55",
            "selectedRing", "#FFF4E8D0",
            "hoverRing", "#80F4E8D0",
            "canvasPattern", "#339A8B6C",
            "rowHover", "#22F4E8D0",
            "labelBackdrop", "#F012100C",
            "scrollTrack", "#FF231E16",
            "scrollThumb", "#FF5C5140",
            "tooltipFill", "#F012100C",
            "tooltipEdge", "#FF6E6552",
            "tooltipText", "#FFD8C9A8",
            "fill", "#FF3A3226",
            "hover", "#FF453C2E",
            "held", "#FF504636",
            "selected", "#FF7A6A3E",
            "disabled", "#FF2A241B",
            "accent", "#FF5C4A26",
            "edge", "#FF5C5140",
            "edgeAccent", "#FF9A8348",
            "edgeSelected", "#FFD9C07A",
            "edgeBright", "#FF6E6552"))
            .applyTo(MODERN)
            .withRadius(8)
            // The surface the name promises: one-pixel specks, one cell in four -- parchment grain
            // rather than noise, which is the line a speckle has to walk.
            .withBackground(new CanvasBackground(CanvasBackground.Kind.SPECKLE,
                    CanvasBackground.Space.GRAPH, 18,
                    new CanvasBackground.Tuning(1, 4, false), CanvasBackground.Image.NONE));

    /**
     * Vanilla's own greys, tidied — for a player who does not want their game to look restyled.
     *
     * <p>Two values are <b>zero</b>, and those are the two that catch things:
     *
     * <p><b>Radius zero.</b> That is what a player means by "looks like Minecraft", and it is the case an
     * implementation treating the radius as a minimum of one gets wrong. A theme cannot express "square"
     * if the code has decided a corner is at least a pixel.
     *
     * <p><b>Motion zero.</b> Vanilla has no UI animation, so neither does this — and a duration of zero
     * means "instant", not "unset". An earlier round read zero as "no opinion" and used the built-in
     * duration, so this theme silently behaved like {@link #MODERN}.
     */
    public static final Theme VANILLA_PLUS = ThemePatch.of("vanilla_plus", set("vanilla_plus", 
            "dim", "#B8000000",
            "canvas", "#FF111111",
            "recessed", "#FF1B1B1B",
            "panel", "#FF242424",
            "raised", "#FF2E2E2E",
            "panelEdge", "#FF4A4A4A",
            "title", "#FFFFFFFF",
            "body", "#FFD0D0D0",
            "faint", "#FF909090",
            "heading", "#FFA0A0A0",
            "available", "#FF6FA8DC",
            "inProgress", "#FFDDC46A",
            "complete", "#FF7FCB7F",
            "blocked", "#FF6A6A6A",
            "nodeFill", "#FF2A2A2A",
            "nodeDim", "#9C000000",
            "nodeDoneWash", "#307FCB7F",
            "nodeEdgeBlocked", "#FF4A4A4A",
            "nodeEdgeAvailable", "#FF6FA8DC",
            "nodeEdgeInProgress", "#FFDDC46A",
            "nodeEdgeComplete", "#FF7FCB7F",
            "line", "#FF555555",
            "lineDone", "#FF5F8A62",
            "selectedRing", "#FFFFFFFF",
            "hoverRing", "#80FFFFFF",
            "canvasPattern", "#33909090",
            "rowHover", "#22FFFFFF",
            "labelBackdrop", "#F0000000",
            "scrollTrack", "#FF1B1B1B",
            "scrollThumb", "#FF4A4A4A",
            "tooltipFill", "#F0000000",
            "tooltipEdge", "#FF5A5A5A",
            "tooltipText", "#FFD0D0D0",
            "fill", "#FF3A3A3A",
            "hover", "#FF454545",
            "held", "#FF505050",
            "selected", "#FF5A86CC",
            "disabled", "#FF262626",
            "accent", "#FF33597F",
            "edge", "#FF4A4A4A",
            "edgeAccent", "#FF6A9AC8",
            "edgeSelected", "#FFB0B0B0",
            "edgeBright", "#FF5A5A5A"))
            .applyTo(MODERN)
            .withRadius(0)
            .withMotion(0L)
            .withEasing(dev.ellipog.armature.client.ui.kit.Easing.LINEAR);

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    /**
     * Maximum contrast, for reading a screen you cannot comfortably see.
     *
     * <h2>Why this is a theme and not a "mode"</h2>
     *
     * <p>Because the toolkit's accessibility story is already that a theme is the unit of appearance —
     * {@link #VANILLA_PLUS}'s zero motion and square corners live here for the same reason. A high-contrast
     * <i>flag</i> would be a second axis with its own precedence rules against the theme, which is the
     * state matrix this codebase has already rejected once in {@code ArmatureControlStyle}.
     *
     * <p>What "high contrast" means here, concretely, and each is a deliberate departure from the
     * default's look: surfaces are pushed to near-black, text to pure white, every state colour is a
     * bright primary with no muddiness, and the two things that are <b>washes</b> in every other theme —
     * {@code rowHover} and {@code nodeDim} — are much more opaque, because a subtle hint about where a
     * click will land is exactly the information this theme exists to make unmissable.
     */
    public static final Theme HIGH_CONTRAST = ThemePatch.of("high_contrast", set("high_contrast", 
            "dim", "#E0000000",
            "canvas", "#FF000000",
            "recessed", "#FF0A0A0A",
            "panel", "#FF000000",
            "raised", "#FF141414",
            "panelEdge", "#FFFFFFFF",
            "title", "#FFFFFFFF",
            "body", "#FFFFFFFF",
            "faint", "#FFC8C8C8",
            "heading", "#FFFFFFFF",
            "available", "#FF66CCFF",
            "inProgress", "#FFFFE066",
            "complete", "#FF66FF99",
            "blocked", "#FFB0B0B0",
            "nodeFill", "#FF141414",
            "nodeDim", "#CC000000",
            "nodeDoneWash", "#3366FF99",
            "nodeEdgeBlocked", "#FFB0B0B0",
            "nodeEdgeAvailable", "#FF66CCFF",
            "nodeEdgeInProgress", "#FFFFE066",
            "nodeEdgeComplete", "#FF66FF99",
            "line", "#FFB0B0B0",
            "lineDone", "#FF66FF99",
            "selectedRing", "#FFFFFFFF",
            "hoverRing", "#FFFFFFFF",
            "canvasPattern", "#33C8C8C8",
            "rowHover", "#55FFFFFF",
            // Stated rather than inherited, which the guard in `set` now enforces: a label sits on this
            // theme's pure black, so the backdrop behind it is the same black and nothing of the graph
            // shows through the words.
            "labelBackdrop", "#FF000000",
            "scrollTrack", "#FF000000",
            "scrollThumb", "#FFFFFFFF",
            "tooltipFill", "#FF000000",
            "tooltipEdge", "#FFFFFFFF",
            "tooltipText", "#FFFFFFFF",
            "fill", "#FF1E1E1E",
            "hover", "#FF3C3C3C",
            "held", "#FF5A5A5A",
            "selected", "#FF0066AA",
            "disabled", "#FF101010",
            "accent", "#FF0055AA",
            "edge", "#FFF0F0F0",
            "edgeAccent", "#FF88CCFF",
            "edgeSelected", "#FFFFFFFF",
            "edgeBright", "#FFFFFFFF"))
            .applyTo(MODERN)
            .withRadius(0)
            .withMotion(0L)
            .withEasing(dev.ellipog.armature.client.ui.kit.Easing.LINEAR);

    /**
     * Greys only: no hue anywhere, so the state colours cannot be told apart by colour at all.
     *
     * <p>Included as a <b>test</b> as much as a theme. Every state here is a distinct <i>brightness</i>
     * and nothing else, so a screen that leans on hue to distinguish available from complete is a screen
     * that is unreadable in this theme — and that is worth being able to check in one click rather than
     * by imagining it. The four values are chosen to be far apart: 210, 150, 255, 90.
     *
     * <h2>The four washes have to be stated, and that is a finding rather than a completeness exercise</h2>
     *
     * <p>This theme used to leave several of them out, on the reasonable-sounding grounds that it is a
     * palette of greys and a wash is only a wash. What came through was {@link #MODERN}'s — whose dim,
     * recessed and label backdrop are all a very slightly blue near-black, and whose completed-node wash
     * is a distinctly green one. So the "no hue anywhere" theme had <b>five faintly tinted surfaces in
     * it, and nothing on screen would ever have said so.</b>
     *
     * <p>{@code ThemesTest.monochromeIsGrey} found it by checking every token rather than the ones a
     * person would think to check — and it found {@code dim} first, then {@code recessed} on the next
     * run, because a fix that addresses the reported token and not the class of token leaves the same
     * bug sitting one slot along. That is the argument for the token registry itself, made by the
     * registry catching something: a property asserted over all forty-three values cannot be forgotten for
     * the thirty-fifth, or for the thirty-sixth.
     */
    public static final Theme MONOCHROME = ThemePatch.of("monochrome", set("monochrome", 
            "dim", "#B8000000",
            "canvas", "#FF101010",
            "recessed", "#FF141414",
            "panel", "#FF1C1C1C",
            "raised", "#FF262626",
            "panelEdge", "#FF464646",
            "title", "#FFFFFFFF",
            "body", "#FFC8C8C8",
            "faint", "#FF7A7A7A",
            "heading", "#FF9A9A9A",
            "available", "#FFD2D2D2",
            "inProgress", "#FF969696",
            "complete", "#FFFFFFFF",
            "blocked", "#FF5A5A5A",
            "nodeFill", "#FF242424",
            // The two washes the guard caught: both used to be inherited from modern, which is how the
            // "no hue anywhere" theme came to carry two of another palette's values. Each is now a
            // translucent form of one of this theme's own steps -- the locked wash of its darkest
            // surface, the hover wash of its lightest edge.
            "nodeDim", "#B0101010",
            "nodeDoneWash", "#33FFFFFF",
            "nodeEdgeBlocked", "#FF3E3E3E",
            "nodeEdgeAvailable", "#FFD2D2D2",
            "nodeEdgeInProgress", "#FF969696",
            "nodeEdgeComplete", "#FFFFFFFF",
            "line", "#FF4A4A4A",
            "lineDone", "#FF9A9A9A",
            "selectedRing", "#FFFFFFFF",
            "hoverRing", "#90FFFFFF",
            "canvasPattern", "#337A7A7A",
            "rowHover", "#22E0E0E0",
            "scrollTrack", "#FF181818",
            "scrollThumb", "#FF525252",
            "labelBackdrop", "#F0000000",
            "tooltipFill", "#F0000000",
            "tooltipEdge", "#FF666666",
            "tooltipText", "#FFC8C8C8",
            "fill", "#FF363636",
            "hover", "#FF424242",
            "held", "#FF4E4E4E",
            "selected", "#FF6E6E6E",
            "disabled", "#FF232323",
            "accent", "#FF5A5A5A",
            "edge", "#FF484848",
            "edgeAccent", "#FF8A8A8A",
            "edgeSelected", "#FFE0E0E0",
            "edgeBright", "#FF5E5E5E"))
            .applyTo(MODERN)
            .withRadius(2);

    /**
     * A <b>light</b> theme: dark ink on pale surfaces.
     *
     * <h2>Why this one is worth shipping even nobody plays on it</h2>
     *
     * <p>Because it is the only theme that inverts the relationship every other one assumes — that text
     * is lighter than the surface behind it. Anywhere the toolkit hardcoded "brighter means more
     * important", or drew a dark shadow, or used white for a highlight, this theme makes it visible.
     * A palette of dark themes cannot find those, because in all of them the assumption happens to be
     * true. That is the same argument as {@link #MONOCHROME}: a theme is also a test.
     *
     * <p>{@code dim} is a pale haze rather than a dark one, which is the one place this theme has to
     * make a real decision. The dim exists to push the world back behind a panel; a light theme can do
     * that with brightness as well as with darkness, and a light theme that dimmed to black would be a
     * light panel on a black void, which reads as a bug.
     */
    public static final Theme PAPER = ThemePatch.of("paper", set("paper", 
            "dim", "#B8E4DCCB",
            "canvas", "#FFEDE7DA",
            "recessed", "#FFE2DACA",
            "panel", "#FFF6F1E6",
            "raised", "#FFFFFCF4",
            "panelEdge", "#FFB4A88E",
            "title", "#FF1A1611",
            "body", "#FF2E2820",
            "faint", "#FF6E6454",
            "heading", "#FF4A4136",
            "available", "#FF1F5C9E",
            "inProgress", "#FF8A5E00",
            "complete", "#FF2A6B32",
            "blocked", "#FF8A8272",
            "nodeFill", "#FFFFFDF7",
            "nodeDim", "#96EFEADC",
            "nodeDoneWash", "#332A6B32",
            "nodeEdgeBlocked", "#FFB4A88E",
            "nodeEdgeAvailable", "#FF1F5C9E",
            "nodeEdgeInProgress", "#FF8A5E00",
            "nodeEdgeComplete", "#FF2A6B32",
            "line", "#FFA2967C",
            "lineDone", "#FF3E7A46",
            "selectedRing", "#FF1A1611",
            "hoverRing", "#801A1611",
            "canvasPattern", "#336E6454",
            "rowHover", "#221A1611",
            "labelBackdrop", "#F0F6F1E6",
            "scrollTrack", "#FFE2DACA",
            "scrollThumb", "#FF9A8E76",
            "tooltipFill", "#F0FFFCF4",
            "tooltipEdge", "#FF8A7E64",
            "tooltipText", "#FF2E2820",
            "fill", "#FFE6DFCE",
            "hover", "#FFDCD3BE",
            "held", "#FFD0C6AE",
            "selected", "#FF2E6BA8",
            "disabled", "#FFEFEAE0",
            "accent", "#FF1F5C9E",
            "edge", "#FFA2967C",
            "edgeAccent", "#FF1F5C9E",
            "edgeSelected", "#FF124068",
            "edgeBright", "#FF6E6454"))
            .applyTo(MODERN)
            .withRadius(3)
            // A one-pixel rule grid: the printed sheet the palette is, faint enough to stay behind
            // the text -- a thicker rule reads as a table rather than as paper.
            .withBackground(new CanvasBackground(CanvasBackground.Kind.GRID_LINES,
                    CanvasBackground.Space.GRAPH, 24,
                    new CanvasBackground.Tuning(1, 5, false), CanvasBackground.Image.NONE));

    // ------------------------------------------------------------------
    // Minecraft materials
    // ------------------------------------------------------------------

    /** Obsidian and crying obsidian: near-black purple, lit from the inside by a violet glow. */
    public static final Theme OBSIDIAN = ThemePatch.of("obsidian", set("obsidian", 
            "dim", "#C0080610",
            "canvas", "#FF0B0914",
            "recessed", "#FF141020",
            "panel", "#FF1B1630",
            "raised", "#FF261F42",
            "panelEdge", "#FF443A6E",
            "title", "#FFEDE6FF",
            "body", "#FFC4B8E4",
            "faint", "#FF8477A8",
            "heading", "#FFA294C8",
            "available", "#FF9E7BFF",
            "inProgress", "#FFB98CFF",
            "complete", "#FFC8A0FF",
            "blocked", "#FF6A5E8A",
            "nodeFill", "#FF241C48",
            "nodeDim", "#A8060410",
            "nodeDoneWash", "#33C8A0FF",
            "nodeEdgeBlocked", "#FF3E3466",
            "nodeEdgeAvailable", "#FF9E7BFF",
            "nodeEdgeInProgress", "#FFB98CFF",
            "nodeEdgeComplete", "#FFC8A0FF",
            "line", "#FF4A3E80",
            "lineDone", "#FF8A6ACC",
            "selectedRing", "#FFE6D8FF",
            "hoverRing", "#80E6D8FF",
            "canvasPattern", "#338477A8",
            "rowHover", "#22D8C8FF",
            "labelBackdrop", "#F00B0914",
            "scrollTrack", "#FF171232",
            "scrollThumb", "#FF5346A0",
            "tooltipFill", "#F00B0914",
            "tooltipEdge", "#FF5E4FA8",
            "tooltipText", "#FFC4B8E4",
            "fill", "#FF2C2456",
            "hover", "#FF372D68",
            "held", "#FF42377A",
            "selected", "#FF5B46A8",
            "disabled", "#FF1F1A38",
            "accent", "#FF4A2E9E",
            "edge", "#FF4A3E80",
            "edgeAccent", "#FF9E7BFF",
            "edgeSelected", "#FFC8A0FF",
            "edgeBright", "#FF6A58B4"))
            .applyTo(MODERN)
            .withRadius(2);

    /** Amethyst: a pale violet crystal, so the light source is the accent rather than the surface. */
    public static final Theme AMETHYST = ThemePatch.of("amethyst", set("amethyst", 
            "dim", "#B8160F1E",
            "canvas", "#FF160F1E",
            "recessed", "#FF1E1628",
            "panel", "#FF211A2B",
            "raised", "#FF2C2440",
            "panelEdge", "#FF453A5C",
            "title", "#F2F2E9FF",
            "body", "#FFCFC2E4",
            "faint", "#FF8C7CA6",
            "heading", "#FFA896C4",
            "available", "#FFC79BF0",
            "inProgress", "#FFE8C868",
            "complete", "#FF8FE0B0",
            "blocked", "#FF6B5F7E",
            "nodeFill", "#FF2A2140",
            "nodeDim", "#9C0F0918",
            "nodeDoneWash", "#338FE0B0",
            "nodeEdgeBlocked", "#FF443A5C",
            "nodeEdgeAvailable", "#FFC79BF0",
            "nodeEdgeInProgress", "#FFE8C868",
            "nodeEdgeComplete", "#FF8FE0B0",
            "line", "#FF4E4270",
            "lineDone", "#FF7A5FA8",
            "selectedRing", "#FFE6D4FF",
            "hoverRing", "#80E6D4FF",
            "canvasPattern", "#338C7CA6",
            "rowHover", "#22D9C2FF",
            "labelBackdrop", "#F0160F1E",
            "scrollTrack", "#FF241C33",
            "scrollThumb", "#FF54466E",
            "tooltipFill", "#F0160F1E",
            "tooltipEdge", "#FF6E5C8E",
            "tooltipText", "#FFCFC2E4",
            "fill", "#FF372C4C",
            "hover", "#FF423555",
            "held", "#FF4E4066",
            "selected", "#FF6C4E9E",
            "disabled", "#FF241E30",
            "accent", "#FF5B3A8C",
            "edge", "#FF493C60",
            "edgeAccent", "#FF8A62C4",
            "edgeSelected", "#FFC79BF0",
            "edgeBright", "#FF655490"))
            .applyTo(MODERN)
            .withRadius(6);

    /** Copper: warm metal going green, which is the one palette where a patina is the point. */
    public static final Theme COPPER = ThemePatch.of("copper", set("copper", 
            "dim", "#B81A0F08",
            "canvas", "#FF17100A",
            "recessed", "#FF23180F",
            "panel", "#FF2C1E13",
            "raised", "#FF3C2A1A",
            "panelEdge", "#FF6B4A2E",
            "title", "#FFF6E4D0",
            "body", "#FFDCBFA0",
            "faint", "#FF9E7B58",
            "heading", "#FFBE9870",
            "available", "#FF6FD4BE",
            "inProgress", "#FFE8B060",
            "complete", "#FF7FD08A",
            "blocked", "#FF7A6048",
            "nodeFill", "#FF34251A",
            "nodeDim", "#9C120B06",
            "nodeDoneWash", "#337FD08A",
            "nodeEdgeBlocked", "#FF54402C",
            "nodeEdgeAvailable", "#FF6FD4BE",
            "nodeEdgeInProgress", "#FFE8B060",
            "nodeEdgeComplete", "#FF7FD08A",
            "line", "#FF6A4E33",
            "lineDone", "#FF4E9E86",
            "selectedRing", "#FFFFE0B8",
            "hoverRing", "#80FFE0B8",
            "canvasPattern", "#339E7B58",
            "rowHover", "#22FFE0B8",
            "labelBackdrop", "#F017100A",
            "scrollTrack", "#FF2A1D12",
            "scrollThumb", "#FF6E4E30",
            "tooltipFill", "#F017100A",
            "tooltipEdge", "#FF8A6440",
            "tooltipText", "#FFDCBFA0",
            "fill", "#FF43301F",
            "hover", "#FF4F3A26",
            "held", "#FF5C442D",
            "selected", "#FF8A6538",
            "disabled", "#FF2C2016",
            "accent", "#FF7A5330",
            "edge", "#FF6A4E33",
            "edgeAccent", "#FFB88A50",
            "edgeSelected", "#FFE0B478",
            "edgeBright", "#FF8A6A48"))
            .applyTo(MODERN)
            .withRadius(4);

    /** Redstone: dark slate and a saturated red glow, so progression reads as current through a wire. */
    public static final Theme REDSTONE = ThemePatch.of("redstone", set("redstone", 
            "dim", "#B81A0606",
            "canvas", "#FF180808",
            "recessed", "#FF200C0C",
            "panel", "#FF281010",
            "raised", "#FF371818",
            "panelEdge", "#FF5E2A2A",
            "title", "#FFFFE0DC",
            "body", "#FFE0B4AE",
            "faint", "#FF9E7270",
            "heading", "#FFC08E88",
            "available", "#FFFF7A6E",
            "inProgress", "#FFFFB454",
            "complete", "#FF9AE07A",
            "blocked", "#FF7A5856",
            "nodeFill", "#FF331414",
            "nodeDim", "#A00E0404",
            "nodeDoneWash", "#339AE07A",
            "nodeEdgeBlocked", "#FF542424",
            "nodeEdgeAvailable", "#FFFF7A6E",
            "nodeEdgeInProgress", "#FFFFB454",
            "nodeEdgeComplete", "#FF9AE07A",
            "line", "#FF6E2E2E",
            "lineDone", "#FFCE5A4A",
            "selectedRing", "#FFFFC8BE",
            "hoverRing", "#80FFC8BE",
            "canvasPattern", "#339E7270",
            "rowHover", "#22FFC8BE",
            "labelBackdrop", "#F0180808",
            "scrollTrack", "#FF261010",
            "scrollThumb", "#FF6E2E2E",
            "tooltipFill", "#F0180808",
            "tooltipEdge", "#FF8E3A3A",
            "tooltipText", "#FFE0B4AE",
            "fill", "#FF3E1A1A",
            "hover", "#FF4A2020",
            "held", "#FF582828",
            "selected", "#FF9E3028",
            "disabled", "#FF2A1414",
            "accent", "#FF8E241C",
            "edge", "#FF6E2E2E",
            "edgeAccent", "#FFD05040",
            "edgeSelected", "#FFFF9A88",
            "edgeBright", "#FF8E4A46"))
            .applyTo(MODERN)
            .withRadius(4);

    // ------------------------------------------------------------------
    // Dimensions
    // ------------------------------------------------------------------

    /** The Nether: blood red, basalt grey and lava orange, with the surfaces as hot as the accents. */
    public static final Theme NETHER = ThemePatch.of("nether", set("nether", 
            "dim", "#C01C0404",
            "canvas", "#FF1C0705",
            "recessed", "#FF2A0C08",
            "panel", "#FF341210",
            "raised", "#FF481C16",
            "panelEdge", "#FF7A3626",
            "title", "#FFFFDCC8",
            "body", "#FFE0AE94",
            "faint", "#FFA87058",
            "heading", "#FFC88C6C",
            "available", "#FFFFA040",
            "inProgress", "#FFFFD060",
            "complete", "#FFB0D060",
            "blocked", "#FF7E5A4C",
            "nodeFill", "#FF3E1814",
            "nodeDim", "#A0100302",
            "nodeDoneWash", "#33B0D060",
            "nodeEdgeBlocked", "#FF5E2A1E",
            "nodeEdgeAvailable", "#FFFFA040",
            "nodeEdgeInProgress", "#FFFFD060",
            "nodeEdgeComplete", "#FFB0D060",
            "line", "#FF7E3826",
            "lineDone", "#FFD06A30",
            "selectedRing", "#FFFFE0B0",
            "hoverRing", "#80FFE0B0",
            "canvasPattern", "#33A87058",
            "rowHover", "#22FFD0A0",
            "labelBackdrop", "#F01C0705",
            "scrollTrack", "#FF331210",
            "scrollThumb", "#FF7E3826",
            "tooltipFill", "#F01C0705",
            "tooltipEdge", "#FF9E4428",
            "tooltipText", "#FFE0AE94",
            "fill", "#FF4A1E18",
            "hover", "#FF58251C",
            "held", "#FF682E22",
            "selected", "#FFA84420",
            "disabled", "#FF301412",
            "accent", "#FF9E3A18",
            "edge", "#FF7E3826",
            "edgeAccent", "#FFD07030",
            "edgeSelected", "#FFFFA860",
            "edgeBright", "#FF9E5438"))
            .applyTo(MODERN)
            .withRadius(4);

    /** The End: void purple and a pale chorus green, with surfaces darker than anything else here. */
    public static final Theme END = ThemePatch.of("end", set("end", 
            "dim", "#C00A0610",
            "canvas", "#FF0A0610",
            "recessed", "#FF120C1C",
            "panel", "#FF181128",
            "raised", "#FF221838",
            "panelEdge", "#FF3E2E60",
            "title", "#FFF0E8FF",
            "body", "#FFC8BCE0",
            "faint", "#FF8A7EA8",
            "heading", "#FFAA9CC8",
            "available", "#FFB89CFF",
            "inProgress", "#FFE0D070",
            "complete", "#FFB0E0B8",
            "blocked", "#FF645C7E",
            "nodeFill", "#FF201640",
            "nodeDim", "#A6040208",
            "nodeDoneWash", "#33B0E0B8",
            "nodeEdgeBlocked", "#FF382C58",
            "nodeEdgeAvailable", "#FFB89CFF",
            "nodeEdgeInProgress", "#FFE0D070",
            "nodeEdgeComplete", "#FFB0E0B8",
            "line", "#FF443474",
            "lineDone", "#FF8A76C8",
            "selectedRing", "#FFE8DCFF",
            "hoverRing", "#80E8DCFF",
            "canvasPattern", "#338A7EA8",
            "rowHover", "#22E0D4FF",
            "labelBackdrop", "#F00A0610",
            "scrollTrack", "#FF171030",
            "scrollThumb", "#FF4A3A7E",
            "tooltipFill", "#F00A0610",
            "tooltipEdge", "#FF5C48A0",
            "tooltipText", "#FFC8BCE0",
            "fill", "#FF261A4C",
            "hover", "#FF302260",
            "held", "#FF3A2A74",
            "selected", "#FF5A44A8",
            "disabled", "#FF1B1434",
            "accent", "#FF442E96",
            "edge", "#FF443474",
            "edgeAccent", "#FF9A7CFF",
            "edgeSelected", "#FFC4AAFF",
            "edgeBright", "#FF6450B0"))
            .applyTo(MODERN)
            .withRadius(8);

    /** The deep dark: almost no light, and the accent is the only thing that glows. Sculk cyan. */
    public static final Theme DEEP_DARK = ThemePatch.of("deep_dark", set("deep_dark", 
            "dim", "#D8040608",
            "canvas", "#FF06080A",
            "recessed", "#FF0C1013",
            "panel", "#FF111619",
            "raised", "#FF182025",
            "panelEdge", "#FF2A3A40",
            "title", "#FFDCF0F2",
            "body", "#FFA8C4C8",
            "faint", "#FF6E888E",
            "heading", "#FF8CA8AE",
            "available", "#FF4FE8E0",
            "inProgress", "#FF7FD8D0",
            "complete", "#FF9CF0C8",
            "blocked", "#FF52666C",
            "nodeFill", "#FF162024",
            "nodeDim", "#B4030405",
            "nodeDoneWash", "#339CF0C8",
            "nodeEdgeBlocked", "#FF27383E",
            "nodeEdgeAvailable", "#FF4FE8E0",
            "nodeEdgeInProgress", "#FF7FD8D0",
            "nodeEdgeComplete", "#FF9CF0C8",
            "line", "#FF2E464C",
            "lineDone", "#FF3E9E98",
            "selectedRing", "#FFC8FFF8",
            "hoverRing", "#80C8FFF8",
            "canvasPattern", "#336E888E",
            "rowHover", "#22C8FFF8",
            "labelBackdrop", "#F006080A",
            "scrollTrack", "#FF101619",
            "scrollThumb", "#FF2E464C",
            "tooltipFill", "#F006080A",
            "tooltipEdge", "#FF3A5A60",
            "tooltipText", "#FFA8C4C8",
            "fill", "#FF1A2429",
            "hover", "#FF222E34",
            "held", "#FF2A3A40",
            "selected", "#FF2A6E70",
            "disabled", "#FF131A1D",
            "accent", "#FF1E5E60",
            "edge", "#FF2E464C",
            "edgeAccent", "#FF4FE8E0",
            "edgeSelected", "#FF9CF0E8",
            "edgeBright", "#FF40686E"))
            .applyTo(MODERN)
            .withRadius(2);

    // ------------------------------------------------------------------
    // Applications rather than places
    // ------------------------------------------------------------------

    /** Terminal: monospace green on black, with the surfaces doing nothing but holding the text up. */
    public static final Theme TERMINAL = ThemePatch.of("terminal", set("terminal", 
            "dim", "#E0000000",
            "canvas", "#FF000000",
            "recessed", "#FF040A04",
            "panel", "#FF060D06",
            "raised", "#FF0C160C",
            "panelEdge", "#FF1E3A1E",
            "title", "#FFB8FFB8",
            "body", "#FF7ADB7A",
            "faint", "#FF4A8A4A",
            "heading", "#FF62B062",
            "available", "#FF7ADB7A",
            "inProgress", "#FFE0E070",
            "complete", "#FFB8FFB8",
            "blocked", "#FF446644",
            "nodeFill", "#FF0A160A",
            "nodeDim", "#B0000000",
            "nodeDoneWash", "#33B8FFB8",
            "nodeEdgeBlocked", "#FF1E3A1E",
            "nodeEdgeAvailable", "#FF7ADB7A",
            "nodeEdgeInProgress", "#FFE0E070",
            "nodeEdgeComplete", "#FFB8FFB8",
            "line", "#FF1E3A1E",
            "lineDone", "#FF62B062",
            "selectedRing", "#FFB8FFB8",
            "hoverRing", "#80B8FFB8",
            "canvasPattern", "#334A8A4A",
            "rowHover", "#22B8FFB8",
            "labelBackdrop", "#F0000000",
            "scrollTrack", "#FF061006",
            "scrollThumb", "#FF1E3A1E",
            "tooltipFill", "#F0000000",
            "tooltipEdge", "#FF2A5A2A",
            "tooltipText", "#FF7ADB7A",
            "fill", "#FF0E1C0E",
            "hover", "#FF162816",
            "held", "#FF1E341E",
            "selected", "#FF1E5A1E",
            "disabled", "#FF081008",
            "accent", "#FF164016",
            "edge", "#FF1E3A1E",
            "edgeAccent", "#FF62B062",
            "edgeSelected", "#FFB8FFB8",
            "edgeBright", "#FF2A5A2A"))
            .applyTo(MODERN)
            .withRadius(0)
            .withMotion(60L)
            .withEasing(dev.ellipog.armature.client.ui.kit.Easing.LINEAR)
            // Phosphor at two pixels: a CRT's shadow mask, which is what the palette imitates. At
            // one pixel it is dust, and the screen stops looking like a terminal.
            .withBackground(new CanvasBackground(CanvasBackground.Kind.DOTS,
                    CanvasBackground.Space.GRAPH, 22,
                    new CanvasBackground.Tuning(2, 5, false), CanvasBackground.Image.NONE));

    /** Neon: near-black with a hot magenta and cyan, for packs that want nothing Minecraft about it. */
    public static final Theme NEON = ThemePatch.of("neon", set("neon", 
            "dim", "#C0080410",
            "canvas", "#FF08040F",
            "recessed", "#FF110A1C",
            "panel", "#FF150D22",
            "raised", "#FF1F1430",
            "panelEdge", "#FF4A2A70",
            "title", "#FFFFE8FF",
            "body", "#FFD0B8E8",
            "faint", "#FF8A72A8",
            "heading", "#FFB08CD8",
            "available", "#FF40E0FF",
            "inProgress", "#FFFF54C8",
            "complete", "#FF54FFA8",
            "blocked", "#FF6A5A84",
            "nodeFill", "#FF1E1234",
            "nodeDim", "#A8030208",
            "nodeDoneWash", "#3354FFA8",
            "nodeEdgeBlocked", "#FF3E2A5E",
            "nodeEdgeAvailable", "#FF40E0FF",
            "nodeEdgeInProgress", "#FFFF54C8",
            "nodeEdgeComplete", "#FF54FFA8",
            "line", "#FF5A2E86",
            "lineDone", "#FF40E0FF",
            "selectedRing", "#FFFF54C8",
            "hoverRing", "#90FF54C8",
            "canvasPattern", "#338A72A8",
            "rowHover", "#28FF54C8",
            "labelBackdrop", "#F008040F",
            "scrollTrack", "#FF140B26",
            "scrollThumb", "#FF5A2E86",
            "tooltipFill", "#F008040F",
            "tooltipEdge", "#FF7A3AB0",
            "tooltipText", "#FFD0B8E8",
            "fill", "#FF241640",
            "hover", "#FF2E1C52",
            "held", "#FF3A2468",
            "selected", "#FF7A2A9E",
            "disabled", "#FF1A1028",
            "accent", "#FF9E1A6E",
            "edge", "#FF5A2E86",
            "edgeAccent", "#FFFF54C8",
            "edgeSelected", "#FF40E0FF",
            "edgeBright", "#FF8A44C8"))
            .applyTo(MODERN)
            .withRadius(6);

    /**
     * Every built-in, in the order a picker should offer them.
     *
     * <h2>The order is a design statement, not an accident of declaration</h2>
     *
     * <p>It goes: the default first, because it is the one a client is already using and a picker that
     * does not list the current setting is a picker nobody trusts; then the three originals, so a cycle
     * starts where the project started and nobody's muscle memory breaks; then the three utility themes,
     * because they are chosen to solve a problem and someone looking for one should not have to scroll;
     * then materials, dimensions, and finally the two that are applications rather than places. <b>A list
     * of sixteen in random order is a list nobody reads past the third entry.</b>
     *
     * <p><b>A theme has to be in this list to be reachable by clicking.</b> That is not a description of
     * this constant so much as the constraint on it: {@code ThemesTest} asserts that the names here are
     * distinct and that {@link #everything} contains every one of them, and the theme the client starts
     * on being reachable is exactly the case that would go wrong quietly — a default nothing can select
     * is a default a player is stuck with.
     */
    public static final List<Theme> ALL = List.of(
            DEFAULT,
            MODERN,
            TOME,
            VANILLA_PLUS,
            HIGH_CONTRAST,
            MONOCHROME,
            PAPER,
            OBSIDIAN,
            AMETHYST,
            COPPER,
            REDSTONE,
            NETHER,
            END,
            DEEP_DARK,
            TERMINAL,
            NEON);

    /**
     * A built-in theme by name, or null when the name matches nothing.
     *
     * <p>Null rather than a fallback, which is the opposite of {@code Shapes.byName} — and the difference
     * is who is asking. A shape name arrives from a payload, where there is nobody to tell and drawing
     * the default is obviously right. A theme name arrives from a <b>person's setting</b> — a control
     * someone clicked, or a group an author typed into a data file — and there the honest answer is
     * "no such theme" so the caller can say so and list the ones that exist. A fallback would report
     * success for a typo, and the player would see the appearance they already had while the log said
     * nothing at all.
     *
     * <p><b>Built-ins only.</b> A theme loaded from a file is not here, because the file has to be read
     * first and this method is called from class initialisation of things that may not have read one yet.
     * {@link #any} is the lookup that includes them, and is what a setting or a chapter should use.
     */
    public static Theme byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String wanted = name.trim();
        for (Theme theme : ALL) {
            if (theme.name().equalsIgnoreCase(wanted)) {
                return theme;
            }
        }
        return null;
    }

    /**
     * A theme by name, built-in or loaded from a file, or null.
     *
     * <p>The lookup everything outside this class should call. {@code ThemeFiles} owns the catalogue of
     * file-loaded themes, and consulting it here rather than at each call site means "a name resolves the
     * same way everywhere" — a setting, a group, an entry and the editor all get the same answer, which
     * is the property that stops a theme being selectable from one place and not another.
     */
    public static Theme any(String name) {
        Theme builtIn = byName(name);
        return builtIn != null ? builtIn : ThemeFiles.byName(name);
    }

    /** The built-in names, for a message: {@code modern, tome, vanilla_plus, ...}. */
    public static String names() {
        return String.join(", ", ALL.stream().map(Theme::name).toList());
    }

    /** Every built-in, plus anything loaded from a file. What a picker shows. */
    public static List<Theme> everything() {
        List<Theme> all = new ArrayList<>(ALL);
        all.addAll(ThemeFiles.all());
        return List.copyOf(all);
    }

    /** The name a theme would take if a file or a patch derived from it wanted a distinct one. */
    public static String derivedName(String base, String suffix) {
        String candidate = base + "_" + suffix;
        // Collisions are possible in principle and trivial to make impossible: a derived theme whose name
        // matches something already registered would shadow it, and a picker listing two entries with one
        // name is a control that appears not to work.
        int n = 2;
        while (any(candidate) != null || ThemeFiles.byName(candidate) != null) {
            candidate = base + "_" + suffix + n++;
        }
        return candidate.toLowerCase(Locale.ROOT);
    }
}

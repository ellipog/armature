package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.client.ui.kit.Easing;

/**
 * A full appearance: every colour, every radius, every measurement the toolkit draws with.
 *
 * <h2>Why this is data rather than constants</h2>
 *
 * <p>Because a reskin should be a resource pack, not a code change. {@code ArmatureTheme} was thirty
 * {@code static final int}s, and that is the right shape for <i>one</i> appearance — but the moment a
 * second is wanted, every one of those becomes something a user has to fork the mod to change, and
 * every consumer's screen has a hard dependency on this particular blue. That is what this replaces,
 * and the change is not cosmetic: it is the difference between a toolkit other projects can adopt and
 * one they can only imitate.
 *
 * <h2>A record, not a map of string keys</h2>
 *
 * <p>A map is more flexible and gets every mistake at runtime: a mistyped key is a missing colour, a
 * renamed field is a silently defaulting one, and nothing can tell you that {@code "panel.edge"} was
 * meant to be {@code "panel.edgeColor"}. A record has fields the compiler knows, so a theme is checked
 * when it is written and a consumer reads {@code theme.panel()} rather than {@code theme.get("panel")}.
 *
 * <p><b>But a record cannot be addressed by name, and something has to be.</b> A theme file, a chapter
 * override and an editor all refer to "one colour" as a string, and none of them can hold a field
 * reference. {@link ThemeToken} is the answer: it names every slot in this record, in order, and the two
 * are kept in step by a test that reads this record reflectively. So the flexibility of a map is
 * available for <i>changing</i> a theme while the safety of a record is what a caller <i>reads</i>.
 *
 * <h2>{@link #from} is the only place that knows the field order</h2>
 *
 * <p>Every derived theme — a patch applied, a radius changed, a name swapped — is built by
 * {@code from(name, allColours(), radius, motion, easing)}. That is deliberate, and it fixed a real
 * bug rather than tidying one: {@code withMotion} and {@code withRadius} each restated all thirty-three top-level
 * values positionally, so adding a colour meant editing three lists, and forgetting one produced a
 * theme whose colours were silently transposed. One restatement is one place to be wrong.
 *
 * <h2>Flat for the surfaces, nested for the controls, and the split is deliberate</h2>
 *
 * <p>The surface and text colours are top-level components because a caller reads them one at a time
 * and {@code theme.panel()} is the whole of what it wants to know.
 *
 * <p>The ten control colours are <b>one component</b>, a nested {@link Controls} record, for the
 * opposite reason: nothing outside {@code ArmatureControlStyle} should be picking a button's fill,
 * because the precedence rules that decide which of the ten applies live there and nowhere else. So
 * they travel as a set — a theme supplies the palette and the style class supplies the rules, and
 * neither can reach into the other. Ten top-level fields would invite a caller to write
 * {@code if (hovered) theme.controlHover() else ...}, which is that decision made a second time.
 *
 * <h2>The prebuilt themes live in {@link Themes}, not here</h2>
 *
 * <p>This class is the <i>shape</i>: the components, the array, the copy operations. The catalogue is
 * sixteen themes and a thousand lines of hex, and a record whose own file is mostly examples is one
 * nobody edits with any confidence. {@code Themes} is also where a derived theme is expressed as a
 * {@link ThemePatch} over a base, which is the same mechanism a data file uses — so the built-in set
 * is itself a demonstration that the override system is expressive enough.
 *
 * @param name           what to call this theme in a log line and a control. The key: what a file says,
 *                       what a chapter names, and what {@code Themes.byName} matches
 * @param dim            the wash behind a whole screen, over the world
 * @param canvas         the graph canvas's background, behind the nodes
 * @param recessed       a sunken area: a list's background, a progress bar's track
 * @param panel          a raised surface: the book's own background
 * @param raised         a surface above a panel: a header strip, a section bar
 * @param panelEdge      the border of a panel or a divider between two surfaces
 * @param title          a heading's own text, the brightest
 * @param body           ordinary text
 * @param faint          secondary text: a subtitle, a count, a hint
 * @param heading        a section label like TASKS or REWARDS
 * @param available      an entry a player may start — and the border of its node
 * @param inProgress     an entry they have started — and the border of its node
 * @param complete       an entry they have finished — and the border of its node
 * @param blocked        something they cannot act on yet
 * @param nodeFill       a graph node's interior, behind the icon
 * @param nodeDim        the wash over a locked node
 * @param nodeDoneWash   the wash over a completed one
 * @param nodeEdgeBlocked the outline of a node nothing leads to yet
 * @param nodeEdgeAvailable the outline of a node that can be started. Equals {@code available} in every
 *                       shipped theme, and is a separate field because an author who wants a node's
 *                       ring to differ from the word beside it should not have to choose — see the note
 *                       on progression borders below
 * @param nodeEdgeInProgress the outline of a started node
 * @param nodeEdgeComplete the outline of a finished node
 * @param line           a dependency line between two nodes
 * @param lineDone       one whose prerequisite is met
 * @param selectedRing   the ring around the node or row in use
 * @param hoverRing      the ring around the one under the pointer, at full hover
 * @param canvasPattern  the ink a canvas background pattern is drawn in; unused by a flat canvas
 * @param rowHover       the wash behind a hovered list row
 * @param labelBackdrop  the opaque backdrop behind text drawn over a canvas
 * @param scrollTrack    the groove a scrollbar's thumb runs in
 * @param scrollThumb    the scrollbar's thumb — the only part of the chrome with a colour of its own
 * @param tooltipFill    behind a floating panel that follows the pointer
 * @param tooltipEdge    the border of that panel
 * @param controls       the control palette, as a set — see the class note on why it is nested
 * @param cornerRadius   the corner radius for a rounded panel, in pixels. Zero is square and is not
 *                       turned into one
 * @param motion         how long transitions last, in milliseconds. Zero disables them.
 * @param easing         the curve transitions use
 * @param background     what is drawn behind the canvas content, over its colour — see
 *                       {@link CanvasBackground}
 */
public record Theme(
        String name,

        int dim,
        int canvas,
        int recessed,
        int panel,
        int raised,
        int panelEdge,

        int title,
        int body,
        int faint,
        int heading,

        int available,
        int inProgress,
        int complete,
        int blocked,

        int nodeFill,
        int nodeDim,
        int nodeDoneWash,
        int nodeEdgeBlocked,
        int nodeEdgeAvailable,
        int nodeEdgeInProgress,
        int nodeEdgeComplete,
        int line,
        int lineDone,
        int selectedRing,
        int hoverRing,
        int canvasPattern,

        int rowHover,
        int labelBackdrop,

        int scrollTrack,
        int scrollThumb,

        int tooltipFill,
        int tooltipEdge,
        int tooltipText,

        Controls controls,

        int cornerRadius,
        long motion,
        Easing easing,
        CanvasBackground background) {

    /**
     * The ten colours a control is drawn from, as a set rather than ten loose fields.
     *
     * <h2>Why these travel together</h2>
     *
     * <p>Because which of them applies is a decision with <b>precedence rules</b>, and those rules live
     * in {@code ArmatureControlStyle} — disabled beats selected beats held beats hovered, with accent
     * as a special case. A theme supplies the ten colours; the style class decides which one a
     * control is in and why. Ten top-level components on {@code Theme} would let a caller write the
     * precedence chain itself, which is the exact duplication the style class was extracted to
     * prevent — a preview that had its own copy of these rules and spent a round drawing the selected
     * chapter differently from the game.
     *
     * <p>So a caller reads {@code theme.controls().fill()} at the one place that has already decided
     * <i>which</i> fill, and never has to know the other nine exist.
     *
     * @param fill       an ordinary control, at rest
     * @param hover      the pointer is over it
     * @param held       the pointer is down on it
     * @param selected   the one the screen is currently about. Constant under hover, deliberately —
     *                   see {@code ArmatureControlStyle}'s class note for the argument
     * @param disabled   unusable, which beats every other state
     * @param accent     the screen's primary action, the loudest thing on it
     * @param edge       the border of an ordinary control
     * @param edgeAccent the border of the primary action
     * @param edgeSelected the border of the current one
     * @param edgeBright a brighter border for a floating surface: a tooltip, a hover caption
     */
    public record Controls(
            int fill,
            int hover,
            int held,
            int selected,
            int disabled,
            int accent,
            int edge,
            int edgeAccent,
            int edgeSelected,
            int edgeBright) {

        /** The same, with each colour shaded by {@code amount}. For a derived variant. */
        public Controls shaded(float amount) {
            return new Controls(
                    dev.ellipog.armature.client.ui.kit.Colour.shade(fill, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(hover, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(held, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(selected, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(disabled, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(accent, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(edge, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(edgeAccent, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(edgeSelected, amount),
                    dev.ellipog.armature.client.ui.kit.Colour.shade(edgeBright, amount));
        }

        /** These ten as an array, in {@link ThemeToken#CONTROLS} order. */
        public int[] allColours() {
            return new int[] {fill, hover, held, selected, disabled, accent,
                    edge, edgeAccent, edgeSelected, edgeBright};
        }

        /**
         * The ten, rebuilt from an array in {@link ThemeToken#CONTROLS} order.
         *
         * <p>The counterpart to {@link #allColours}, and the only place the order of these ten is
         * written down. Public because {@link Theme#from} is the caller and it lives in this file, and
         * because a test asserts both directions round-trip.
         */
        public static Controls from(int[] colours) {
            if (colours.length != 10) {
                throw new IllegalArgumentException("expected 10 control colours, got " + colours.length);
            }
            return new Controls(colours[0], colours[1], colours[2], colours[3], colours[4],
                    colours[5], colours[6], colours[7], colours[8], colours[9]);
        }

        /** This set, with one of its colours replaced by token id. Returns this unchanged if unknown. */
        public Controls with(String tokenId, int argb) {
            int index = ThemeToken.indexOf(tokenId);
            if (index < ThemeToken.CONTROL_START) {
                return this;
            }
            int[] colours = allColours();
            colours[index - ThemeToken.CONTROL_START] = argb;
            return from(colours);
        }
    }

    // ------------------------------------------------------------------
    // The array, and the one place the field order is written down
    // ------------------------------------------------------------------

    /**
     * Every colour in this theme, in {@link ThemeToken#ALL} order.
     *
     * <p>Exists so the checks that have to consider every colour can be written once — "every colour
     * carries an alpha channel", "a patch changed exactly the tokens it named" — rather than listing
     * thirty fields at a call site, where the next field added would be left out of the check silently.
     * That is the same shape of gap as a field nothing reads.
     *
     * <p>Order is load-bearing and asserted against the registry by test.
     */
    public int[] allColours() {
        int[] top = {
                dim, canvas, recessed, panel, raised, panelEdge,
                title, body, faint, heading,
                available, inProgress, complete, blocked,
                nodeFill, nodeDim, nodeDoneWash,
                nodeEdgeBlocked, nodeEdgeAvailable, nodeEdgeInProgress, nodeEdgeComplete,
                line, lineDone, selectedRing, hoverRing, canvasPattern,
                rowHover, labelBackdrop,
                scrollTrack, scrollThumb,
                tooltipFill, tooltipEdge, tooltipText,
        };
        if (top.length != ThemeToken.CONTROL_START) {
            // A compile-time constant check, in effect: this is the one list that can silently fall out
            // of step with the registry, and a themed colour landing in the wrong slot is a very
            // confusing bug. Failing at class initialisation makes it a startup crash naming the count,
            // which is the loudest possible way for it to be wrong.
            throw new IllegalStateException("Theme has " + top.length + " top-level colours but the token"
                    + " registry expects " + ThemeToken.CONTROL_START);
        }

        int[] controls = this.controls.allColours();
        int[] all = new int[top.length + controls.length];
        System.arraycopy(top, 0, all, 0, top.length);
        System.arraycopy(controls, 0, all, top.length, controls.length);
        return all;
    }

    /**
     * A theme from a colour array, plus the three values that are not colours.
     *
     * <p><b>This is the single construction point for a derived theme.</b> {@link #withName},
     * {@link #withRadius}, {@link #withMotion}, {@link #withEasing}, {@link #withColours} and
     * {@link ThemePatch} all route through here, which is what makes "one place knows the field order"
     * true rather than aspirational. The field list below is the only positional restatement of this
     * record anywhere outside its own declaration.
     *
     * @param colours every colour, in {@link ThemeToken#ALL} order. Validated by length, because the
     *     whole point of this method is that the order is not written down twice and a caller that got
     *     it wrong would otherwise produce a plausibly-coloured theme
     */
    public static Theme from(String name, int[] colours, int cornerRadius, long motion, Easing easing,
                             CanvasBackground background) {
        if (colours.length != ThemeToken.ALL.size()) {
            throw new IllegalArgumentException("a theme needs " + ThemeToken.ALL.size()
                    + " colours, got " + colours.length);
        }
        if (cornerRadius < 0) {
            throw new IllegalArgumentException("a corner radius cannot be negative: " + cornerRadius);
        }
        if (motion < 0) {
            throw new IllegalArgumentException("a motion duration cannot be negative: " + motion);
        }

        return new Theme(
                name,
                colours[0], colours[1], colours[2], colours[3], colours[4], colours[5],
                colours[6], colours[7], colours[8], colours[9],
                colours[10], colours[11], colours[12], colours[13],
                colours[14], colours[15], colours[16],
                colours[17], colours[18], colours[19], colours[20],
                colours[21], colours[22], colours[23], colours[24],
                colours[25], colours[26], colours[27],
                colours[28], colours[29],
                colours[30], colours[31], colours[32],
                Controls.from(java.util.Arrays.copyOfRange(colours, ThemeToken.CONTROL_START, colours.length)),
                cornerRadius,
                motion,
                easing,
                background);
    }

    // ------------------------------------------------------------------
    // By name, for a patch, a file or an editor
    // ------------------------------------------------------------------

    /**
     * One colour by token id.
     *
     * <p>Throws on an unknown id rather than returning a default, which is the opposite of
     * {@link Themes#byName} and deliberate. A name arriving from a file is a person's input and has a
     * caller that can report it; a token id reaching here has already been resolved by
     * {@link ThemeToken#byId} at the boundary, so an unknown one is a programming mistake in this
     * codebase — and the useful behaviour for that is a stack trace naming the id.
     */
    public int colour(String tokenId) {
        int index = ThemeToken.indexOf(tokenId);
        if (index < 0) {
            throw new IllegalArgumentException("no theme token called '" + tokenId + "'. There are: "
                    + ThemeToken.ids());
        }
        return allColours()[index];
    }

    /** Whether an id names a colour this theme has. */
    public boolean has(String tokenId) {
        return ThemeToken.exists(tokenId);
    }

    // ------------------------------------------------------------------
    // Copies
    // ------------------------------------------------------------------

    /**
     * A copy under a different name.
     *
     * <p>A copy rather than a setter, because a theme is shared: every screen draws from the one
     * instance, so mutating it would change the appearance of a screen that is halfway through a
     * frame. The same argument as {@code Slot.moved} returning a new slot.
     */
    public Theme withName(String newName) {
        return from(newName, allColours(), cornerRadius, motion, easing, background);
    }

    /**
     * A copy at a different motion duration.
     *
     * <p>Zero means instant and is not turned into "unset" — see {@code Motion}, which is where a
     * theme's zero and a player's own setting are told apart.
     */
    public Theme withMotion(long millis) {
        return from(name, allColours(), cornerRadius, millis, easing, background);
    }

    /** A copy at a different corner radius. Zero means square, and is not turned into one. */
    public Theme withRadius(int radius) {
        return from(name, allColours(), radius, motion, easing, background);
    }

    /** A copy on a different easing curve. */
    public Theme withEasing(Easing curve) {
        return from(name, allColours(), cornerRadius, motion, curve, background);
    }

    /**
     * A copy over a different canvas background.
     *
     * <p>The background is a whole component rather than a token because most of it is not a colour:
     * a pattern name, a space and a spacing travel together, and the one colour they ink with is the
     * {@code canvasPattern} token beside them.
     */
    public Theme withBackground(CanvasBackground newBackground) {
        return from(name, allColours(), cornerRadius, motion, easing, newBackground);
    }

    /** A copy with a whole new set of colours, in {@link ThemeToken#ALL} order. */
    public Theme withColours(int[] colours) {
        return from(name, colours, cornerRadius, motion, easing, background);
    }

    /** A copy with one colour replaced. Unknown ids leave the theme unchanged. */
    public Theme with(String tokenId, int argb) {
        int index = ThemeToken.indexOf(tokenId);
        if (index < 0) {
            return this;
        }
        int[] colours = allColours();
        colours[index] = argb;
        return withColours(colours);
    }

    // ------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------

    /**
     * This theme's name, fit to put on a control.
     *
     * <h2>Why this is derived rather than a field</h2>
     *
     * <p>Because a second name is a second thing to keep in step with the first, and this project has
     * already paid for that twice — two descriptions of one control, and a shape field that three
     * places knew about and none read. The <b>name</b> is the key: it is what a file says, what a
     * chapter names, and what {@link Themes#byName} matches. A label is a <i>rendering</i> of it, so it
     * is computed from it.
     *
     * <p>{@code vanilla_plus} becomes {@code Vanilla Plus} rather than {@code Vanilla_plus}, which is
     * the whole reason not to just print the name. Underscores are an identifier convention; a sidebar
     * control is not an identifier.
     */
    public String displayName() {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        // An empty name would render as an empty button, which reads as a missing control. Nothing
        // should produce one, so this is the floor rather than a case with a story behind it.
        return out.isEmpty() ? name : out.toString();
    }

    @Override
    public String toString() {
        return "Theme(" + name + ", radius " + cornerRadius + ", motion " + motion + "ms)";
    }
}

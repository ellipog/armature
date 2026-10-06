package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.CacheHits;
import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.armature.client.ui.kit.RoundedRect;
import dev.ellipog.armature.client.ui.shape.Outlines;
import dev.ellipog.armature.client.ui.shape.Plans;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shapes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The palette every drawing call reads, and the one place a theme is swapped.
 *
 * <h2>Every colour in the toolkit comes from here, which is why this class is static</h2>
 *
 * <p>A button does not know what a theme is. It asks for {@link #body}, and this answers with whatever
 * is in force — so a reskin reaches every control, panel, node and label without any of them being
 * changed, and a new control is themeable by construction rather than by remembering to plumb a colour
 * through its constructor.
 *
 * <p>That is a deliberate trade, and the cost is that two palettes cannot be live at once by accident:
 * there is one field. {@link #scope} is how two are live at once <i>on purpose</i>.
 *
 * <h2>Two levels: the chrome, and whatever is inside a region</h2>
 *
 * <p>There are two quite different things a theme can mean, and before this they were one:
 *
 * <ul>
 *   <li><b>The main theme</b> — {@link #current}. The player's or the pack's choice, and it styles the
 *       whole screen: the panel, the sidebar, the header, the title, every control, every tooltip.
 *       It is a setting, it persists, and it is the answer everywhere by default.</li>
 *   <li><b>A scoped theme</b> — {@link #scope}. Something a piece of <i>content</i> asks for, while that
 *       content is on screen: the node graph drawn in the colours of the chapter you are reading. It
 *       does not persist, it does not change a control, and it ends when the scope does.</li>
 * </ul>
 *
 * <p>These are not two features, they are two questions — "how do I want this program to look" and
 * "what does this chapter look like" — and conflating them was the mistake the previous round made and
 * then patched over. There, a chapter's theme and the player's theme were one field with a precedence
 * rule, which meant a click could be overruled and the code needed a flag to remember that the player
 * had meant it. With the two separated by <i>region</i> rather than by <i>priority</i>, no precedence
 * rule is needed at all: the sidebar is chrome and reads the main theme, the canvas is content and
 * reads the chapter's, and both are visible at once without either winning.
 *
 * <h2>Scopes nest, and the one that is open wins</h2>
 *
 * <p>A chapter sets the viewport's palette; a single entry inside it may set its own; the detail overlay
 * may set its own again. So this is a stack rather than a field, and {@link #current} is its top. That is
 * also what makes the feature composable rather than a special case: nothing in this class knows what a
 * group or an entry is, only that something asked for a palette for the duration of a block of drawing.
 *
 * <p>Use it with try-with-resources:
 *
 * <pre>{@code
 * try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(chapterTheme)) {
 *     drawTheGraph(renderer);          // in the chapter's colours
 * }
 * drawTheSidebar(renderer);            // back in the player's
 * }</pre>
 *
 * <p>A scope is not optional in that idiom, and the reason is a bug rather than taste: a pop that is
 * skipped on an early return leaves the rest of the frame — and every frame after it — drawing in a
 * palette nobody chose, with nothing on screen saying where it came from. The compiler places the close
 * on every exit path, so the two cannot come apart. Same argument as {@code GuiRenderer.clip}, which is
 * the same mechanism for the same reason.
 *
 * <h2>What a scope changes: the colours and the corners</h2>
 *
 * <p>{@link Theme} also carries a corner radius, a motion duration and an easing curve.
 *
 * <ul>
 *   <li><b>The radius travels with the scope.</b> {@code panel} and {@code fillSurface} read it from
 *       {@link #current()}, so a surface drawn inside a scope is rounded by the theme it is drawn in —
 *       which is what "this chapter's panels have their own corners" has to mean. The class note used to
 *       claim the opposite while the code already did this; the code was right.</li>
 *   <li><b>Motion is process-wide.</b> It is pushed into {@link Motion} once, when the main theme
 *       changes, and read by every tween in the toolkit. A region with its own animation timing would
 *       mean every tween asking which region it was in, and the honest reading of "this chapter animates
 *       differently" is a mystery to a player rather than a feature. {@code scope} pushes the theme
 *       record only; {@link Motion} is untouched by it.</li>
 * </ul>
 *
 * <p>{@code ThemePatch.tint} still cannot set radius or motion: a group or entry is a colouring, and the
 * patch's own {@code applyTo} is the form that carries the non-colour fields.
 *
 * <h2>Threading, stated rather than assumed</h2>
 *
 * <p>The scope stack is not synchronised and must not be: drawing happens on the client thread, and a
 * theme pushed from another thread would be a palette for a frame that is not being drawn. A mod that
 * wants to prepare a theme off-thread should build the {@link Theme} there and push it here.
 */
public final class ArmatureTheme {

    private ArmatureTheme() {
    }

    // ------------------------------------------------------------------
    // The theme in use
    // ------------------------------------------------------------------

    /** The main theme: what the chrome is drawn in, and the fallback for everything else. */
    private static Theme base = Themes.DEFAULT;

    /** Open scopes, innermost last. Empty is the normal case. */
    private static final Deque<Theme> scopes = new ArrayDeque<>();

    /**
     * The palette in force, which is the innermost open scope or the main theme.
     *
     * <h2>Process-wide, and why that is the right scope</h2>
     *
     * <p>A client has one appearance, the same way it has one window size. Threading a theme through
     * every constructor would be more "correct" in the abstract and would mean every screen, every
     * control and every helper carries a parameter that is the same object in all of them — which is
     * the shape of a setting, not of a dependency.
     *
     * <p>Where a second palette is genuinely wanted, it is wanted <i>for a region</i> rather than for a
     * call path, and {@link #scope} is that — a hundred and fifty call sites inside a node canvas do
     * not each need to be told which chapter they are drawing.
     */
    public static Theme current() {
        Theme scoped = scopes.peek();
        return scoped != null ? scoped : base;
    }

    /**
     * The main theme, ignoring any open scope.
     *
     * <p>For the handful of places that must stay chrome while content is drawn in its own colours — a
     * scrollbar over a themed list, a tooltip over a themed canvas. Rare, and worth having as a named
     * question rather than as a variable somebody captured before a scope opened.
     */
    public static Theme chrome() {
        return base;
    }

    /** The name of the palette in force, for a log line or a control's label. */
    public static String themeName() {
        return current().name();
    }

    /**
     * Sets the main theme: what the chrome is drawn in.
     *
     * <h2>Why this reaches {@link Motion}</h2>
     *
     * <p>Because motion <i>is</i> part of an appearance, not a separate setting — "how this UI moves"
     * belongs with "how this UI looks", and the plan's own R6 lists animation curves as theme data
     * alongside the palette. So this is where the two are joined: a theme says how long its transitions
     * last and what curve they use, and the toolkit's animation picks both up here rather than every
     * call site asking the theme for a duration.
     *
     * <p>That is what makes {@code vanilla_plus} — a theme with a motion of zero, because vanilla has no
     * UI animation — actually instant rather than merely described as such. One call, and every hover in
     * the toolkit snaps.
     *
     * <p><b>What this does not override:</b> a player who has turned motion off keeps it off. The theme
     * sets the <i>default</i> duration and curve; {@link Motion#setEnabled} remains the accessibility
     * switch that wins over any theme, because a reskin must not be able to re-enable animation for
     * someone who cannot comfortably use it.
     *
     * <p>A scoped theme does <b>not</b> reach Motion even when it carries a duration, and that is the
     * same rule from the other side — see the class note.
     *
     * <h2>This is the primitive, and it deliberately has no by-name form</h2>
     *
     * <p>There used to be a {@code selectByName} beside it, and it is gone. It was a convenience that
     * set the theme by name without touching {@link Appearance} — which means without persisting it, so
     * a caller that used it got an appearance that worked until the client restarted and no record of
     * why. Two entry points to one setting, one of which is a trap, is the duplication this codebase
     * keeps removing.
     */
    public static void setCurrent(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        base = theme;

        Motion.setDefaultEasing(theme.easing());
        Motion.setDefaultDuration(theme.motion());
    }

    /**
     * Sets the main theme by name, built-in or file-loaded.
     *
     * <p>Fails rather than falling back, for the reason {@code Themes.byName} gives: a name comes from a
     * person's setting or a file, and a fallback would report success for a typo while showing the
     * appearance the player already had. A caller with a name it cannot check — {@code Appearance} — is
     * also the caller that can say so.
     */
    public static boolean selectByName(String name) {
        Theme found = Themes.any(name);
        if (found == null) {
            return false;
        }
        setCurrent(found);
        return true;
    }

    /** Back to the default appearance, and no scopes. For a client shutdown, or a test. */
    public static void resetCurrent() {
        scopes.clear();
        setCurrent(Themes.DEFAULT);
    }

    // ------------------------------------------------------------------
    // Scopes
    // ------------------------------------------------------------------

    /**
     * The handle for one open scope, closed by try-with-resources.
     *
     * <h2>Closing twice is a no-op, and that needs a field rather than a check</h2>
     *
     * <p>The compiler emits exactly one close per scope, but a caller may reasonably close one by hand
     * inside the block as well — and a second {@code pop()} would take a palette off the stack that this
     * scope did not put there. The symptom is a region drawn in the chrome's colours while everything
     * around it is in the chapter's, with no obvious cause at the call site.
     *
     * <p><b>This was documented here before it was true.</b> The first version carried a {@code pushed}
     * flag and no more, so a scope opened with nothing to apply closed harmlessly while a real scope
     * closed twice popped twice — the exact case the comment claimed to handle. The flag said whether
     * anything was pushed, not whether it was still there, and only the second question answers this
     * one. {@code ThemeTest.closingTwiceIsHarmless} is the test that found it.
     *
     * <h2>What is still not guarded, stated so it is not assumed</h2>
     *
     * <p>Closing scopes <b>out of order</b> — an inner one after its outer has already closed — is not
     * detected. A stack cannot tell you that a pop is the wrong pop; doing so would mean identity
     * tracking for a case the try-with-resources idiom cannot produce, and a wrong-order close is a bug
     * in the caller that no amount of bookkeeping here would fix. What this class guarantees is that the
     * <i>same</i> scope cannot be closed twice, which is the mistake a person actually makes.
     */
    public static final class Scope implements AutoCloseable {

        /**
         * Whether this scope is currently holding a palette on the stack.
         *
         * <p>Not final, and that is the whole mechanism: {@link #close} clears it, so the second call
         * finds nothing to do. False from the start for a scope opened with nothing to apply, which is
         * what stops a "no theme" scope from popping somebody else's palette.
         */
        private boolean open;

        private Scope(boolean pushed) {
            this.open = pushed;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                // `poll` rather than `pop`, because {@link #resetCurrent} clears the whole stack and a
                // scope closed after that would otherwise throw {@code NoSuchElementException} — from
                // inside a try-with-resources close, which is to say during a client shutdown rather
                // than during drawing. "Nothing left to pop" and "already popped" are the same fact,
                // and {@code open} is what tracks it.
                scopes.poll();
            }
        }
    }

    /**
     * Draws inside this palette until the scope closes.
     *
     * <p>The theme is pushed even when it is the same object as the one already in force, and that is
     * not waste: a nested scope that happens to match still has to be popped the same number of times it
     * was pushed, and skipping the push would make the stack depth depend on the colours.
     */
    public static Scope scope(Theme theme) {
        scopes.push(Objects.requireNonNull(theme, "theme"));
        return new Scope(true);
    }

    /**
     * Draws inside the palette a name refers to, or in the current one if the name resolves to nothing.
     *
     * <p>The null-safe form, for content: a chapter's theme may be absent, may name a built-in, or may
     * name a theme the client does not have. In the last two cases the right answer differs — apply it,
     * or draw unchanged and <i>say so</i> — and this method can only do the second silently, so a caller
     * that wants to warn must check {@code Themes.any(name)} itself.
     *
     * <p>Nothing is pushed when the name resolves to nothing, so {@link Scope#close} is pointless rather
     * than wrong: the stack depth is still what the block expects.
     */
    public static Scope scopeOf(String themeName) {
        Theme found = themeName == null ? null : Themes.any(themeName);
        return found == null ? new Scope(false) : scope(found);
    }

    /**
     * How many scopes are open.
     *
     * <p>For a test, and for one diagnostic worth having: a screen that leaves a scope open at the end of
     * a frame draws every later screen in a chapter's colours. That failure is invisible in a screenshot —
     * the frame it happens on looks correct — so the only place it can be caught is a check on the depth
     * after drawing, which is why this exists rather than being private.
     */
    public static int scopeDepth() {
        return scopes.size();
    }

    // ------------------------------------------------------------------
    // Surfaces, deepest first
    // ------------------------------------------------------------------

    /** The dim over the world, behind a screen's panel. Deliberately not fully opaque. */
    public static int dim() {
        return current().dim();
    }

    /** The canvas a graph or a map sits on — the deepest surface there is. */
    public static int canvas() {
        return current().canvas();
    }

    /**
     * What is drawn over the canvas colour: a flat surface, or a procedural pattern.
     *
     * <p>An accessor of its own rather than a field read at the call site, for the same reason
     * {@link #canvas()} is one: a screen asks the theme in force, which may be a chapter's, and
     * nothing outside this class tracks which scope that is.
     */
    public static CanvasBackground background() {
        return current().background();
    }

    /** The ink a canvas pattern is drawn in. Unused while the background is flat, which it is by default. */
    public static int canvasPattern() {
        return current().canvasPattern();
    }

    /** A recessed area inside a panel — a sidebar, a list. */
    public static int recessed() {
        return current().recessed();
    }

    /** A screen's main panel — the frame everything else sits inside. */
    public static int panel() {
        return current().panel();
    }

    /**
     * A raised area inside a panel — a header, a strip.
     *
     * <p>48,48,60 in the default theme. Deliberately far above the control fill, because a control sits
     * <b>on</b> a raised surface and the two must not be the same value. A theme is free to break that
     * relationship, which is a decision a reskin is allowed to make badly — but the default theme's
     * numbers are here so the next person can check the gap without opening an image editor.
     */
    public static int raised() {
        return current().raised();
    }

    /** A panel's border, and the divider between two surfaces. Bright enough to read as a line. */
    public static int panelEdge() {
        return current().panelEdge();
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    /**
     * The ten colours a control is drawn from.
     *
     * <h2>Why these are one accessor and not ten</h2>
     *
     * <p>Because which of them applies is a <b>precedence decision</b> — disabled beats selected beats
     * held beats hovered, with accent as a special case — and that decision lives in
     * {@link ArmatureControlStyle} and nowhere else. Ten loose accessors here would let a caller write
     * the chain itself, which is the duplication that class was extracted to prevent: a preview with its
     * own copy of these rules drew the selected chapter differently from the game for a whole round.
     *
     * <p>So the palette travels as a set. {@code ArmatureControlStyle} picks from it; a caller reads
     * {@link ArmatureControlStyle#fill} and never has to know the other nine exist.
     *
     * <h2>The trap the default palette is built around, which cost a reading of the file to notice</h2>
     *
     * <p>A raised strip at 48,48,60 and a control at 62,62,76 are <b>fourteen units apart</b>, and that
     * is a fix rather than a coincidence: the first version had the control three units above the strip
     * it sits on, so an unaccented button on a header was drawn in very nearly its own background. Same
     * mistake as two colliding buttons and an icon that did not scale with its box — <b>two values that
     * have to differ, chosen independently.</b> A theme is where they get chosen together.
     *
     * <h2>Reads the scope, so a chapter's own controls wear its palette</h2>
     *
     * <p>A control that belongs to a chapter — the buttons and fields on that chapter's own card, the
     * picker opened for its icon — is part of that chapter's surface, and a theme that reached every
     * pixel of the card except the buttons on it would read as a half-finished skin. So this reads
     * {@link #current()}: inside a chapter's scope its controls take the chapter's fills, edges and
     * accents, and outside one they take the main theme's.
     *
     * <p>Chrome that must not shift per chapter — the sidebar, the header, the right-click menus, the
     * tools panel — is drawn outside any chapter scope, so it keeps the main theme by position rather
     * than by a special case. {@link #chrome} remains the explicit escape hatch for a caller that needs
     * the main theme <i>inside</i> a scope.
     */
    public static Theme.Controls controls() {
        return current().controls();
    }

    /**
     * A control's border.
     *
     * <p>One of the four control edges, and the only one exposed on its own — because a scrollbar's track
     * is not a control and is not drawn by {@code ArmatureControlStyle}, so it has no state for a
     * precedence rule to resolve. The other three are read through {@link #controls()}, where the class
     * that decides which applies can reach them.
     */
    public static int controlEdge() {
        return current().controls().edge();
    }

    /** A control's border at its brightest, for a tooltip frame or a highlight. */
    public static int controlEdgeBright() {
        return current().controls().edgeBright();
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** A heading's own text, the brightest in the theme. */
    public static int title() {
        return current().title();
    }

    /** Ordinary text. */
    public static int body() {
        return current().body();
    }

    /** Secondary text: a subtitle, a count, a hint. */
    public static int faint() {
        return current().faint();
    }

    /** A section label like TASKS or REWARDS. */
    public static int heading() {
        return current().heading();
    }

    /** Behind a label drawn over something else, so text never sits directly on a line or an icon. */
    public static int labelBackdrop() {
        return current().labelBackdrop();
    }

    // ------------------------------------------------------------------
    // Progression: the state of an entry, and the border of its node
    // ------------------------------------------------------------------

    /** Available, not started. */
    public static int available() {
        return current().available();
    }

    /** Started, not finished. */
    public static int inProgress() {
        return current().inProgress();
    }

    /** Done. */
    public static int complete() {
        return current().complete();
    }

    /** Cannot be done yet. Legible as a border, not as a fog. */
    public static int blocked() {
        return current().blocked();
    }

    /**
     * The colour of an entry's state, by name, for a caller holding the name rather than four branches.
     *
     * <p>Exists because the state enum lives in a consumer mod and this class cannot know it — so the
     * mapping from a state's name to its colour is here, at the one place that has both the names and the
     * palette. The alternative was four {@code switch} arms in every consumer, which is the same switch
     * written as many times as there are screens.
     *
     * <p>An unknown name gives {@link #blocked}, on the grounds that an unrecognised state is one nothing
     * can be done about — and because the useful failure for a state label is a drab one rather than a
     * crash in the middle of a frame.
     *
     * @param state the state's name: {@code available}, {@code inProgress}, {@code complete} or
     *     {@code blocked}, matched case-insensitively
     */
    public static int state(String state) {
        if (state == null) {
            return blocked();
        }
        return switch (state.toLowerCase(java.util.Locale.ROOT)) {
            case "available", "unlocked" -> available();
            case "inprogress", "in_progress", "started" -> inProgress();
            case "complete", "completed" -> complete();
            default -> blocked();
        };
    }

    // ------------------------------------------------------------------
    // Graph
    // ------------------------------------------------------------------

    /** A node's fill, so an item with transparent corners still sits on something. */
    public static int nodeFill() {
        return current().nodeFill();
    }

    /**
     * A node's border when the entry cannot be started.
     *
     * <h2>The four node borders are tokens of their own, and why they still default to the state colours</h2>
     *
     * <p>Three of these four equal their state colour in every shipped theme, and the fourth does not —
     * see {@code ThemePatch}'s rule on which borders follow their state, and why {@code blocked} is
     * deliberately excluded. So why are they separate values a theme can set at all?
     *
     * <p>Because the two are <b>different statements that happen to agree in every theme written so
     * far</b>. {@code available} is the colour of the word "Available" in a list; {@code nodeEdgeAvailable}
     * is the ring around a box on a canvas. Those are different jobs — one is text on a panel and the
     * other is a shape outline on a background with items in it — and a pack author who wants a
     * desaturated ring under a bright label, or a node border that reads against a themed canvas while
     * the label keeps the contrast it needs, should not have to change both.
     *
     * <p>They were one value before, and the case that forced them apart was the canvas itself: a theme
     * that recolours the graph background cannot always keep one colour legible as both. Sharing a value
     * is the default, not the rule — and since it <i>is</i> the default, a theme file that sets
     * {@code available} and not {@code nodeEdgeAvailable} still gets a matching ring rather than the base
     * theme's. That fallback is implemented in {@code ThemePatch} rather than described here, because a
     * documented convention that nothing enforces is how a violet label ends up beside a blue ring.
     */
    public static int nodeEdgeBlocked() {
        return current().nodeEdgeBlocked();
    }

    /** A node's border when the entry can be started. Equals {@link #available} in every shipped theme. */
    public static int nodeEdgeAvailable() {
        return current().nodeEdgeAvailable();
    }

    /** A node's border when the entry is started. Equals {@link #inProgress} in every shipped theme. */
    public static int nodeEdgeInProgress() {
        return current().nodeEdgeInProgress();
    }

    /** A node's border when the entry is finished. Equals {@link #complete} in every shipped theme. */
    public static int nodeEdgeComplete() {
        return current().nodeEdgeComplete();
    }

    /**
     * A wash over a locked node's icon.
     *
     * <p>This replaced a chip with a cross drawn in the node's bottom-right corner. At node scale that
     * chip was a black square pasted over the artwork — the worst thing in one screenshot, and it hid
     * the one thing the icon is on the canvas to show. Dimming what is already there says "not yet"
     * without destroying it.
     */
    public static int nodeDim() {
        return current().nodeDim();
    }

    /** A wash over a completed node's icon, so "done" reads at a glance and still shows the item. */
    public static int nodeDoneWash() {
        return current().nodeDoneWash();
    }

    /** A dependency line between two nodes. */
    public static int line() {
        return current().line();
    }

    /** A dependency line whose prerequisite is met. */
    public static int lineDone() {
        return current().lineDone();
    }

    /** The ring around the node or row in use. */
    public static int selectedRing() {
        return current().selectedRing();
    }

    /** The ring around the one under the pointer, at full hover. Scaled by its eased amount. */
    public static int hoverRing() {
        return current().hoverRing();
    }

    /**
     * The wash behind a row the pointer is over — a task in an entry's body, a reward, a dependency.
     *
     * <h2>Why this is separate from a control's hover</h2>
     *
     * <p>A control's hover is a <b>fill</b>: the button's own background, a solid colour chosen to be
     * distinct from its resting fill. A row's hover is a <b>wash over whatever is already there</b> —
     * the panel colour, a connector line, an icon — so it has to be translucent, and it has to read
     * against both the panelled background and the description text above it.
     *
     * <p>Its alpha is deliberately low: a row's hover is a hint about where a click will land, not a
     * selection, and a row that brightened as much as a button would compete with the actual selection.
     * It is scaled by the hover's own eased amount at the call site, so this is the colour at <b>full</b>
     * hover — {@code Colour.translucent} is what fades it.
     */
    public static int rowHover() {
        return current().rowHover();
    }

    // ------------------------------------------------------------------
    // Scrollbar
    // ------------------------------------------------------------------

    /**
     * The groove a scrollbar's thumb runs in.
     *
     * <h2>Why a scrollbar got two tokens of its own</h2>
     *
     * <p>Because it is the one part of the chrome that was invisible to a theme, and it showed. It was
     * drawn from {@link #raised} and {@link #controlEdge} — both reasonable at the time, since neither
     * was chosen for it — which meant every theme got the same relationship between a track and the panel
     * behind it, and a theme with a light panel had a track with no contrast against it at all.
     *
     * <p>The general point is worth stating, because it is the same one that produced the four node
     * borders: <b>a colour borrowed for a job it was not chosen for is a colour that will be wrong for
     * one of the two jobs.</b> The scrollbar is where that was most visible.
     */
    public static int scrollTrack() {
        return current().scrollTrack();
    }

    /** The scrollbar's thumb — the moving part, and the one that has to read against the track. */
    public static int scrollThumb() {
        return current().scrollThumb();
    }

    // ------------------------------------------------------------------
    // Tooltips
    // ------------------------------------------------------------------

    /**
     * Behind a floating panel that follows the pointer.
     *
     * <p>Separate from {@link #labelBackdrop}, which is nearly the same colour in every shipped theme and
     * is genuinely a different surface: a label backdrop is a strip behind one line of text drawn over a
     * canvas, and it is sized to that line. This is a panel with a border and several lines in it. They
     * agree in the default theme because a tooltip drawn over black should be the same black as a label —
     * and a light theme is where they must be allowed to differ, since a tooltip over a pale canvas needs
     * a different relationship to its surroundings than a caption does.
     */
    public static int tooltipFill() {
        return current().tooltipFill();
    }

    /** The border of that panel. See {@link #tooltipFill} for why this is not `controlEdgeBright`. */
    public static int tooltipEdge() {
        return current().tooltipEdge();
    }

    /**
     * The ink of the lines inside that panel.
     *
     * <p>A token of its own rather than {@code body()}, because a tooltip is a floating surface with its
     * own fill: on a light theme the body ink that reads well on a panel can vanish on a tooltip, and
     * the point of the tooltip tokens is that a theme answers this without touching its prose.
     */
    public static int tooltipText() {
        return current().tooltipText();
    }

    // ------------------------------------------------------------------
    // Drawing helpers
    // ------------------------------------------------------------------

    /**
     * A one-pixel rectangle outline, as four fills.
     *
     * <p>Provided because there is no stroke primitive — not in {@code GuiGraphics}, and in nothing the
     * seam covers — so every outline in this UI is four {@code fill} calls, and writing that out at each
     * site is four chances to get one edge wrong. The width is not a parameter: nothing in this UI wants
     * a 2px border, and a width argument invites one.
     *
     * <p><b>Square, always, whatever the theme's radius is.</b> That is not an oversight — this is the
     * outline for small things: a progress bar six pixels tall, a swatch, a divider. A theme radius of 8
     * applied to a 6-pixel bar gives an oval, which is not what a progress bar is. Anything big enough
     * for a corner to read as a corner goes through {@link #panel} or {@link #fillSurface}, which do
     * honour it.
     */
    public static void outline(GuiRenderer renderer, int left, int top, int width, int height, int colour) {
        renderer.fill(left, top, left + width, top + 1, colour);
        renderer.fill(left, top + height - 1, left + width, top + height, colour);
        renderer.fill(left, top, left + 1, top + height, colour);
        renderer.fill(left + width - 1, top, left + width, top + height, colour);
    }

    /**
     * A filled panel with a one-pixel border, rounded to the theme's corner radius.
     *
     * <p>The single most repeated pair of calls in this UI, and the reason it is a method rather than
     * two lines at each site: <b>the border is drawn as the whole footprint and the fill is then drawn
     * inset by one pixel</b>, which leaves exactly a one-pixel ring of border. Getting that the other way
     * round — fill the box, then draw a border over it — produces a panel that is a solid block of border
     * colour, which reads as the fill colour being wrong rather than as a call in the wrong order.
     *
     * <p>The colour overload is the one to prefer, since it takes the pair from the palette in force;
     * this form exists for the caller that has a reason to pass both — a selection chip drawn in its own
     * colours, a border that is deliberately a different theme's.
     *
     * <h2>This is where the theme's radius is spent, and it was spent nowhere until now</h2>
     *
     * <p>{@code Theme.cornerRadius} existed, every theme set it, and <b>nothing drew it</b> — this method
     * filled a rectangle and the outline went round it squarely. So the two themes advertising a radius of
     * 8 and the two advertising 0 rendered identically, which made the value a lie rather than a setting:
     * the exact "parsed, validated, printed, read by nothing" failure this codebase has now recorded four
     * times. Found by grepping for the accessor rather than by looking at the screen, because a radius that
     * does nothing looks like a theme that was designed that way.
     *
     * <p>The shape comes from {@link RoundedRect}, which was already written and already tested for the
     * node shapes — so the arithmetic of a quarter-circle corner exists in exactly one place, and this
     * method supplies the part that was missing: a radius that applies to a rectangle rather than a square.
     */
    public static void panel(GuiRenderer renderer, int left, int top, int width, int height,
                             int fill, int border) {
        int radius = current().cornerRadius();
        // The border as the whole footprint, then the fill inset by one -- which is the same pixels as
        // "fill the rectangle, then draw a one-pixel outline over it" and is what makes the corners round
        // without the border having to be a separate shape that has to agree with the fill's.
        fillSurface(renderer, left, top, width, height, border, radius, CORNERS_ALL);
        if (width > 2 && height > 2) {
            // Inset radius, so the gap between the two curves is one pixel on the diagonal as well as on
            // the sides. Reusing the outer radius would make the border visibly thicker at the corners --
            // a 4-pixel radius carrying a 2-pixel border there and a 1-pixel border everywhere else.
            fillSurface(renderer, left + 1, top + 1, width - 2, height - 2, fill,
                    Math.max(0, radius - 1), CORNERS_ALL);
        }
    }

    /** A panel in the palette in force: {@link #panel} filled, {@link #panelEdge} bordered. */
    public static void panel(GuiRenderer renderer, int left, int top, int width, int height) {
        panel(renderer, left, top, width, height, panel(), panelEdge());
    }

    /**
     * A rounded fill, for a surface that is not a panel: a strip inside one, a backdrop.
     *
     * <p>See {@link #fillSurface} for the corner mask, which is the parameter that matters here. A
     * surface inside a panel rounds only the corners it shares with that panel — a sidebar down the left
     * edge rounds its left corners and leaves its right ones square, because the right edge is interior.
     */
    public static void fillSurface(GuiRenderer renderer, int left, int top, int width, int height,
                                   int colour, int corners) {
        fillSurface(renderer, left, top, width, height, colour, current().cornerRadius(), corners);
    }

    /** Top-left, top-right, bottom-right and bottom-left, as a bit each. */
    public static final int TOP_LEFT = 1;
    public static final int TOP_RIGHT = 2;
    public static final int BOTTOM_RIGHT = 4;
    public static final int BOTTOM_LEFT = 8;

    /** All four, for a surface that stands on its own. */
    public static final int CORNERS_ALL = TOP_LEFT | TOP_RIGHT | BOTTOM_RIGHT | BOTTOM_LEFT;

    /** The two on the top edge, for a strip along the top of a panel. */
    public static final int CORNERS_TOP = TOP_LEFT | TOP_RIGHT;

    /** The two down the left edge, for a sidebar. */
    public static final int CORNERS_LEFT = TOP_LEFT | BOTTOM_LEFT;

    /**
     * A fill whose specified corners are rounded to {@code radius} and whose others are square.
     *
     * <h2>Why a mask rather than "round all four"</h2>
     *
     * <p>Because a panel is almost always more than one surface. The book screen is a rounded panel with a
     * recessed sidebar down its left edge and a raised header across the top, and both of those touch two
     * of the panel's corners and none of the other two. Rounding all four of the sidebar's corners leaves
     * two notches in the corner of the panel where the sidebar is not; rounding none of them leaves a
     * square corner poking out of the panel's rounded one. The mask is how a caller says which, and there
     * are exactly three cases in this UI: a whole panel, a strip on one edge, and a surface that touches
     * nothing.
     *
     * <h2>Row by row, with equal runs merged</h2>
     *
     * <p>A rounded rectangle is a per-row span, and the arithmetic for one is
     * {@link RoundedRect#cornerCut} — already written and already swept by a test over every size and
     * radius, for the node shapes. What that method does not do is a <i>rectangle</i>: it is written for a
     * square node, so this supplies the missing half, which is that a corner's cut depends on the distance
     * to the nearest horizontal edge and not on the width.
     *
     * <p>{@code fillShape} merges rows with an identical span, so a rectangle at radius 0 is one fill call
     * and a rounded one is about {@code 2r + 1} — which is the difference between this being affordable on
     * a panel per frame and being a decoration nobody enables.
     */
    public static void fillSurface(GuiRenderer renderer, int left, int top, int width, int height,
                                   int colour, int radius, int corners) {
        if (width <= 0 || height <= 0) {
            // A zero-width surface is what a collapsed sidebar or an empty strip is, and it is not an
            // error. Returning early keeps `fillShape`'s own guard from being the only thing standing
            // between a caller and a fill that straddles nothing.
            return;
        }
        if (corners == 0 || radius <= 0) {
            renderer.fill(left, top, left + width, top + height, colour);
            return;
        }
        emit(renderer, left, top, colour, surfacePlan(width, height, radius, corners));
    }

    /**
     * The horizontal extent of a rounded rectangle on one row, or {@code {0, width}} for a row that is
     * entirely inside it.
     *
     * <p>Measured from whichever horizontal edge is nearer, which is the whole of the rectangle case: a
     * corner is a quarter circle whose cut depends on how far into the corner you are, and "into the
     * corner" is the distance to the top edge for the top two and to the bottom edge for the bottom two.
     * The width is not involved at all, which is why a 300×40 panel and a 40×40 node round by the same
     * amount at the same radius.
     */
    private static int[] roundedSpan(int row, int width, int height, int radius, int corners) {
        // Clamped to half the shorter side, so a radius bigger than the surface cannot produce a span
        // that runs backwards. `RoundedRect` clamps internally too, and the clamping here is about the
        // two-dimensional case it was not written for.
        int r = Math.min(radius, Math.min(width, height) / 2);
        if (r <= 0) {
            return new int[] {0, width};
        }

        int fromTop = row;
        int fromBottom = height - 1 - row;

        if (fromTop < r) {
            int cut = RoundedRect.cornerCut(fromTop, height, r);
            boolean roundLeft = (corners & TOP_LEFT) != 0;
            boolean roundRight = (corners & TOP_RIGHT) != 0;
            return new int[] {roundLeft ? cut : 0, roundRight ? width - cut : width};
        }
        if (fromBottom < r) {
            int cut = RoundedRect.cornerCut(fromBottom, height, r);
            boolean roundLeft = (corners & BOTTOM_LEFT) != 0;
            boolean roundRight = (corners & BOTTOM_RIGHT) != 0;
            return new int[] {roundLeft ? cut : 0, roundRight ? width - cut : width};
        }
        return new int[] {0, width};
    }

    /**
     * The horizontal extent of a shape on one row, as flattened {@code {from, to, …}} pairs.
     *
     * <p>The functional interface shapes are built from. It is this rather than the full {@code Shape}
     * type so a caller can supply the lookup as a method reference — {@code entry.shape()::spans} — which
     * is what every call site does and what keeps a shape from having to be boxed or wrapped to draw one.
     */
    @FunctionalInterface
    public interface Spans {
        /** {@code null} for a row outside the shape, or for a row with no material. */
        int[] spans(int row, int size);
    }

    /**
     * Fills a whole shape row by row, merging rows that share a span, and filling every interval.
     *
     * <h2>Why merging matters rather than being an optimisation</h2>
     *
     * <p>A square or a rounded rectangle has long runs of identical rows, and a shape drawn a row at a
     * time is forty-eight {@code fill} calls for a node — thirty nodes on a chapter, and a canvas that
     * redraws every frame. Merging brings a rectangle down to one call and a rounded rectangle to
     * about nine, which is the difference between a shape being affordable and being a decoration
     * nobody enables.
     *
     * <h2>Several intervals per row</h2>
     *
     * <p>A row's spans are sorted and disjoint — {@link dev.ellipog.armature.client.ui.shape.Shape#spans}
     * guarantees both — so each pair is one fill, and two rows merge when their <i>whole</i> lists are
     * equal. Comparing only the first pair would merge a heart's two-lobed row with a one-lobed row
     * beneath it and fill the notch, which is the kind of bug a picture shows and a number does not.
     */
    public static void fillShape(GuiRenderer renderer, int x, int y, int size, int colour,
                                 Spans spans) {
        if (size <= 0) {
            return;
        }
        emit(renderer, x, y, colour, Plans.merge(spans::spans, size));
    }

    /**
     * The same, for a caller that has the shape itself — which is every hot one.
     *
     * <p>A shape's rows are merged once per shape and size instead of once per call, so a node's four layers
     * cost four plan lookups and their rectangles rather than two hundred table reads and comparisons. The
     * fills are unchanged and unchanged in number: a circle is still one fill per distinct row, because
     * merging them further would change the pixels. See {@link Plans}.
     *
     * <p>The key is the shape object and the size, and that works because the layers a node draws are stable
     * objects: the built-ins are singletons, and {@code inner}/{@code outer} memoise their own results per
     * inset. A shape built at the call site would miss every frame — {@code Shapes.cached} is how to make one
     * that does not.
     */
    public static void fillShape(GuiRenderer renderer, int x, int y, int size, int colour, Shape shape) {
        if (size <= 0) {
            return;
        }
        emit(renderer, x, y, colour, Plans.of(shape, size));
    }

    /** One plan's rectangles at an origin: the single place a plan becomes fills. */
    private static void emit(GuiRenderer renderer, int x, int y, int colour, Plans.Plan plan) {
        for (int i = 0; i < plan.count(); i++) {
            renderer.fill(x + plan.x1(i), y + plan.y1(i), x + plan.x2(i), y + plan.y2(i), colour);
        }
    }

    /** How many rounded-rectangle plans are remembered, and the four numbers they are keyed by. */
    private static final int SURFACE_PLAN_LIMIT = 256;

    private record SurfaceKey(int width, int height, int radius, int corners) {
    }

    private static final Map<SurfaceKey, Plans.Plan> SURFACES = new HashMap<>();

    /**
     * A rounded rectangle's rectangles, remembered by its own four numbers.
     *
     * <p>{@code fillSurface} used to hand {@code fillShape} a lambda built at the call site, so the merge
     * ran per call and nothing could be keyed on it — every panel and every control in the toolkit paid it
     * every frame. The four numbers are the whole of what the spans depend on, so they are the whole of the
     * key.
     */
    private static Plans.Plan surfacePlan(int width, int height, int radius, int corners) {
        SurfaceKey key = new SurfaceKey(width, height, radius, corners);
        Plans.Plan plan = SURFACES.get(key);
        // Reported for both answers: see CacheHits, which exists because a table of this kind was once
        // built and unreachable with nothing able to see it. The key is four ints, so a hit here says the
        // same panel is being drawn at the same size again -- which is the normal case, and the whole
        // reason the table exists.
        CacheHits.asked(SURFACE_CACHE, plan != null);
        if (plan != null) {
            return plan;
        }
        Plans.Plan built = Plans.merge(
                (row, size) -> roundedSpan(row, width, height, radius, corners), height);
        if (SURFACES.size() >= SURFACE_PLAN_LIMIT) {
            SURFACES.clear();
        }
        SURFACES.put(key, built);
        return built;
    }

    /** This table's name in the hit/miss report. A constant, so a probe allocates nothing. */
    private static final String SURFACE_CACHE = "surfaces";

    /**
     * A shape's border and its fill, in that order.
     *
     * <p>The shape equivalent of {@link #panel}, and the same argument applies: the border is the shape at
     * full size, inset by one on every side for the fill. Reversed, the fill covers the border entirely
     * and a node loses the state colour that tells you whether it can be started.
     *
     * <h2>The fill is the border's own table, transformed — not the same function at {@code size - 2}</h2>
     *
     * <p>That second sampling is what a node's outline used to break on. Nothing related the table at
     * {@code size - 2} to the one at {@code size}, so wherever a size-dependent feature stepped — a
     * gear's tooth count at 32 pixels, a rounded rectangle's integer radius every four, a tome's notch,
     * the minimum width of a tip — the fill disagreed with the border: it covered the outline at one
     * size and reached outside it at another, which is a node whose line does not close. The transform
     * makes the fill a pixel inside the border by construction, at every size, for every shape, and for a
     * turned node as much as an upright one. See {@link Outlines}.
     */
    public static void shapePanel(GuiRenderer renderer, int x, int y, int size, int fill, int border,
                                  Shape shape) {
        fillShape(renderer, x, y, size, border, shape);
        if (size > 2) {
            // The shape's own eroded layer rather than one built here, and that is the difference
            // between an erosion per node per frame and an erosion per shape: `inner` is a table once
            // it has been asked for a size — see Shape.inner and Shapes.cached. The caller has to be
            // the thing that holds the shape for that to be worth anything, which is why this takes
            // one rather than a bare row lookup. It is also what makes the fill plan findable: the same
            // layer object comes back every frame, so its rectangles are worked out once.
            fillShape(renderer, x + 1, y + 1, size - 2, fill, shape.inner());
        }
    }
}

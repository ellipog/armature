package dev.ellipog.armature.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every colour a theme can set, named — one entry per value in {@link Theme}.
 *
 * <h2>Why a registry exists at all, when {@code Theme} already has the fields</h2>
 *
 * <p>Because three separate things need to refer to "one colour of the theme" <i>by name</i>, and none
 * of them can use a field reference:
 *
 * <ul>
 *   <li><b>A theme file</b> — {@code "panel": "#24242E"} is a key in JSON. There is no field to point
 *       at, so a mistyped key was previously a colour that silently never applied.</li>
 *   <li><b>A group or entry overriding one colour</b> — the same, but arriving from a data file on a
 *       server that has no idea what a {@code Theme} is.</li>
 *   <li><b>The editor</b> — a screen listing every value has to be built from a list, not from thirty
 *       hand-written widgets, or the thirty-first value is one nobody can edit.</li>
 * </ul>
 *
 * <p>So this is the <b>index</b>: the record is still the storage, and this names the slots in it.
 *
 * <h2>The order here is load-bearing, and a test enforces it</h2>
 *
 * <p>{@link #ALL} is in exactly the order {@link Theme#allColours()} returns values, and
 * {@link Theme#from} builds a theme by walking that same order. That is what lets a patch be a map of
 * name to colour with one merge implementation rather than forty branches.
 *
 * <p>The risk in an arrangement like that is that the two orders drift, and the failure is silent:
 * a reordered array does not fail to compile, it swaps two colours, and the result looks like a
 * slightly wrong theme rather than a bug. {@code ThemeTokenTest} closes it by reading the record
 * reflectively and asserting that every token's value is the component it is named after — so
 * reordering one without the other fails the build, naming the token.
 *
 * <h2>Only colours: radius, motion and easing are settings, not tokens</h2>
 *
 * <p>They are three values rather than forty, they are not colours, and an editor wants a number box
 * or a dropdown for them rather than a swatch. Putting them in this list would mean a list of things
 * that are mostly colours with three exceptions, and every consumer branching on which kind it has.
 * {@code ThemePatch} carries them as fields, beside the colour map.
 *
 * @param id    the key: what a file says, what a patch names, and what the editor lists. Identical to
 *              the corresponding record component's name, which is what makes the reflection test
 *              possible and what makes the JSON keys guessable
 * @param label what a person reads in the editor, since {@code nodeDoneWash} is not a phrase
 * @param group which part of the interface this belongs to, so the editor can section the list and a
 *              reader can find "the scrollbar colours" without scanning forty entries
 */
public record ThemeToken(String id, String label, Group group) {

    /**
     * The sections an editor groups by, in the order they should be shown.
     *
     * <p>The order is the same reasoning as {@code Controls}' comment: it is the order of the drawing,
     * deepest surface first. A person editing a theme works from the background forwards, because every
     * colour chosen after the first is chosen <i>against</i> it — a text colour is picked to read on a
     * panel, not in the abstract.
     */
    public enum Group {

        /** The background stack: the screen's dim, its panel, and the surfaces inside it. */
        SURFACE("Surfaces"),

        /** Everything text is drawn in. */
        TEXT("Text"),

        /** The four progression states, which are also the progression borders on a graph node. */
        STATE("Progression"),

        /** The graph itself: nodes, their outlines, and the lines between them. */
        GRAPH("Graph"),

        /** Washes and backdrops used by list rows and floating labels. */
        ROW("Rows and labels"),

        /** The scrollbar, which is the one part of the chrome with a colour of its own. */
        SCROLL("Scrollbar"),

        /** A floating panel that follows the pointer. */
        OVERLAY("Tooltips"),

        /** The control palette, as a set. */
        CONTROL("Controls");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        /** The name to show a person. */
        public String label() {
            return label;
        }
    }

    /**
     * The tokens whose values live in {@link Theme}'s own components.
     *
     * <h2>In the order {@code Theme.allColours()} returns them</h2>
     *
     * <p>Grouped by {@link Group} inside that order rather than sorted, because the two orderings answer
     * different questions: this one is the storage order and has to match the record, and the group is
     * what an editor groups by. Sorting this list would break the pairing for a cosmetic gain.
     */
    private static final List<ThemeToken> SURFACES = List.of(
            new ThemeToken("dim", "Screen dim", Group.SURFACE),
            new ThemeToken("canvas", "Graph background", Group.SURFACE),
            new ThemeToken("recessed", "Recessed area", Group.SURFACE),
            new ThemeToken("panel", "Panel", Group.SURFACE),
            new ThemeToken("raised", "Raised strip", Group.SURFACE),
            new ThemeToken("panelEdge", "Panel border", Group.SURFACE));

    private static final List<ThemeToken> TEXT = List.of(
            new ThemeToken("title", "Title", Group.TEXT),
            new ThemeToken("body", "Body", Group.TEXT),
            new ThemeToken("faint", "Secondary text", Group.TEXT),
            new ThemeToken("heading", "Section heading", Group.TEXT));

    /**
     * The four states, and the reason they are called progression rather than status.
     *
     * <p>Each of these is drawn twice: once as the colour of a state's label, and once as the
     * <b>border of a graph node</b> in that state. That second use is what makes them the progression
     * borders, and it is why they are worth a group label that says so — an author looking for "the
     * border on a finished node" would otherwise have to work out that it is the same value as the
     * word "complete" in a list.
     */
    private static final List<ThemeToken> STATES = List.of(
            new ThemeToken("available", "Available", Group.STATE),
            new ThemeToken("inProgress", "In progress", Group.STATE),
            new ThemeToken("complete", "Complete", Group.STATE),
            new ThemeToken("blocked", "Blocked", Group.STATE));

    private static final List<ThemeToken> GRAPH = List.of(
            new ThemeToken("nodeFill", "Node interior", Group.GRAPH),
            new ThemeToken("nodeDim", "Locked node wash", Group.GRAPH),
            new ThemeToken("nodeDoneWash", "Completed node wash", Group.GRAPH),
            new ThemeToken("nodeEdgeBlocked", "Node border, blocked", Group.GRAPH),
            new ThemeToken("nodeEdgeAvailable", "Node border, available", Group.GRAPH),
            new ThemeToken("nodeEdgeInProgress", "Node border, in progress", Group.GRAPH),
            new ThemeToken("nodeEdgeComplete", "Node border, complete", Group.GRAPH),
            new ThemeToken("line", "Dependency line", Group.GRAPH),
            new ThemeToken("lineDone", "Dependency line, met", Group.GRAPH),
            new ThemeToken("selectedRing", "Selected ring", Group.GRAPH),
            new ThemeToken("hoverRing", "Hover ring", Group.GRAPH),
            new ThemeToken("canvasPattern", "Canvas pattern ink", Group.GRAPH));

    private static final List<ThemeToken> ROWS = List.of(
            new ThemeToken("rowHover", "Row hover wash", Group.ROW),
            new ThemeToken("labelBackdrop", "Label backdrop", Group.ROW));

    private static final List<ThemeToken> SCROLL = List.of(
            new ThemeToken("scrollTrack", "Scrollbar track", Group.SCROLL),
            new ThemeToken("scrollThumb", "Scrollbar thumb", Group.SCROLL));

    private static final List<ThemeToken> OVERLAYS = List.of(
            new ThemeToken("tooltipFill", "Tooltip background", Group.OVERLAY),
            new ThemeToken("tooltipEdge", "Tooltip border", Group.OVERLAY),
            new ThemeToken("tooltipText", "Tooltip text", Group.OVERLAY));

    /**
     * The ten control colours, whose values live in {@link Theme.Controls}.
     *
     * <p>Named after the component rather than prefixed, so the id is what a caller would guess. They
     * are still distinguishable in a file by where they sit in the JSON — a theme file nests them under
     * {@code controls}, so {@code "fill"} there cannot be confused with anything else.
     */
    private static final List<ThemeToken> CONTROL = List.of(
            new ThemeToken("fill", "Fill", Group.CONTROL),
            new ThemeToken("hover", "Fill, hovered", Group.CONTROL),
            new ThemeToken("held", "Fill, held", Group.CONTROL),
            new ThemeToken("selected", "Fill, selected", Group.CONTROL),
            new ThemeToken("disabled", "Fill, disabled", Group.CONTROL),
            new ThemeToken("accent", "Fill, accent", Group.CONTROL),
            new ThemeToken("edge", "Border", Group.CONTROL),
            new ThemeToken("edgeAccent", "Border, accent", Group.CONTROL),
            new ThemeToken("edgeSelected", "Border, selected", Group.CONTROL),
            new ThemeToken("edgeBright", "Border, bright", Group.CONTROL));

    /**
     * The components of {@link Theme} that are not in {@link Theme.Controls}, in storage order.
     *
     * <p>Public because {@link Theme#from} needs the split point and the reflection test needs the list:
     * a theme is {@code (name, topLevelColours, controls, radius, motion, easing)}, and the boundary
     * between the first two has to be stated somewhere. Stating it here, where the lists are already
     * written out, means there is exactly one place it could be wrong.
     */
    public static final List<ThemeToken> TOP_LEVEL = List.of(
            SURFACES, TEXT, STATES, GRAPH, ROWS, SCROLL, OVERLAYS).stream()
            .flatMap(List::stream)
            .toList();

    /** The control colours, in storage order. */
    public static final List<ThemeToken> CONTROLS = CONTROL;

    /** Every token, top-level colours first and control colours last. */
    public static final List<ThemeToken> ALL;

    /** Where {@link #ALL} switches from {@link Theme}'s components to {@link Theme.Controls}. */
    public static final int CONTROL_START = TOP_LEVEL.size();

    private static final Map<String, ThemeToken> BY_ID;

    static {
        List<ThemeToken> all = new ArrayList<>(TOP_LEVEL);
        all.addAll(CONTROLS);
        ALL = List.copyOf(all);

        Map<String, ThemeToken> byId = new LinkedHashMap<>();
        for (ThemeToken token : ALL) {
            ThemeToken previous = byId.put(token.id().toLowerCase(java.util.Locale.ROOT), token);
            if (previous != null) {
                // Two tokens with one id is not a subtle failure: a patch naming it would set one and
                // a file naming it would set the other, so half of a theme would ignore half its
                // configuration. Louder than an exception here would be an exception at the first use.
                throw new IllegalStateException("two theme tokens share the id '" + token.id()
                        + "': " + previous + " and " + token);
            }
        }
        BY_ID = Map.copyOf(byId);
    }

    /** This token's position in {@link #ALL}, which is also its position in {@code Theme.allColours}. */
    public int index() {
        return ALL.indexOf(this);
    }

    /**
     * A token by id, or null when nothing matches.
     *
     * <p>Null rather than a default, for the reason {@code Theme.byName} gives: the id comes from a file
     * a person wrote or from a server, and the honest answer to "there is no such colour" is to say so.
     * A fallback would silently drop that part of a patch, which on a forty-entry theme means a theme
     * that mostly worked and nobody can say which fifth of it did not.
     */
    public static ThemeToken byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return BY_ID.get(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Whether an id names a colour a theme can set. */
    public static boolean exists(String id) {
        return byId(id) != null;
    }

    /** This token's position in {@link #ALL}, or -1 when the id matches nothing. */
    public static int indexOf(String id) {
        ThemeToken token = byId(id);
        return token == null ? -1 : token.index();
    }

    /** The tokens in one group, in storage order. What the editor's sections are built from. */
    public static List<ThemeToken> inGroup(Group group) {
        return ALL.stream().filter(token -> token.group() == group).toList();
    }

    /** Every id, for a message listing them: {@code dim, canvas, recessed, ...}. */
    public static String ids() {
        return String.join(", ", ALL.stream().map(ThemeToken::id).toList());
    }
}

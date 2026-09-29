package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A collapsible outline: keyed nodes, each with a parent and a depth, and the rows that are visible.
 *
 * <h2>Why this is a class rather than a list of flags on a screen</h2>
 *
 * <p>Because "which rows are on screen" is derived, not stored, and the derivation is where the bugs
 * are. A screen that keeps a flat list of rows and a set of collapsed keys has to answer three
 * questions on every frame — is this row inside a collapsed branch, how far is it indented, and is
 * the row itself collapsed — and it answers them by walking parents at the point of drawing, once per
 * row. Every one of those three answers is a place a two-level list can be got wrong in a way that
 * looks like a data problem rather than a drawing problem: a row that is indented but should not be
 * visible, or a row that is hidden because its <i>grandparent</i> is collapsed and nothing said so.
 *
 * <p>So the walking happens once, here, and comes out as a list of the keys that should be drawn, in
 * declaration order. The depth is computed when a node is added rather than when a row is drawn,
 * which is the difference between one climb per node and one climb per row per frame.
 *
 * <h2>Declaration order is the order, and it is not sorted</h2>
 *
 * <p>{@link #visibleRows()} returns nodes in the order they were added, not in key order. That is
 * deliberate and it is the whole reason the kit can serve a hand-authored tree at all: an author who
 * writes chapters in an order means that order, and a list that sorted itself by identifier would
 * silently reorder a book. Insertion order is also what makes the class usable with a key type that
 * has no natural ordering — an enum, a record, an identifier string.
 *
 * <h2>A parent must be declared first, and that is a check rather than a convention</h2>
 *
 * <p>{@link #add} refuses a key whose parent it has not seen. The alternative — accepting the node and
 * resolving the depth later — turns a discovery loop that got its order wrong into an outline where
 * every node is at depth zero, which draws as a flat list with no indentation and no error anywhere.
 * The thrown exception names both keys, so the loop that produced the wrong order is obvious from the
 * message.
 *
 * <h2>Leaves are never collapsible, and the two questions are kept apart</h2>
 *
 * <p>{@link #isExpanded} reports whether a node's children are being shown, and {@link #isCollapsible}
 * reports whether it has any. They are separate because the two are used separately: a caller drawing
 * a chevron asks {@code isCollapsible} first, and a caller deciding whether to draw the next row asks
 * {@code isExpanded}. Folding them into one method is how a heading with no children ends up with a
 * disclosure arrow that does nothing when it is clicked.
 *
 * <h2>Seeding from defaults is not the same as expanding everything</h2>
 *
 * <p>{@link #seedFromDefaults} gives each node the expanded state it was <i>declared</i> with. A tree
 * whose groups are declared collapsed opens with those groups closed, which is the authored intent,
 * and the state is then the player's: nothing here writes anything anywhere, so a player's toggles
 * survive for as long as the object does and no further. Persisting them is a caller's decision about
 * a caller's file, and this class has no opinion about it.
 *
 * <p><b>Game-free by design.</b> Every field is a key, an {@code int} or a {@code boolean} — no
 * widget, no renderer, no font. That is what lets the derivation above be asserted on directly instead
 * of being judged by looking at a list of rows.
 *
 * @param <K> what a node is identified by. Declared order is the insertion order, so no natural
 *     ordering is required.
 */
public final class Outline<K> {

    /**
     * One node: what it is, where it hangs, and the state it was declared with.
     *
     * <p>Mutable, and private. The outline is the only thing allowed to change a node, because two of
     * these fields have invariants that a caller could break from outside — {@code depth} has to agree
     * with the parent chain, and {@code childKeys} has to agree with every other node's {@code parent}.
     */
    private static final class Node<K> {

        private final K key;
        private final K parent;
        private final int depth;
        private final boolean expandedByDefault;
        private final List<K> childKeys = new ArrayList<>();

        private Node(K key, K parent, int depth, boolean expandedByDefault) {
            this.key = key;
            this.parent = parent;
            this.depth = depth;
            this.expandedByDefault = expandedByDefault;
        }
    }

    private final Map<K, Node<K>> nodes = new LinkedHashMap<>();
    private final Set<K> expanded = new HashSet<>();

    private Outline() {
    }

    /** A new, empty outline. */
    public static <K> Outline<K> of() {
        return new Outline<>();
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * Declares a node that starts expanded, under {@code parent}.
     *
     * @param parent the key of an already-declared node, or null for a root
     */
    public Outline<K> add(K key, K parent) {
        return add(key, parent, true);
    }

    /**
     * Declares a node under {@code parent}.
     *
     * <p>{@code expandedByDefault} is a property of the <i>declaration</i> and not of the state: the
     * node's current expanded state is untouched by adding a child to it later, so a tree can be built
     * and then have {@link #seedFromDefaults} applied once, when it is first shown.
     *
     * @param parent           the key of an already-declared node, or null for a root
     * @param expandedByDefault whether this node's children are shown when the tree is first seeded
     * @throws IllegalArgumentException if the key is already declared, or the parent is not declared yet
     */
    public Outline<K> add(K key, K parent, boolean expandedByDefault) {
        Objects.requireNonNull(key, "key -- a null key cannot be looked up, so the node would be invisible to every caller");

        if (nodes.containsKey(key)) {
            throw new IllegalArgumentException("a node is already declared with the key " + key
                    + "; every key has to be unique or a lookup cannot say which node it means");
        }

        int depth = 0;
        if (parent != null) {
            Node<K> found = nodes.get(parent);
            if (found == null) {
                throw new IllegalArgumentException("cannot add " + key + " under " + parent
                        + ": that parent has not been declared yet. Declare a parent before its children,"
                        + " because the depth is computed once here rather than climbed on every frame.");
            }
            depth = found.depth + 1;
            found.childKeys.add(key);
        }

        // Purely structural: building a tree does not decide what is visible. That decision is
        // `seedFromDefaults`'s alone, which is why there is exactly one place that populates
        // `expanded` from what was declared.
        //
        // Doing it here as well was the first version and it read as a convenience: a node added under
        // an expanded parent appeared immediately, so a caller building a tree and drawing it got
        // sensible output without remembering a second call. What it cost was that "what is expanded"
        // then depended on *the order nodes were added in*, which is a property of a discovery loop
        // rather than of the tree. A test that added a chapter before its group and one that added it
        // after would see different rows from the same declaration, and nothing would say why.
        nodes.put(key, new Node<>(key, parent, depth, expandedByDefault));
        return this;
    }

    // ------------------------------------------------------------------
    // The state a tree is first shown in
    // ------------------------------------------------------------------

    /**
     * Gives every node that has children the expanded state it was declared with.
     *
     * <p>Discards whatever the player has toggled, which is the point: this is for the first sight of
     * a tree, and running it on every frame would undo each toggle as it was made. A caller keeps the
     * outline and calls this once, when the tree it describes arrives.
     */
    public Outline<K> seedFromDefaults() {
        expanded.clear();
        for (Node<K> node : nodes.values()) {
            if (!node.childKeys.isEmpty() && node.expandedByDefault) {
                expanded.add(node.key);
            }
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    /**
     * The keys to draw, in declaration order: every node whose ancestors are all expanded.
     *
     * <p>A node whose own children are hidden is still in this list — it is the row carrying the
     * collapsed marker, and dropping it would leave a parent missing and its branch with nothing to
     * point back at.
     *
     * <p>Memoised within one call, so the cost is a single pass rather than a climb per row. The
     * recursion bottoms out at a root, and the depth is bounded by the tree, which for a two-level
     * outline is two frames.
     */
    public List<K> visibleRows() {
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<K, Boolean> known = new HashMap<>();
        List<K> out = new ArrayList<>(nodes.size());
        for (K key : nodes.keySet()) {
            if (isVisible(key, known)) {
                out.add(key);
            }
        }
        return List.copyOf(out);
    }

    /** How many rows {@link #visibleRows} would return, without building the list. */
    public int visibleRowCount() {
        return visibleRows().size();
    }

    private boolean isVisible(K key, Map<K, Boolean> known) {
        Boolean cached = known.get(key);
        if (cached != null) {
            return cached;
        }

        Node<K> node = nodes.get(key);
        boolean visible;
        if (node.parent == null) {
            // A root is always drawn. Nothing gates it, and a collapsed group whose own row vanished
            // would be a group that could never be expanded again.
            visible = true;
        } else {
            // Both halves are needed. `expanded.contains(...)` is the parent's own state;
            // `isVisible(parent)` is whether the parent is on screen to be expanded in the first place.
            // Without the second half, collapsing a grandparent would leave its grandchildren drawn --
            // the parent would still be expanded, and the walk would stop asking why the parent is not
            // on screen at all.
            visible = expanded.contains(node.parent) && isVisible(node.parent, known);
        }

        known.put(key, visible);
        return visible;
    }

    // ------------------------------------------------------------------
    // Expanding
    // ------------------------------------------------------------------

    /**
     * Whether this node's children are being shown. False for a leaf, which has none.
     *
     * <p>Refuses a key that was never declared, rather than answering false. The two are
     * indistinguishable to a caller and mean completely different things: one is a closed group, the
     * other is a typo in a key name, and a typo answered with false is a row that silently never
     * appears.
     */
    public boolean isExpanded(K key) {
        return expanded.contains(node(key).key);
    }

    /** Whether this node has children at all. The question a caller asks before drawing a chevron. */
    public boolean isCollapsible(K key) {
        return !node(key).childKeys.isEmpty();
    }

    /** Sets a node's expanded state. Setting a leaf has no effect, because a leaf shows nothing. */
    public void setExpanded(K key, boolean value) {
        Node<K> node = node(key);
        if (node.childKeys.isEmpty()) {
            return;
        }
        if (value) {
            expanded.add(node.key);
        } else {
            expanded.remove(node.key);
        }
    }

    /**
     * Flips a node's expanded state, and reports whether anything changed.
     *
     * <p>False for a leaf rather than true-but-nothing-happened, so a caller that routes a click on
     * any row through here can tell a real collapse from a click on a heading with nothing under it.
     * That distinction is what stops a screen marking a content rebuild as needed on every click.
     */
    public boolean toggle(K key) {
        Node<K> node = node(key);
        if (node.childKeys.isEmpty()) {
            return false;
        }
        if (!expanded.remove(node.key)) {
            expanded.add(node.key);
        }
        return true;
    }

    /**
     * Expands every ancestor of {@code key}, so that the node becomes a visible row.
     *
     * <p>The node itself is <b>not</b> expanded, and that is the useful reading: a caller revealing a
     * selected node wants its branch open, not its own children spilled out underneath it. Expanding
     * the node as well would open a subtree nobody asked about every time a selection moved.
     */
    public Outline<K> expandAncestors(K key) {
        K at = node(key).parent;
        while (at != null) {
            expanded.add(at);
            at = nodes.get(at).parent;
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Every key, in declaration order, whether or not it is visible. */
    public List<K> keys() {
        return List.copyOf(nodes.keySet());
    }

    /** The keys declared directly under {@code parentOrNull}. Null means the roots. */
    public List<K> children(K parentOrNull) {
        if (parentOrNull == null) {
            List<K> out = new ArrayList<>();
            for (Node<K> node : nodes.values()) {
                if (node.parent == null) {
                    out.add(node.key);
                }
            }
            return List.copyOf(out);
        }
        return List.copyOf(node(parentOrNull).childKeys);
    }

    /** The parent of a node, or empty for a root. */
    public Optional<K> parentOf(K key) {
        return Optional.ofNullable(node(key).parent);
    }

    /** How far in a node sits. A root is zero, a child of a root is one. */
    public int depth(K key) {
        return node(key).depth;
    }

    /** Whether a node with this key was declared. */
    public boolean contains(K key) {
        return nodes.containsKey(key);
    }

    /** How many nodes there are, visible or not. */
    public int size() {
        return nodes.size();
    }

    /** Whether nothing was declared. */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** Every node, as an unmodifiable view. Drawn by nothing; for a caller that needs to walk it. */
    public Set<K> declared() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    private Node<K> node(K key) {
        Objects.requireNonNull(key, "key");
        Node<K> found = nodes.get(key);
        if (found == null) {
            throw new IllegalArgumentException("no node was declared with the key " + key
                    + "; the outline holds " + nodes.size() + " node(s)");
        }
        return found;
    }

    @Override
    public String toString() {
        return "Outline(" + nodes.size() + " node(s), " + expanded.size() + " expanded, "
                + visibleRowCount() + " visible)";
    }
}

package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outline: which rows are visible, how deep they are, and what a toggle does.
 *
 * <h2>Why every case is asserted on keys rather than on a count</h2>
 *
 * <p>Because a count is the assertion that passes for the wrong reason. "Collapsing a group leaves
 * three rows" is true of a tree where the group's row was dropped and a stranger's was picked up, and
 * the whole point of this class is that the row set is <i>derived</i> — so a test that checks the size
 * of the derivation rather than its contents is checking the part that was never in doubt.
 *
 * <p>So {@link #rows} is used everywhere, and it compares the list.
 */
class OutlineTest {

    /** A three-level tree: group → chapter → entry, which is the shape this exists for. */
    private static Outline<String> threeLevels() {
        return Outline.<String>of()
                .add("group", null, true)
                .add("chapter", "group", true)
                .add("entry", "chapter", true);
    }

    private static List<String> rows(Outline<String> outline) {
        return outline.visibleRows();
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("building")
    class Building {

        @Test
        @DisplayName("a child declared before its parent is refused, and the message names both")
        void aParentMustExistFirst() {
            // The alternative -- accept it and work out the depth later -- turns a discovery loop that
            // got its order wrong into an outline where everything is at depth zero, which draws as a
            // flat list with no error anywhere. Naming both keys is what makes the loop obvious.
            Outline<String> outline = Outline.<String>of().add("group", null, true);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> outline.add("chapter", "nowhere", true));

            assertTrue(thrown.getMessage().contains("chapter"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("nowhere"), thrown.getMessage());
        }

        @Test
        @DisplayName("a duplicate key is refused rather than quietly replacing the first")
        void keysAreUnique() {
            // A replacement would lose the first node's children while leaving them pointing at a
            // parent that no longer describes them -- so the outline would draw rows under a heading
            // whose collapsed state is somebody else's.
            Outline<String> outline = Outline.<String>of().add("group", null, true);

            assertThrows(IllegalArgumentException.class, () -> outline.add("group", null, false));
            assertEquals(1, outline.size(), "and the first declaration is untouched");
        }

        @Test
        @DisplayName("a null key is refused")
        void aNullKeyIsRefused() {
            // A null key cannot be looked up, so a node declared under one is invisible to every
            // caller -- including the one that would want to remove it.
            assertThrows(NullPointerException.class, () -> Outline.<String>of().add(null, null, true));
        }

        @Test
        @DisplayName("depth counts from the root, and does not drift when a grandchild comes first")
        void depthIsDerivedFromTheParentChain() {
            // The point of computing depth once, when a node is added, rather than climbing on every
            // frame. This tree is declared child-before-sibling at the third level, which is exactly
            // the order a discovery walk can produce -- and a lazily computed depth would give
            // "second_node" the wrong one if it were derived from the previous node's.
            Outline<String> outline = Outline.<String>of()
                    .add("group", null, true)
                    .add("chapter", "group", true)
                    .add("first_node", "chapter", true)
                    .add("second_node", "chapter", true)
                    .add("other_chapter", "group", false);

            assertEquals(0, outline.depth("group"));
            assertEquals(1, outline.depth("chapter"));
            assertEquals(2, outline.depth("first_node"));
            assertEquals(2, outline.depth("second_node"), "the sibling is not deeper than the first");
            assertEquals(1, outline.depth("other_chapter"), "and a later chapter is not deeper either");
        }

        @Test
        @DisplayName("a question about a key that was never declared is refused, not answered with zero")
        void unknownKeysAreRefused() {
            Outline<String> outline = threeLevels();

            // Negative answers to these would be indistinguishable from a real declaration with a false
            // value, which is how a typo in a key name becomes a row that never appears and no message.
            assertThrows(IllegalArgumentException.class, () -> outline.depth("nope"));
            assertThrows(IllegalArgumentException.class, () -> outline.isExpanded("nope"));
            assertThrows(IllegalArgumentException.class, () -> outline.isCollapsible("nope"));
            assertFalse(outline.contains("nope"), "contains is the one that answers a question, not a fact");
        }
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("visibility")
    class Visibility {

        @Test
        @DisplayName("an unseeded tree shows its roots and nothing else")
        void nothingIsExpandedUntilSeeded() {
            // Not an accident of construction: building declares a shape, and seeding decides what is
            // shown. Making them one step is what made the visible set depend on the order nodes were
            // added in.
            Outline<String> outline = threeLevels();

            assertEquals(List.of("group"), rows(outline));
        }

        @Test
        @DisplayName("seeding honours what each node declared")
        void seedFromDefaultsHonoursWhatWasDeclared() {
            Outline<String> outline = Outline.<String>of()
                    .add("open_group", null, true)
                    .add("open_chapter", "open_group", true)
                    .add("shown", "open_chapter", false)
                    .add("closed_group", null, false)
                    .add("hidden_chapter", "closed_group", true);

            outline.seedFromDefaults();

            assertEquals(List.of("open_group", "open_chapter", "shown", "closed_group"), rows(outline));
        }

        @Test
        @DisplayName("rows come back in declaration order, not in key order")
        void declarationOrderIsTheOrder() {
            // The property that lets the class serve a hand-authored tree at all. A sorted list would
            // silently reorder a book whose author wrote the chapters in an order, and it would do it
            // without any code looking wrong.
            Outline<String> outline = Outline.<String>of()
                    .add("zebra", null, false)
                    .add("alpha", null, false)
                    .add("middle", null, false);
            outline.seedFromDefaults();

            assertEquals(List.of("zebra", "alpha", "middle"), rows(outline));
        }

        @Test
        @DisplayName("collapsing a group hides its chapters but keeps the group's own row")
        void collapsingAGroupKeepsItsRow() {
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();

            outline.setExpanded("group", false);

            // The row stays because it carries the collapsed marker. Dropping it would leave a branch
            // with nothing to point back at, and no way to open it again.
            assertEquals(List.of("group"), rows(outline));
            assertTrue(outline.isCollapsible("group"), "and it is still the thing that can be reopened");
        }

        @Test
        @DisplayName("collapsing a chapter hides its entries and leaves its sibling chapter alone")
        void collapsingAChapterIsLocal() {
            Outline<String> outline = Outline.<String>of()
                    .add("group", null, true)
                    .add("one", "group", true)
                    .add("entry", "one", true)
                    .add("two", "group", true)
                    .add("other", "two", true);
            outline.seedFromDefaults();

            outline.setExpanded("one", false);

            assertEquals(List.of("group", "one", "two", "other"), rows(outline));
        }

        @Test
        @DisplayName("collapsing a grandparent hides its grandchildren, not just its children")
        void collapsingAnAncestorHidesEverythingUnderIt() {
            // The case that needs both halves of the visibility test. Checking only the immediate
            // parent's state would leave the grandchild drawn here, because its parent is still
            // expanded -- the parent is simply not on screen.
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();

            outline.setExpanded("group", false);

            assertEquals(List.of("group"), rows(outline));
        }

        @Test
        @DisplayName("a node with no children has no expanded state to set")
        void leavesCannotBeToggled() {
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();

            outline.setExpanded("entry", false);

            // A leaf draws nothing underneath it either way, so accepting the toggle would invent a
            // state that has no effect and that `isExpanded` would then report as if it mattered.
            assertFalse(outline.isExpanded("entry"), "a leaf's expanded state is always false");
            assertFalse(outline.isCollapsible("entry"));
            assertTrue(outline.isCollapsible("chapter"));
        }

        @Test
        @DisplayName("visibleRowCount agrees with visibleRows")
        void theCountIsTheList() {
            // Two methods over one derivation, so a caller that only needs the number does not build
            // the list -- and a test that they agree is what stops the cheap one drifting from the
            // expensive one.
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();

            assertEquals(rows(outline).size(), outline.visibleRowCount());
            outline.setExpanded("group", false);
            assertEquals(rows(outline).size(), outline.visibleRowCount());
        }

        @Test
        @DisplayName("an empty outline has no rows, no keys and no depth to report")
        void anEmptyOutlineIsHarmless() {
            Outline<String> outline = Outline.of();

            assertTrue(outline.isEmpty());
            assertEquals(0, outline.size());
            assertEquals(List.of(), rows(outline));
            assertEquals(List.of(), outline.keys());
        }
    }

    // ------------------------------------------------------------------
    // Toggling
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("toggling")
    class Toggling {

        @Test
        @DisplayName("a toggle on a leaf reports that nothing happened")
        void togglingALeafReportsNothingChanged() {
            // False rather than true-but-nothing-happened, so a screen that routes every row's click
            // through here can tell a real collapse from a click on a heading with nothing under it.
            // That is the difference between marking a rebuild as needed and not.
            Outline<String> outline = threeLevels();

            assertFalse(outline.toggle("entry"), "a leaf has nothing to show or hide");
            assertTrue(outline.toggle("chapter"), "a node with children does");
        }

        @Test
        @DisplayName("toggling twice returns to where it started")
        void togglingIsReversible() {
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();
            List<String> before = rows(outline);

            outline.toggle("group");
            assertEquals(List.of("group"), rows(outline));

            outline.toggle("group");
            assertEquals(before, rows(outline), "and a round trip is exactly a round trip");
        }

        @Test
        @DisplayName("seeding again discards whatever the player toggled")
        void seedingIsForTheFirstSightOfATree() {
            // Which is the point of it: this is what a client calls when a *new* tree arrives, and a
            // caller that ran it per frame would undo every toggle as it was made. Asserting the
            // discard is asserting the intended use rather than a side effect.
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();
            outline.setExpanded("group", false);
            assertEquals(List.of("group"), rows(outline));

            outline.seedFromDefaults();

            assertEquals(List.of("group", "chapter", "entry"), rows(outline));
        }
    }

    // ------------------------------------------------------------------
    // Revealing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("expanding ancestors")
    class ExpandingAncestors {

        @Test
        @DisplayName("a node three levels down becomes visible, and is not itself expanded")
        void ancestorsAreOpenedButNotTheNode() {
            // The node itself is deliberately left alone: a caller revealing a selection wants its
            // branch open, not its own children spilled out underneath it. Expanding it as well would
            // open a subtree nobody asked about every time a selection moved.
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();
            outline.setExpanded("group", false);
            outline.setExpanded("chapter", false);
            assertEquals(List.of("group"), rows(outline));

            outline.expandAncestors("entry");

            assertEquals(List.of("group", "chapter", "entry"), rows(outline));
            assertFalse(outline.isExpanded("entry"), "the node's own children are still hidden");
            assertTrue(outline.isExpanded("chapter"), "but its ancestors are open");
        }

        @Test
        @DisplayName("revealing a root opens nothing, because a root is never gated")
        void aRootHasNoAncestors() {
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();
            outline.setExpanded("group", false);
            outline.setExpanded("chapter", false);

            outline.expandAncestors("group");

            assertEquals(List.of("group"), rows(outline), "a root is always drawn, so this changes nothing");
        }
    }

    // ------------------------------------------------------------------
    // Reading the shape
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("shape")
    class Shape {

        @Test
        @DisplayName("children of null are the roots, in declaration order")
        void rootsAreTheChildrenOfNull() {
            Outline<String> outline = Outline.<String>of()
                    .add("b_group", null, true)
                    .add("a_group", null, true)
                    .add("chapter", "b_group", true);

            assertEquals(List.of("b_group", "a_group"), outline.children(null));
            assertEquals(List.of("chapter"), outline.children("b_group"));
            assertEquals(List.of(), outline.children("a_group"));
        }

        @Test
        @DisplayName("a root has no parent; a child has one")
        void parentOfAnswersForBoth() {
            Outline<String> outline = threeLevels();

            assertTrue(outline.parentOf("group").isEmpty());
            assertEquals("group", outline.parentOf("chapter").orElseThrow());
            assertEquals("chapter", outline.parentOf("entry").orElseThrow());
        }

        @Test
        @DisplayName("keys are every node, visible or not, in declaration order")
        void keysIncludeWhatIsHidden() {
            Outline<String> outline = threeLevels();
            outline.seedFromDefaults();
            outline.setExpanded("group", false);

            // `keys` is for a caller walking the whole tree -- resolving a selection, say -- so it is
            // deliberately not `visibleRows`. Asserting both makes the difference explicit rather than
            // a thing a reader has to notice.
            assertEquals(List.of("group", "chapter", "entry"), outline.keys());
            assertEquals(List.of("group"), rows(outline));
        }

        @Test
        @DisplayName("declared() is the same set, and cannot be mutated through")
        void declaredIsAReadOnlyView() {
            Outline<String> outline = threeLevels();

            assertEquals(List.of("group", "chapter", "entry"), List.copyOf(outline.declared()));
            assertThrows(UnsupportedOperationException.class, () -> outline.declared().remove("group"));
        }
    }
}

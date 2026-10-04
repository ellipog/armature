package dev.ellipog.armature.client.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token registry, and the one property everything else depends on: that its order is the record's.
 *
 * <h2>Why this file is the load-bearing test of the whole theme feature</h2>
 *
 * <p>{@link ThemeToken} names the thirty-three top-level colours of {@link Theme} and then its ten control
 * colours, and {@code Theme.from} rebuilds a theme by walking that same order. That is what makes a patch
 * a map of names to colours with one merge implementation instead of forty branches — and it is also a
 * <b>silent</b> failure if the two ever come apart. A reordered list does not fail to compile; it swaps
 * two colours, and the result is a theme that looks slightly wrong rather than a build that stops.
 *
 * <p>So the pairing is checked mechanically: for every token, the method on {@code Theme} with that
 * token's name is invoked on a known theme and compared against that token's slot in
 * {@code allColours()}. Reorder either side without the other and this fails, naming the token. There is
 * no way to write that check by hand for forty-three values that stays correct when the forty-fourth is
 * added — which is exactly why it is written with reflection.
 *
 * <h2>The second property: a name a patch or a file uses must be a colour that exists</h2>
 *
 * <p>Ids arrive from JSON keys, from a server, and from an editor's text field. A name that is not a
 * token is a value that silently never applies — this project has recorded that failure three times —
 * so {@link #everyIdIsUnique} and {@link #everyIdRoundTrips} make it impossible for the registry to
 * contain a duplicate or an unlookupable entry.
 */
@DisplayName("Theme tokens")
class ThemeTokenTest {

    // ------------------------------------------------------------------
    // The registry and the record agree
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every token's name is a method on Theme, and its index is that method's value")
    void everyTokenMatchesItsComponent() throws Exception {
        // The central assertion. `allColours()` is built by hand-positional code, which is the one place
        // in the class that restates the field order -- so this compares three things at once: the token
        // list, the accessor names, and the array.
        int[] values = Themes.MODERN.allColours();
        assertEquals(ThemeToken.ALL.size(), values.length,
                "allColours() and the token registry disagree on how many colours a theme has");

        for (ThemeToken token : ThemeToken.ALL) {
            int index = token.index();
            assertEquals(index, ThemeToken.indexOf(token.id()),
                    token.id() + "'s index() and indexOf() disagree, so indexOf is not the inverse of id");

            int actual = values[index];
            int fromMethod = readThroughAccessor(token, values);

            assertEquals(actual, fromMethod,
                    "the token '" + token.id() + "' is at slot " + index + " of allColours(), which is "
                            + hex(actual) + ", but the method '" + token.id() + "()' returns " + hex(fromMethod)
                            + ". Either the token list or the record's field order has been changed without"
                            + " the other, so some other colour has moved to make room.");
        }
    }

    /**
     * Reads one token's value through the accessor named after it.
     *
     * <p>Top-level tokens are methods on {@code Theme}; control tokens are methods on
     * {@code Theme.Controls}, which is the same split {@code allColours()} makes. A token with no method
     * at all fails here rather than being skipped, which is the point: a token that names nothing is a
     * key an author could set with no effect.
     */
    private static int readThroughAccessor(ThemeToken token, int[] values) throws Exception {
        Class<?> owner = token.index() < ThemeToken.CONTROL_START ? Theme.class : Theme.Controls.class;
        Object target = owner == Theme.class ? Themes.MODERN : Themes.MODERN.controls();

        Method method;
        try {
            method = owner.getMethod(token.id());
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError("the token '" + token.id() + "' names no accessor on "
                    + owner.getSimpleName() + ", so a theme file could set a colour nothing reads. The"
                    + " id has to be the component's own name -- that is what makes this test possible.", e);
        }
        assertEquals(int.class, method.getReturnType(), token.id() + "() should return a colour");
        assertEquals(0, method.getParameterCount(), token.id() + "() should take nothing");
        return (int) method.invoke(target);
    }

    // ------------------------------------------------------------------
    // The registry's own shape
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every id is unique, so no two colours can share a key")
    void everyIdIsUnique() {
        Set<String> seen = new HashSet<>();
        for (ThemeToken token : ThemeToken.ALL) {
            assertTrue(seen.add(token.id()),
                    "'" + token.id() + "' is in the registry twice, so a patch naming it would set one of"
                            + " them and a file naming it would set the other");
        }
    }

    @Test
    @DisplayName("every id resolves back to its own token, whatever its capitalisation")
    void everyIdRoundTrips() {
        for (ThemeToken token : ThemeToken.ALL) {
            assertSame(token, ThemeToken.byId(token.id()));
            assertSame(token, ThemeToken.byId(token.id().toUpperCase(java.util.Locale.ROOT)),
                    "ids come from JSON keys and from a text field, so the lookup is case-insensitive");
            assertSame(token, ThemeToken.byId("  " + token.id() + "  "),
                    "a hand-edited file has spaces in it");
        }
    }

    @Test
    @DisplayName("an id that is not a token resolves to nothing, and surrounding space is ignored")
    void unknownIdsResolveToNothing() {
        // Null rather than a default, for the reason `Themes.byName` gives: a name arriving from a file
        // has a caller that can report it, and a fallback would make a misspelling look like it worked.
        assertNull(ThemeToken.byId("panell"));
        assertNull(ThemeToken.byId(""));
        assertNull(ThemeToken.byId(null));
        assertEquals(-1, ThemeToken.indexOf("panell"));
        assertTrue(ThemeToken.exists("nodeEdgeBlocked"));

        // Space around an id is ignored, in both directions, and this is asserted rather than assumed
        // because it is a decision with a cost. Ids come from JSON keys and from a text field, where a
        // trailing space is easy to type and invisible -- so a theme file with `"panel ": "#26212E"` sets
        // the panel rather than being reported as an unknown colour. The cost is that a genuinely
        // misspelled id with a space in the middle of it is still refused, which is the case worth
        // refusing.
        assertTrue(ThemeToken.exists("nodeEdgeBlocked "), "trailing space should be trimmed");
        assertTrue(ThemeToken.exists(" nodeEdgeBlocked"), "and leading space");
        assertSame(ThemeToken.byId("panel"), ThemeToken.byId("  PANEL  "));
    }

    @Test
    @DisplayName("the two halves are split where the record splits, not somewhere convenient")
    void theControlBoundaryIsWhereTheRecordIs() {
        // `CONTROL_START` is the single number that says where `Theme`'s own components end and the
        // nested `Controls` record begins. `Theme.allColours()` asserts its top-level array against it
        // and `Theme.from` splits on it, so if it is wrong, everything is.
        assertEquals(33, ThemeToken.CONTROL_START);
        assertEquals(10, ThemeToken.CONTROLS.size());
        assertEquals(43, ThemeToken.ALL.size());

        assertTrue(ThemeToken.ALL.subList(0, ThemeToken.CONTROL_START).stream()
                        .noneMatch(t -> t.group() == ThemeToken.Group.CONTROL),
                "a control colour is in the top-level half, so Theme.from would look for it in the"
                        + " wrong array");
        assertTrue(ThemeToken.ALL.subList(ThemeToken.CONTROL_START, ThemeToken.ALL.size()).stream()
                        .allMatch(t -> t.group() == ThemeToken.Group.CONTROL),
                "a non-control colour is in the control half");
    }

    @Test
    @DisplayName("every token has a label and a group, because the editor is built from the list")
    void everyTokenIsUsableByAnEditor() {
        // The editor lists all forty-three and groups them by section. A token with an empty label would
        // be a blank row; a token with no group would be missing from every section and unreachable --
        // which is the failure mode of building a screen from a hand-written list, prevented here by
        // building it from this one.
        Set<ThemeToken.Group> groups = new LinkedHashSet<>();
        Set<String> labels = new LinkedHashSet<>();

        for (ThemeToken token : ThemeToken.ALL) {
            assertNotNull(token.group(), token.id() + " has no group, so no section would show it");
            assertNotNull(token.label(), token.id() + " has no label");
            assertTrue(!token.label().isBlank(), token.id() + " has a blank label");
            assertTrue(!token.id().isBlank(), "a token with no id cannot be set by anything");
            groups.add(token.group());
        }

        // Labels need not be unique across the whole registry -- `Fill` and `Fill, hovered` are in one
        // group and distinct, but two groups may legitimately both have a `Border` -- but two tokens in
        // one group sharing a label is a row a person cannot tell from the one above it.
        for (ThemeToken.Group group : groups) {
            labels.clear();
            for (ThemeToken token : ThemeToken.inGroup(group)) {
                assertTrue(labels.add(token.label()),
                        "two " + group + " tokens are both labelled '" + token.label()
                                + "', so the editor shows two identical rows");
            }
        }
    }

    @Test
    @DisplayName("inGroup returns every token of that group, in storage order, and no others")
    void groupsPartitionTheRegistry() {
        int total = 0;
        for (ThemeToken.Group group : ThemeToken.Group.values()) {
            List<ThemeToken> inGroup = ThemeToken.inGroup(group);
            total += inGroup.size();
            for (ThemeToken token : inGroup) {
                assertEquals(group, token.group());
            }
        }
        assertEquals(ThemeToken.ALL.size(), total,
                "the groups do not cover every token, so the editor would leave some out");

        // Storage order, not group order: the editor groups the list, but the merge walks it. If this
        // returned sorted-by-group, a patch's indices and the record's would disagree.
        List<ThemeToken> surfaces = ThemeToken.inGroup(ThemeToken.Group.SURFACE);
        assertEquals(List.of("dim", "canvas", "recessed", "panel", "raised", "panelEdge"),
                surfaces.stream().map(ThemeToken::id).toList(),
                "the surface tokens are the first six slots of the record, in that order");
    }

    @Test
    @DisplayName("ids() lists all of them, so a warning can tell an author what exists")
    void idsAreListed() {
        String listed = ThemeToken.ids();
        for (ThemeToken token : ThemeToken.ALL) {
            assertTrue(listed.contains(token.id()),
                    "ids() omits " + token.id() + ", so a message cannot tell an author it is available");
        }
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }
}

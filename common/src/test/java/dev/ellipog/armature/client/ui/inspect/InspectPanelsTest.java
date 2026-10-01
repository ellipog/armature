package dev.ellipog.armature.client.ui.inspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel registry: a type's own panel, the fallback for a type nobody wrote one for, and the answer
 * when there is neither.
 *
 * <h2>Why the unknown type gets this much attention</h2>
 *
 * <p>Because it is the case the panel exists for: the types a build knows were written for it, and the
 * types it does not know arrive anyway. A panel that renders an unknown type as nothing reads as a bug;
 * one that renders it as a summary of fields it invented is worse -- it looks like support. The fallback
 * showing the value itself is the only answer that cannot lie, and these tests hold it to that.
 */
@DisplayName("the inspector's per-type panels")
class InspectPanelsTest {

    /** The value the tests describe: a stand-in for "some object a panel knows how to read". */
    private static final Map<String, String> VALUE = Map.of("kind", "sample");

    @Test
    @DisplayName("a registered type gets its own panel's rows")
    void registeredTypesGetTheirPanel() {
        InspectPanels<Map<String, String>> panels = new InspectPanels<Map<String, String>>()
                .register("example:item", (type, value) -> List.of(
                        InspectRow.field("item", "Item", "minecraft:stone"),
                        InspectRow.field("count", "Count", "20")));

        assertTrue(panels.known("example:item"));
        List<InspectRow> rows = panels.rowsFor("example:item", VALUE);
        assertEquals(List.of("item", "count"), rows.stream().map(InspectRow::key).toList(),
                "the type's own rows, in the order its panel wrote them");
    }

    @Test
    @DisplayName("an unregistered type gets the fallback, which shows the value itself")
    void unknownTypesGetTheFallback() {
        InspectPanels<Map<String, String>> panels = new InspectPanels<Map<String, String>>()
                .register("example:item", (type, value) -> List.of(InspectRow.value("k", "Kind", "item")))
                .fallback((type, value) -> List.of(
                        InspectRow.warning("raw", "No panel for \"" + type + "\""),
                        InspectRow.raw("raw:json", type, value.toString())));

        assertFalse(panels.known("addon:custom"), "nobody wrote a panel for it");
        List<InspectRow> rows = panels.rowsFor("addon:custom", VALUE);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isWarning(), "the fallback opens in the voice that says something is wrong");
        assertEquals(VALUE.toString(), rows.get(1).value(),
                "the value itself, not a summary of fields the panel did not understand");
    }

    @Test
    @DisplayName("the fallback is the caller's format, not this class's guess at one")
    void theFallbackIsSupplied() {
        // Two callers with two formats: what "showing the value" means is theirs to say. This class only
        // guarantees that an unknown type is asked of the fallback and nothing is dropped on the way.
        InspectPanels<String> one = new InspectPanels<String>()
                .fallback((type, value) -> List.of(InspectRow.raw("raw", type, value.toUpperCase())));
        InspectPanels<String> two = new InspectPanels<String>()
                .fallback((type, value) -> List.of(InspectRow.raw("raw", type, value.trim())));

        assertEquals("LOUD", one.rowsFor("any", "loud").get(0).value());
        assertEquals("tight", two.rowsFor("any", "  tight  ").get(0).value());
    }

    @Test
    @DisplayName("with no fallback registered, an unknown type is told rather than pretended away")
    void noFallbackStillAnswers() {
        InspectPanels<String> panels = new InspectPanels<>();

        List<InspectRow> rows = panels.rowsFor("addon:custom", "value");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isWarning(), "the answer is the warning, not silence");
        assertTrue(rows.get(0).label().contains("addon:custom"), "and it names the type it could not show");
    }

    @Test
    @DisplayName("a null type is an unknown type, and the fallback is asked rather than tripped")
    void nullTypeIsUnknown() {
        InspectPanels<String> panels = new InspectPanels<String>()
                .fallback((type, value) -> List.of(InspectRow.raw("raw", "stored", value)));

        assertFalse(panels.known(null));
        assertEquals("value", panels.rowsFor(null, "value").get(0).value());
    }

    @Test
    @DisplayName("a fallback that answers nothing is an empty panel, not an exception")
    void anEmptyFallbackIsEmpty() {
        InspectPanels<String> panels = new InspectPanels<String>()
                .fallback((type, value) -> null);

        assertTrue(panels.rowsFor("addon:custom", "value").isEmpty(),
                "a fallback that declines yields nothing, which the caller draws as an empty panel");
    }

    @Test
    @DisplayName("re-registering a type replaces its panel, deliberately")
    void reRegisteringReplaces() {
        InspectPanels<String> panels = new InspectPanels<String>()
                .register("type", (type, value) -> List.of(InspectRow.value("first", "First", value)))
                .register("type", (type, value) -> List.of(InspectRow.value("second", "Second", value)));

        assertEquals(List.of("second"), panels.rowsFor("type", "x").stream()
                .map(InspectRow::key).toList(), "the last panel written for a type is its panel");
    }
}

package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A baseline of named counters, and which of them have moved since it was taken.
 *
 * <h2>What this is for, and why it is not a screen's business</h2>
 *
 * <p>Every screen in a mod with a server behind it has the same problem: the data it was built from can
 * change while it is open, and the widgets it built are placed once. So each screen ends up with its own
 * remembered revision and its own comparison — which is a small piece of arithmetic written out once per
 * screen, forgotten once per screen, and invisible when it is wrong.
 *
 * <p>This is that arithmetic, and only that: no Minecraft, no screen, no rebuild. A caller hands it the
 * numbers as they stand; it answers with the names that differ from the last time and takes the new
 * numbers as its baseline. {@code ArmatureScreen} is the caller that turns that answer into a rebuild.
 *
 * <h2>Why the baseline moves on every look rather than on a rebuild</h2>
 *
 * <p>Because the two are the same event seen from two sides. A caller that looked, decided to rebuild,
 * and then forgot to update its baseline would rebuild on every frame for as long as the data stayed
 * new — which is a screen that flickers and a redraw loop nothing in the game reports. Settling inside
 * the comparison makes that unrepresentable rather than merely discouraged.
 */
public final class Watch {

    private Map<String, Long> last = Map.of();

    /**
     * The names whose value differs from the last look, and {@code now} becomes the new baseline.
     *
     * <p>A name that was not in the last look counts as moved, which is what makes a source added to a
     * running client show up rather than being silently ignored.
     */
    public List<String> moved(Map<String, Long> now) {
        Objects.requireNonNull(now, "now");
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, Long> each : now.entrySet()) {
            if (!each.getValue().equals(last.get(each.getKey()))) {
                moved.add(each.getKey());
            }
        }
        settle(now);
        return List.copyOf(moved);
    }

    /**
     * Records {@code now} as the baseline, reporting nothing.
     *
     * <p>For a caller that has just <i>built</i> from these numbers: what it holds is current by
     * construction, so the next look has nothing to report. Without it the first frame after every
     * rebuild would find everything moved, which is a rebuild per frame.
     */
    public void settle(Map<String, Long> now) {
        last = Map.copyOf(Objects.requireNonNull(now, "now"));
    }

    /** How many names the baseline holds, for a caller asserting that it is watching anything at all. */
    public int size() {
        return last.size();
    }
}

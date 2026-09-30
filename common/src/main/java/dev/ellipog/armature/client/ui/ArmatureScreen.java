package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.client.ui.kit.Watch;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * A screen that keeps its widgets current: extend this instead of {@code Screen} and a panel updates
 * itself when the data behind it moves.
 *
 * <h2>What a subclass gets, and what it has to do for it</h2>
 *
 * <p>Nothing beyond the one word in its declaration. Register the things that change in
 * {@link ArmatureLive} — once, where the data lives — and every screen built on this class notices them,
 * including sources registered by code written later. There is no revision field to add, no comparison
 * to write, and nothing to remember: that is the entire point, because the panel somebody forgets to
 * wire is the panel that stays stale for a month.
 *
 * <h2>Why the check is in the frame and not on the message</h2>
 *
 * <p>Because a message handler runs on whichever thread received it, at whatever moment, possibly while
 * this screen is half-drawn — and a widget list rebuilt from a network thread is a crash rather than a
 * redraw. The frame loop is the one place that is always the right thread and never in the middle of
 * anything. The cost is a comparison of a few numbers per frame, against a supplier per watched source.
 *
 * <h2>Where the player was looking</h2>
 *
 * <p>Kept, and not by this class. A rebuild recreates widgets, but the things that make up "my place" —
 * a {@code ScrollView}'s offset, a canvas's pan and zoom — live in the screen's own fields and in the
 * kit's own objects, which a rebuild does not touch; {@code ScrollView.apply} clamps the offset it
 * already holds against the new layout rather than resetting it. So a quest completing under a
 * scrolled-down reader redraws the row and leaves the column where it was.
 *
 * <p>Worth stating because it is the property that makes an automatic rebuild acceptable at all: a
 * refresh that threw the reader back to the top of a long chapter would be worse than the staleness it
 * fixed, and the next screen to add a scroll of its own needs to know that its offset is already safe.
 */
public abstract class ArmatureScreen extends Screen {

    private final Watch watch = new Watch();
    private boolean built = false;

    protected ArmatureScreen(Component title) {
        super(title);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshIfMoved();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void refreshIfMoved() {
        Map<String, Long> now = ArmatureLive.revisions();

        // The first frame after this screen was built -- including the build a rebuild itself performs.
        // What it holds came from these numbers a moment ago, so nothing has moved; recording that is
        // what stops the first frame of every screen from rebuilding for nothing.
        if (!built) {
            watch.settle(now);
            built = true;
            return;
        }

        List<String> moved = watch.moved(now);
        if (moved.isEmpty()) {
            return;
        }

        // Debug rather than info: this fires whenever a panel follows its data, which is the normal
        // case. It is here because the failure this mechanism can have is a screen that rebuilds on
        // every frame -- a source that does not settle, or a rebuild that moves a value the rebuild
        // itself reads -- and that is invisible except as a flicker.
        dev.ellipog.armature.Constants.LOG.debug("armature: {} moved; {} is rebuilding", moved, title.getString());

        rebuildWidgets();
    }
}

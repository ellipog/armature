package dev.ellipog.armature.client.render;

import java.util.function.Consumer;

/**
 * Who owns the open fill batch, and how deep its regions are nested — the bookkeeping half of
 * {@link GuiGraphicsRenderer#batched}, kept apart from the drawing so it can be tested without a client.
 *
 * <p>Regions on one owner nest by depth and share one batch. A region on a <i>different</i> owner inside
 * an open one submits the outer run, batches on its own, and on the way out hands the outer region back
 * its owner and depth. The renderer used to reset the depth there and never restore it, so the outer
 * region's exit took it to -1, no region ever reached 0 again, and every fill after that drew unbatched
 * for the rest of the session: no wrong pixel, just the batching quietly gone.
 *
 * @param <T> the owner, an extractor in the game and anything at all in a test
 */
final class BatchNesting<T> {

    /** What {@link #enter} found, for {@link #exit} to put back. */
    record Entry<T>(T outerOwner, int outerDepth, boolean fresh) {
    }

    private T owner;
    private int depth;

    /** Whether a fill on this owner joins the open batch. */
    boolean batching(T who) {
        return depth > 0 && owner == who;
    }

    T owner() {
        return owner;
    }

    int depth() {
        return depth;
    }

    /**
     * Opens a region on {@code who}. A region open on another owner is handed to {@code flush} first.
     * The entry's {@code fresh} says a new batch starts here, which the caller empties.
     */
    Entry<T> enter(T who, Consumer<T> flush) {
        T outerOwner = null;
        int outerDepth = 0;
        if (depth > 0 && owner != who) {
            flush.accept(owner);
            outerOwner = owner;
            outerDepth = depth;
            depth = 0;
        }
        boolean fresh = depth == 0;
        if (fresh) {
            owner = who;
        }
        depth++;
        return new Entry<>(outerOwner, outerDepth, fresh);
    }

    /** Closes a region. The outermost one on its owner hands that owner to {@code flush}, then restores. */
    void exit(Entry<T> entry, Consumer<T> flush) {
        depth--;
        if (depth == 0) {
            flush.accept(owner);
            owner = entry.outerOwner();
            depth = entry.outerDepth();
        }
    }
}

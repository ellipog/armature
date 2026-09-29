package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link GuiRenderer} that records what it was asked to draw instead of drawing it.
 *
 * <h2>Why this is the second implementation, and why that matters more than it looks</h2>
 *
 * <p>The plan's reason for scheduling the seam last was fair and worth answering directly: <i>"an
 * interface wrapping one implementation is an indirection nobody can evaluate"</i>. A seam whose only
 * implementation forwards to the thing it abstracts is a layer of indirection with nothing on the
 * other side, and nothing about it can be tested — you cannot tell a correct forwarding from a wrong
 * one without a client, which is the situation the seam was supposed to improve.
 *
 * <p>This is the answer. It is not a renderer; it is a <b>reader</b>. Every call it receives is
 * recorded as a value, so a test can ask what would have been drawn without a window, a font or an
 * item registry — and the questions that become askable are the ones that were previously only
 * answerable by looking at a screenshot:
 *
 * <ul>
 *   <li>Does a hovered node draw an outline that follows its shape rather than a box around it?</li>
 *   <li>Is a label's backdrop behind the label, and is the label inside the node it names?</li>
 *   <li>Does an outline's four fills actually enclose the rectangle they claim to?</li>
 *   <li>Is a clip opened and closed exactly once, even on the path that returns early?</li>
 * </ul>
 *
 * <p>That is what turns this from a deferral into work the tests hold. It also makes the seam
 * itself evaluable: a forwarding bug — {@code fill} passing its arguments in the wrong order, say —
 * is a failing assertion rather than a picture somebody has to notice.
 *
 * <h2>What it deliberately does not simulate</h2>
 *
 * <p>No font metrics and no item rendering. {@link #textWidth} returns a fixed width per character and
 * {@link #icon} always reports success, because a test that needs real metrics is a test that needs a
 * client and there is no point pretending otherwise. What it records is the <i>sequence and geometry
 * of calls</i>, which is the part that is about this code rather than about Minecraft's renderer.
 *
 * <p>{@link #withCharWidth} exists so a caller can reason about wrapping and truncation with
 * arithmetic it chose, the same way {@code Measure.monospace} works for the layout tests.
 */
public final class RecordingRenderer implements GuiRenderer {

    /**
     * One recorded call.
     *
     * <p>A record with an operation name rather than a class per operation: the assertions read
     * {@code fills()} and {@code texts()}, and a test that wants "was this label drawn over that
     * rectangle" compares numbers from two lists. Six types would be six visitors.
     */
    public record Call(Op op, int x, int y, int x2, int y2, int argb, String text) {

        /** Whether this call is a filled rectangle covering the given point. */
        public boolean covers(int px, int py) {
            return op == Op.FILL && px >= x && px < x2 && py >= y && py < y2;
        }

        @Override
        public String toString() {
            return switch (op) {
                case FILL -> "fill(" + x + "," + y + " -> " + x2 + "," + y2 + ", " + hex(argb) + ")";
                case TEXT -> "text(\"" + text + "\" at " + x + "," + y + ", " + hex(argb) + ")";
                case ICON -> "icon(" + x + "," + y + " " + x2 + "px)";
                case CLIP -> "clip(" + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case UNCLIP -> "unclip";
            };
        }

        private static String hex(int argb) {
            return String.format("#%08X", argb);
        }
    }

    /** What a recorded call was. */
    public enum Op { FILL, TEXT, ICON, CLIP, UNCLIP }

    private final List<Call> calls = new ArrayList<>();
    private final int charWidth;
    private final int lineHeight;
    private final boolean iconsDraw;

    private int openClips;
    private int deepestClip;
    private int clippedAfterStop;

    private RecordingRenderer(int charWidth, int lineHeight, boolean iconsDraw) {
        this.charWidth = charWidth;
        this.lineHeight = lineHeight;
        this.iconsDraw = iconsDraw;
    }

    /**
     * A recorder with the given metrics. Six pixels a character, ten a line, icons drawing — the same
     * stand-in the kit's own layout tests use, so arithmetic in a test is checkable by hand.
     */
    public static RecordingRenderer create() {
        return new RecordingRenderer(6, 10, true);
    }

    /** A recorder with a chosen character width, for a test that needs a label to fit or not. */
    public static RecordingRenderer withCharWidth(int charWidth) {
        return new RecordingRenderer(charWidth, 10, true);
    }

    /** A recorder whose {@link #icon} reports that it drew nothing, for the fallback path. */
    public static RecordingRenderer withoutIcons() {
        return new RecordingRenderer(6, 10, false);
    }

    // ------------------------------------------------------------------
    // GuiRenderer
    // ------------------------------------------------------------------

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        calls.add(new Call(Op.FILL, left, top, right, bottom, argb, ""));
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        calls.add(new Call(Op.TEXT, x, y, 0, 0, argb, text));
    }

    @Override
    public int textWidth(String text) {
        return text == null ? 0 : text.length() * charWidth;
    }

    @Override
    public int lineHeight() {
        return lineHeight;
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        calls.add(new Call(Op.ICON, boxX, boxY, box, box, 0, ""));
        return iconsDraw;
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        calls.add(new Call(Op.CLIP, left, top, right, bottom, 0, ""));
        openClips++;
        deepestClip = Math.max(deepestClip, openClips);

        // An anonymous class rather than a lambda, and the reason is a contract rather than a style:
        // the seam says closing twice is a no-op, and a lambda cannot remember that it has been
        // called. My first version returned a lambda, and the assertion below failed -- correctly,
        // because the recorder was not modelling the thing it exists to model.
        //
        // That is worth keeping as a note: a test double that does not implement the contract being
        // tested does not report a wrong contract, it reports the double. The failure pointed at the
        // recorder, which is where the bug was.
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                calls.add(new Call(Op.UNCLIP, 0, 0, 0, 0, 0, ""));
                openClips--;
                if (openClips < 0) {
                    // A pop with nothing pushed. Counted rather than thrown, so a test can assert that
                    // it did not happen without the failure arriving as an exception from inside a
                    // recorder.
                    clippedAfterStop++;
                    openClips = 0;
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Reading it back
    // ------------------------------------------------------------------

    /** Every call, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The filled rectangles, in order. */
    public List<Call> fills() {
        return calls.stream().filter(call -> call.op() == Op.FILL).toList();
    }

    /** The text, in order. */
    public List<Call> texts() {
        return calls.stream().filter(call -> call.op() == Op.TEXT).toList();
    }

    /** The icons, in order. */
    public List<Call> icons() {
        return calls.stream().filter(call -> call.op() == Op.ICON).toList();
    }

    /** The clips, in order. */
    public List<Call> clips() {
        return calls.stream().filter(call -> call.op() == Op.CLIP).toList();
    }

    /**
     * Whether every clip was closed exactly once.
     *
     * <p>The assertion worth having on this class, and the reason a scoped clip is the seam's shape
     * rather than a push/pop pair: a leaked clip does not fail loudly, it leaves every later draw in
     * the frame clipped to a rectangle nobody chose. Reading it back off a recorder is the only way to
     * check it without a client.
     */
    public boolean clipsBalanced() {
        return openClips == 0 && clippedAfterStop == 0;
    }

    /** How many clips were still open when this recorder was last read. */
    public int unclosedClips() {
        return openClips;
    }

    /** How many pops happened with nothing pushed. */
    public int strayPops() {
        return clippedAfterStop;
    }

    /** How deep the clip nesting went, so a test can tell nesting from replacing. */
    public int deepestClip() {
        return deepestClip;
    }

    /** Whether any text was drawn with the given string. */
    public boolean drewText(String text) {
        return calls.stream().anyMatch(call -> call.op() == Op.TEXT && text.equals(call.text()));
    }

    /** Whether a filled rectangle covers this point. */
    public boolean covered(int x, int y) {
        return calls.stream().anyMatch(call -> call.covers(x, y));
    }

    /** The text drawn with the given string, or null. The first, if it was drawn more than once. */
    public Call callFor(String text) {
        return calls.stream().filter(call -> call.op() == Op.TEXT && text.equals(call.text()))
                .findFirst().orElse(null);
    }

    /** Forgets everything recorded, so one recorder can serve several assertions. */
    public void reset() {
        calls.clear();
        openClips = 0;
        deepestClip = 0;
        clippedAfterStop = 0;
    }

    @Override
    public String toString() {
        return "RecordingRenderer(" + calls.size() + " call(s))";
    }
}

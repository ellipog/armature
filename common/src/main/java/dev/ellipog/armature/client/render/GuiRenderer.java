package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.world.item.ItemStack;

/**
 * The only thing in either mod that draws. Every rectangle, every label and every icon goes through
 * here.
 *
 * <h2>What this is for, in one paragraph</h2>
 *
 * <p>26.1 renames the drawing context and changes how a screen is asked to draw:
 * {@code GuiGraphics} becomes {@code GuiGraphicsExtractor}, {@code Screen#render} becomes
 * {@code Screen#extractRenderState}, {@code renderBackground} becomes {@code Screen#extractBackground}.
 * With this seam that port is <b>one file</b> — {@link GuiGraphicsRenderer} — and both mods compile
 * unchanged. Without it, it is every draw call in a toolkit and a screen, twice, with no way to tell
 * which were missed.
 *
 * <h2>Six methods, because that is what was actually being used</h2>
 *
 * <p>Not a guess at what a UI toolkit might need. This is the complete set of operations that
 * {@code ArmatureTheme}, {@code ArmatureButton}, {@code ScrollView} and {@code QuestBookScreen}
 * between them called on {@code GuiGraphics}, measured rather than recalled:
 * {@code fill}, {@code drawString}, {@code drawCenteredString}, {@code pose} + {@code renderItem},
 * and the scissor pair. Thirty-odd fills, thirty-odd strings, one centred string, two icons and two
 * scissors.
 *
 * <h2>What is deliberately not here, and it is the most important decision in the file</h2>
 *
 * <p><b>No transform stack.</b> The obvious translation of {@code GuiGraphics} would expose
 * {@code pose()} and hand out a {@code PoseStack}, and it is what the two icon sites in this codebase
 * reach for — {@code renderItem} draws at a fixed 16 pixels, so scaling an item to fill a box means
 * changing the transform it inherits. Putting that on the seam would leak Blaze3D through it and make
 * the whole thing worthless for the port that is meant to be its justification: the next version's
 * renderer has its own way of doing 2D transforms, and every caller would still be pushing a matrix.
 *
 * <p>So the seam offers {@link #icon} — "draw this item in this box" — and the transform stops
 * existing anywhere outside the implementation. The two sites that needed one become one call, and
 * the knowledge that an item is 16 units square until something scales it lives in exactly one place
 * instead of being rediscovered by whoever writes the next icon box.
 *
 * <p><b>No text with styling, and no {@code Component}.</b> {@link #text} takes a {@code String},
 * because a plain string is what all thirty-odd call sites have by the time they draw — a component
 * is resolved to its display text before it reaches here, and the tooltip lists that do carry
 * components are stored as strings. Nothing in this UI draws one string in two styles, so taking a
 * {@code Component} would put a Minecraft type on the seam to serve no caller.
 *
 * <p><b>No {@code renderItemDecorations}, no gradients, no nine-slice, no atlas.</b> R5's work. This
 * interface is what today's code needs and nothing more, which is what makes it small enough that a
 * second implementation is a real thing to write rather than a project.
 *
 * <h2>Why it is an interface with a test double, rather than an abstraction</h2>
 *
 * <p>The plan said this stage was deliberately last "because an interface wrapping one implementation
 * is an indirection nobody can evaluate". That is fair, and the answer is a second implementation that
 * is not a renderer: {@code RecordingRenderer} in the tests records the calls it is given, so
 * <b>drawing becomes assertable without a client</b>. That is what turns this from a deferral of work
 * into something the tests can hold — {@code ArmatureTheme}'s shape-filling and label-fitting are no
 * longer verified only by looking at a screenshot.
 *
 * <h2>How a caller gets one</h2>
 *
 * <p>From the two places a {@code GuiGraphics} is handed to this codebase — a {@code Screen}'s
 * {@code render} and a widget's {@code renderWidget} — both of which wrap and delegate immediately:
 *
 * <pre>
 * &#64;Override
 * protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
 *     draw(new GuiGraphicsRenderer(graphics), partialTick);
 * }
 * </pre>
 *
 * <p>Both of those overrides are forced by a signature Minecraft owns, so they cannot be avoided and
 * are not meant to be: two one-line adapters are the honest minimum, and {@code .utils/check_seam.py}
 * names them so that a third cannot appear without somebody writing down why.
 */
public interface GuiRenderer {

    /**
     * A scope that closes itself, without declaring a checked exception.
     *
     * <p>Exists because {@link AutoCloseable#close} throws {@code Exception}, so a caller writing
     * {@code try (var clip = renderer.clip(...))} would have to catch it — on every clip, for a pop
     * that cannot fail. Narrowing the throws clause away is the standard fix and the reason this
     * nested type is here rather than the method returning a bare {@code AutoCloseable}.
     */
    interface Scoped extends AutoCloseable {
        @Override
        void close();
    }

    // ------------------------------------------------------------------
    // Rectangles
    // ------------------------------------------------------------------

    /**
     * Fills a rectangle, corners half-open: the right and bottom edges are excluded.
     *
     * <p>Half-open to match {@link Slot#contains} and vanilla's own {@code fill}, so a filled rectangle
     * and a hit test over the same numbers describe the same pixels. A closed interval would make two
     * adjacent fills share a line of pixels at double opacity, which reads as a seam.
     *
     * @param argb the colour, alpha included. Always eight digits — see {@code ArmatureTheme}.
     */
    void fill(int left, int top, int right, int bottom, int argb);

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** Draws one line of text with the top-left of its first glyph at {@code x}, {@code y}. */
    void text(String text, int x, int y, int argb);

    /**
     * Draws one line of text centred on {@code centreX}.
     *
     * <p>A default rather than an abstract method, and deliberately: the centring is
     * {@code centreX - width / 2}, which is arithmetic worth being able to test rather than a call into
     * whatever the current version's centred-draw helper happens to do. Implementing it in terms of
     * {@link #textWidth} and {@link #text} means one fewer method in every future implementation and no
     * possibility of the two disagreeing about where the middle is.
     */
    default void centredText(String text, int centreX, int y, int argb) {
        text(text, centreX - textWidth(text) / 2, y, argb);
    }

    /** The width of this string on one line, in pixels. Never negative. */
    int textWidth(String text);

    /** The height of one line of text. What a wrapped line advances the cursor by. */
    int lineHeight();

    // ------------------------------------------------------------------
    // Icons
    // ------------------------------------------------------------------

    /**
     * Draws an item so that it exactly fills a {@code box}-pixel square.
     *
     * <p>The one operation that is about a transform, and the reason no transform is exposed — see the
     * class note. An item is 16 units square until something scales it, so filling a box means scaling
     * around the box's corner; that is the implementation's problem and nobody else's.
     *
     * @return whether anything was drawn, so a caller can fall back to a plain block. An empty box in a
     *     row of icons reads as a bug rather than as a fallback, so the caller must be able to tell.
     */
    boolean icon(ItemStack stack, int boxX, int boxY, int box);

    // ------------------------------------------------------------------
    // Clipping
    // ------------------------------------------------------------------

    /**
     * Narrows drawing to a rectangle until the returned scope is closed.
     *
     * <p>Scoped rather than the raw push/pop pair, because a missing pop does not fail loudly: it leaves
     * the scissor stack one deeper than it should be, so every later draw in the frame is silently
     * clipped to a rectangle nobody chose — which presents as "the rest of the screen stopped
     * rendering" and points at nothing. {@code try}-with-resources places the pop on every exit path
     * including an exception.
     */
    Scoped clip(int left, int top, int right, int bottom);

    /** The same, from a placed rectangle — a view clipping to its own slot. */
    default Scoped clip(Slot slot) {
        return clip(slot.x(), slot.y(), slot.right(), slot.bottom());
    }

    /**
     * The same, from a viewport's view rectangle.
     *
     * <p>Folded in rather than written as four getters at each call site, because a clip pushed at a
     * viewport's edges and a viewport asked separately whether a point is inside it have to mean the
     * same rectangle — or a click just outside the visible area is accepted by one and hidden by the
     * other.
     */
    default Scoped clip(Viewport viewport) {
        return clip(viewport.originX(), viewport.originY(), viewport.viewRight(), viewport.viewBottom());
    }
}

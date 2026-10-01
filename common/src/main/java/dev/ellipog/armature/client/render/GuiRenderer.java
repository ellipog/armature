package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

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
 * <p><b>Styling exists, and it is this seam's own type; {@code Component} still does not cross.</b> This
 * paragraph used to say that no text was styled anywhere, which was true when it was written and is not
 * any more: a quest description is markdown, and emphasis is not emphasis if it is not drawn differently.
 * The fix is the one the {@code ArmatureButton} note promised for the day a styled string turned up —
 * <b>give the seam a styled-text type rather than put {@code Style} in the caller's hands</b> — so
 * {@link StyledRun} is a plain string and two booleans, {@link #styledText} draws a line of them, and
 * {@link #styledWidth} measures one. A {@code Component} would carry far more than that (events, fonts,
 * colours, hover) and would tie every caller to the game to say "bold".
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
     * One run of a styled line: its text, and the two things a font can actually do with it.
     *
     * <p>A record here rather than in the kit because this is the seam's vocabulary — what a renderer
     * promises to draw — and because the alternative names a Minecraft type in every caller. It is
     * deliberately smaller than the game's own notion of style: three booleans are what a markdown
     * paragraph needs, and a colour travels beside the run rather than inside it.
     *
     * <p>{@code underline} is here for links, and it is the whole of how they are marked: the card has no
     * colour of its own for a link, and the theme's palette deliberately has no borrowed one ("a colour
     * borrowed for a job it was not chosen for is a colour that will be wrong for one of the two jobs" --
     * the scrollbar's note). Under the body's own ink, an underline reads as a link and can never read as a
     * state the card does not have.
     *
     * <p>{@code scale} is here because the game's font has one size, and a heading has to be bigger than
     * the prose under it: a scaled run is drawn about its own top-left, so it grows downward from the line
     * it starts on -- which is the room a caller reserving a taller line has left for it.
     */
    record StyledRun(String text, boolean bold, boolean italic, boolean underline, float scale) {

        /** A run at the font's own size, which is what every run but a heading is. */
        public StyledRun(String text, boolean bold, boolean italic, boolean underline) {
            this(text, bold, italic, underline, 1F);
        }
    }

    /**
     * Draws a line as a sequence of styled runs, left to right, each where the last one ended.
     *
     * <p>The caller does not position the runs: a renderer is the only thing that knows how wide a run
     * <i>is</i>, so asking the caller to add up widths would be asking it to guess at the font. One call
     * per line, the same shape as {@link #text} one dimension up.
     */
    void styledText(java.util.List<StyledRun> runs, int x, int y, int argb);

    /**
     * How wide a styled string is, which is not the same as the width of the same string plain: bold is
     * wider and a scaled run is larger throughout, and a caller laying out styled prose has to know by how
     * much -- a layout that reserved the plain width for a scaled heading reserves a line the drawing
     * overflows.
     */
    int styledWidth(String text, boolean bold, boolean italic, float scale);

    /**
     * Forces everything queued so far to be drawn now, before anything after it.
     *
     * <h2>What this exists for, and why it is not an optimisation</h2>
     *
     * <p>Both implementations batch: fills accumulate in one buffer and are handed to the GPU when the
     * batch fills up, when the render type changes, or at the end of the frame. That is normally
     * invisible and usually faster. It stops being invisible when <b>one operation draws through a
     * different path than its neighbours</b>, because the two are then ordered by their own batching
     * rather than by the order the code called them in.
     *
     * <p>An item icon is exactly that operation: it goes through the item renderer rather than through
     * {@code fill}, so a queued rectangle drawn <i>after</i> it can land <i>before</i> it. The quest
     * book hit this — nodes are item icons on the canvas, and the tool cluster's backing panel is
     * drawn over the canvas afterwards, which left icons floating on top of the buttons they are
     * supposed to be behind.
     *
     * <p>So this is the seam between one drawing layer and the next, and a caller places it where a
     * <b>layering boundary</b> is, not where a performance concern is. It costs a batch flush, which is
     * a thing the frame was going to do anyway; what it buys is that "drawn later" means "on top",
     * which is the only ordering anybody reading the code can reason about.
     */
    void flush();

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

    /**
     * Draws a player's face, flat and from the front, so that it exactly fills a {@code box}-pixel square.
     *
     * <h2>Why the face and not a head</h2>
     *
     * <p>Because a head drawn as an item is a <b>model</b>: three sides, shading, and a perspective that
     * belongs to an inventory rather than to a list of names. In a row beside a player's name what is
     * wanted is the portrait — the same 8×8 face the tab list draws — and a portrait is a texture blit
     * rather than a model, so the two operations are not each other's special case.
     *
     * <p>The lookup is the implementation's, and that is the point of putting it here: which skin a
     * player has, and what to draw for one who has none, are questions about a client's caches rather
     * than about rectangles.
     *
     * @return whether anything was drawn. A server in offline mode has no skins to look up, so a caller
     *     must be able to fall back — the same contract as {@link #icon}.
     */
    boolean face(UUID player, int boxX, int boxY, int box);

    // ------------------------------------------------------------------
    // Clipping
    // ------------------------------------------------------------------

    /**
     * Softens everything drawn so far this frame, in place, and leaves the pipeline fit to draw into.
     *
     * <h2>What "so far" means, and why that is the useful operation</h2>
     *
     * <p>Minecraft's blur is a post-process over the framebuffer rather than a filter on a rectangle, so
     * what it can soften is <b>what is already in the target</b>: the world, the background, and whatever
     * this frame has drawn. For a modal behind a card that is exactly right — call it with the panel
     * drawn and the card not yet drawn, and the panel goes soft while the card stays crisp.
     *
     * <h2>The state this puts back, which is the whole of why it is here</h2>
     *
     * <p>A post-process is not a drawing operation. It reprograms the pipeline for its own passes — it
     * rebinds the render target and leaves the scissor and blending set to what those passes wanted — and
     * vanilla only ever calls it as the first thing a screen does, so nothing in vanilla is drawn into
     * the state it leaves behind. A caller that blurs <i>mid-frame</i>, with layers still to draw, is
     * asking for exactly that, and it does not fail loudly: the layers drawn through a clip are fine
     * (a clip sets its own scissor), and the ones drawn without one <b>disappear</b>.
     *
     * <p>That is a real fault and it cost a round to find: the modal card was the only thing in the
     * frame drawn with no clip of its own, and blurring before it made it vanish. So this method's
     * contract is the two halves together — process the chain, and put back the target, the scissor and
     * blending — and a caller gets to think about <i>when</i> to blur rather than about what it broke.
     *
     * <p>Honours the client's menu-blurriness setting, the same one vanilla's menus do, so a player who
     * has turned it off gets a scrim rather than a blur. {@code false} is that answer, and the one for a
     * screen with no world behind it.
     *
     * @param partialTick the frame's partial tick, for the post chain's own timing
     * @return whether anything was blurred, so a caller can fall back to a scrim
     */
    boolean blur(float partialTick);

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

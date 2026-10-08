package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.TextWrap;

import java.util.ArrayList;
import java.util.List;

/**
 * A tooltip box: the themed panel, the lines in it, and the one rule that it is <b>always inside the
 * screen</b>.
 *
 * <h2>Why the box lives in the toolkit and not in a screen</h2>
 *
 * <p>Because every tooltip in a modded screen is the same five decisions — how wide, how tall, which way
 * to move near an edge, what to fill it with, what ink to write in — and a screen that answered them
 * itself is a screen whose tooltips drift from every other screen's the first time one of the answers
 * changes. It happened once already: the tooltip box was drawn from {@code panel()} and
 * {@code controlEdgeBright()} while the theme carried {@code tooltipFill}/{@code tooltipEdge} tokens that
 * nothing in the game used, so a theme could set its tooltip colours and see no change.
 *
 * <h2>The invariant, and the four moves that keep it</h2>
 *
 * <p>For any lines, any preferred position and any screen of at least one pixel, <b>no pixel of the box
 * or its text falls outside the screen</b>. That is the contract, it is asserted by a sweep rather than by
 * examples, and it takes four moves in this order:
 *
 * <ol>
 *   <li><b>Fold.</b> Each line is wrapped to the room the window leaves ({@link TextWrap}'s rule, the same
 *       one a panel's rows and a card's body wrap with), so the box can never be wider than the
 *       window — {@link #MIN_LINE_WIDTH} is a floor only while the window can hold it.</li>
 *   <li><b>Move to the side.</b> The placements are tried in order and the first that fits <i>entirely</i>
 *       is taken: for a tooltip at the pointer, right-below, left-below, right-above, left-above; for a
 *       caption at a control, under it, above it, right of it, left of it. A move is preferred to a
 *       squeeze, because a box that is somewhere unexpected is readable and a box that is half off the
 *       screen is not.</li>
 *   <li><b>Slide.</b> When no placement fits but the box does, it is clamped inside — the pointer or the
 *       anchor may end up under it, which is the lesser fault.</li>
 *   <li><b>Say there is more.</b> A box that cannot fit is capped to the screen and its last drawn line
 *       becomes {@link #ELLIPSIS}: the one case folding cannot answer, because wrapping works <i>within</i>
 *       a line and cannot merge two facts into one line. A list taller than the window is that case, and
 *       the alternative — cutting it off with nothing said — is the fault this toolkit's comments keep
 *       naming, a control that reads as complete and is not.</li>
 * </ol>
 *
 * <p>A screen too small to show a single line draws <b>nothing</b>: a box with no text in it is a
 * rectangle, and a rectangle is a worse lie than an absent tooltip.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not drop lines to make a box fit before it has tried folding and moving, and it does not
 * re-fold to a <i>wider</i> room to shave a line off a tall box: folding cannot merge two lines, so that
 * only helps a tooltip that is one long sentence, and on a wide window it would turn a tip into a
 * paragraph — which is what {@link #MAX_LINE_WIDTH}'s own note exists to prevent.
 *
 * <h2>The theme is read, not captured</h2>
 *
 * <p>Fill, border, text and radius all come from {@link ArmatureTheme#current()}, so a tooltip drawn
 * inside a chapter's scope wears that chapter's palette — which is the whole point of a scope.
 */
public final class Tooltips {

    /** The inset between the box's edge and its text, and the gap to the pointer. */
    public static final int PAD = 4;

    /** How far the box sits from the pointer, on the side it opens towards. */
    public static final int OFFSET = 10;

    /**
     * How far the box clears the pointer's line: the top edge is this far above it, and a box placed
     * above has its <b>bottom</b> this far above it.
     *
     * <p>Eleven, which is about a line — so a tip opens above the line the pointer is on rather than on
     * top of it, and the mirrored form keeps the same clearance. It was a bare eleven at one call site
     * until the placements became a list, which is when it needed a name.
     *
     * <p><b>And the mirror is a clearance rather than an overlap, which is the half that makes the
     * placement useful.</b> Mirroring it the other way — the pointer eleven pixels inside the box's
     * <i>bottom</i> — puts the box <i>lower</i> than the opening placement for any box shorter than
     * twenty-two pixels, which is nearly all of them, so the above case could never be the one that fits
     * a pointer near the bottom edge. The four placements have to cover four quadrants to be four
     * placements.
     */
    public static final int POINTER_INSET = 11;

    /** The closest the box comes to any screen edge, on every side. */
    public static final int MARGIN = 2;

    /** The air between a control and the caption under it, which is what the captions have always had. */
    public static final int ANCHOR_GAP = 2;

    /**
     * The widest a line gets before it is folded, in pixels.
     *
     * <p>About forty characters of the default font. That is the order vanilla wraps its own tooltips at,
     * and it is chosen the same way: wide enough that a sentence keeps its shape across two lines rather
     * than five, narrow enough that the box still reads as a tip rather than as a paragraph. It is an
     * <b>upper</b> bound and not a target — a short line still gets a short box, because a tooltip padded
     * out to a fixed width would look like a panel.
     */
    public static final int MAX_LINE_WIDTH = 240;

    /**
     * The narrowest a line is folded to, while the window can hold it.
     *
     * <p>It exists so the arithmetic has a floor: a room of zero is a wrap that cannot place a character
     * and a box one glyph wide. <b>But the window comes first</b> — a screen narrower than this floor plus
     * the box's own padding folds to what it can, because a floor that outranks the screen is a box wider
     * than the screen, which is the one thing this class promises never happens.
     */
    public static final int MIN_LINE_WIDTH = 48;

    /**
     * The line that says "there is more", drawn in place of the last line a capped box can show.
     *
     * <p>The glyph {@code Measure.truncate} already writes and the menus already draw, so it is one the
     * font is measured to carry — see {@code .utils/check_glyphs.py}, which fails the build on any escape
     * outside that set.
     */
    public static final String ELLIPSIS = "\u2026";

    private Tooltips() {
    }

    /** The box's width for these lines: the widest line plus the padding. */
    public static int width(GuiRenderer r, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, r.textWidth(line));
        }
        return widest + PAD * 2;
    }

    /** The box's height for this many lines: one text height each, plus the padding. */
    public static int height(GuiRenderer r, int lineCount) {
        return lineCount * r.lineHeight() + PAD + 2;
    }

    /**
     * The lines as they will be drawn: each one folded to the room the window leaves it.
     *
     * <p>Separate from {@link #draw} because the folding is the part worth asking about — a test can
     * assert the lines without a box, and a caller that wants to know how tall a tooltip will be asks this
     * first and then {@link #height}.
     *
     * <p>An <b>empty</b> line stays one line. {@link TextWrap} answers "nothing in, nothing out" for a
     * paragraph, and for a tooltip that is wrong: a blank line here is a spacer a caller wrote on purpose,
     * and it is how a tooltip separates two facts. That one difference is the whole of this method's own
     * logic; everything else is {@code TextWrap}'s.
     */
    public static List<String> folded(GuiRenderer r, List<String> lines, int screenWidth) {
        return folded(lines, lineRoom(screenWidth), Measure.of(r::textWidth, r.lineHeight()));
    }

    /** The same, over a {@link Measure} and a room: the arithmetic without a renderer. */
    private static List<String> folded(List<String> lines, int room, Measure measure) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                out.add("");
                continue;
            }
            out.addAll(TextWrap.wrap(line, room, measure));
        }
        return List.copyOf(out);
    }

    /**
     * How wide one line may be, given the window.
     *
     * <p>The cap, or whatever the window can actually hold with a margin either side, whichever is
     * smaller — so a narrow window folds earlier rather than drawing a box wider than itself. The floor
     * yields to the window, and the result is never less than one pixel, because {@link TextWrap} with a
     * room of zero has nowhere to put a character.
     */
    private static int lineRoom(int screenWidth) {
        int windowRoom = Math.max(1, screenWidth - MARGIN * 2 - PAD * 2);
        int floor = Math.min(MIN_LINE_WIDTH, windowRoom);
        return Math.max(floor, Math.min(MAX_LINE_WIDTH, windowRoom));
    }

    /**
     * One box, placed: where it goes, how big it is, and the lines it draws.
     *
     * <p>Exposed because it is the answer to a question a test can ask without a renderer — {@link #fit}
     * is pure — and because "the box is inside the screen" is a statement about this record's four
     * numbers.
     */
    public record Placed(int x, int y, int width, int height, List<String> lines) {

        /** The box's right edge, exclusive. */
        public int right() {
            return x + width;
        }

        /** The box's bottom edge, exclusive. */
        public int bottom() {
            return y + height;
        }
    }

    /** A box's size and contents, before anything decides where it goes. */
    private record Shape(int width, int height, List<String> lines) {
    }

    /**
     * The whole placement for one preferred top-left, or null when the screen cannot show a line.
     *
     * <p>Pure: it takes a {@link Measure} rather than a renderer, so the containment rule is asserted
     * against a known width per character instead of judged in a screenshot. A caller that wants the box
     * somewhere specific gets it there when there is room, and inside the screen when there is not.
     */
    public static Placed fit(List<String> lines, int preferredX, int preferredY,
                             int screenWidth, int screenHeight, Measure measure) {
        Shape shape = shape(lines, screenWidth, screenHeight, measure);
        if (shape == null) {
            return null;
        }
        return place(shape, List.of(new int[] {preferredX, preferredY}), screenWidth, screenHeight);
    }

    /**
     * Draws the box near the pointer: folded to fit, moved to a side that fits, and clamped if none does.
     *
     * <p>The placements are tried in the order a reader looks for a tip — right-below of the pointer
     * first, which is where a tooltip has always opened, then the left, then the two above it. Independent
     * flips are what the first version did, and they are not enough for a box that is tall <i>and</i> wide:
     * a corner needs both at once, and the pair is only two of the four cases.
     */
    public static void draw(GuiRenderer r, List<String> lines, int mouseX, int mouseY,
                            int screenWidth, int screenHeight) {
        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        Shape shape = shape(lines, screenWidth, screenHeight, measure);
        if (shape == null) {
            return;
        }
        int left = mouseX - PAD - shape.width();
        int below = mouseY - POINTER_INSET;
        // Clear of the pointer, not mirrored onto it: see POINTER_INSET for why that is the difference
        // between a placement that can be chosen and one that never fits anything.
        int above = mouseY - POINTER_INSET - shape.height();
        paint(r, place(shape, List.of(
                new int[] {mouseX + OFFSET, below},
                new int[] {left, below},
                new int[] {mouseX + OFFSET, above},
                new int[] {left, above}), screenWidth, screenHeight));
    }

    /**
     * Draws a caption at a control: under it, then above it, then beside it.
     *
     * <h2>Why a control's caption goes through this class</h2>
     *
     * <p>Because it is the same surface: the same three tokens, the same fold, and the same promise that
     * it is on the screen. A screen that hand-rolled it drew the border a pixel <i>outside</i> the box and
     * clamped against the panel rather than the window, which is a caption that leaves the screen at the
     * panel's edge — and the panel's edge is the window's on a full-bleed book.
     *
     * <p>The anchor is a rectangle in screen coordinates: the control the caption belongs to. A caller
     * that knows only a point passes a zero-sized anchor at it, which is the "under this spot" case.
     */
    public static void drawAt(GuiRenderer r, List<String> lines, int anchorX, int anchorY,
                              int anchorWidth, int anchorHeight, int screenWidth, int screenHeight) {
        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        Shape shape = shape(lines, screenWidth, screenHeight, measure);
        if (shape == null) {
            return;
        }
        paint(r, place(shape, List.of(
                new int[] {anchorX, anchorY + anchorHeight + ANCHOR_GAP},
                new int[] {anchorX, anchorY - ANCHOR_GAP - shape.height()},
                new int[] {anchorX + anchorWidth + ANCHOR_GAP, anchorY},
                new int[] {anchorX - ANCHOR_GAP - shape.width(), anchorY}), screenWidth, screenHeight));
    }

    /**
     * The box's size and lines for a screen, or null when nothing can be drawn.
     *
     * <p>The guard is first, and it is two comparisons rather than a clamp: a screen with no room for one
     * line's height, or for the box's own padding and margins, has no box that would be both contained and
     * readable — so nothing is drawn, and the caller's hover is simply unanswered.
     */
    private static Shape shape(List<String> lines, int screenWidth, int screenHeight, Measure measure) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int lineHeight = measure.lineHeight();
        if (screenWidth < MARGIN * 2 + PAD * 2 + 1) {
            return null;
        }
        if (screenHeight < MARGIN * 2 + lineHeight + PAD + 2) {
            return null;
        }

        List<String> folded = folded(lines, lineRoom(screenWidth), measure);
        // The height first, because it decides how many lines are drawn; then the width, measured over
        // what is actually drawn rather than over everything that was folded. They are the same list
        // unless something was dropped, and when it was, the box should be as wide as what is in it --
        // an ellipsis is narrower than the line it replaced.
        int height = Math.min(folded.size() * lineHeight + PAD + 2, screenHeight - MARGIN * 2);
        List<String> drawn = drawn(folded, height, lineHeight);
        int widest = 0;
        for (String line : drawn) {
            widest = Math.max(widest, measure.width(line));
        }
        // Both bounds are what make the clamp below total: a box that fits its screen is a box that always
        // has somewhere to go, which is the whole of the containment promise.
        int width = Math.min(widest + PAD * 2, screenWidth - MARGIN * 2);
        return new Shape(width, height, drawn);
    }

    /**
     * The lines a box of this height shows: all of them, or as many as fit with {@link #ELLIPSIS} saying so.
     *
     * <p>The ellipsis takes the <b>last</b> slot rather than being appended, so a box that can show six
     * lines shows five facts and the promise of more, rather than six and a seventh line outside itself.
     */
    private static List<String> drawn(List<String> folded, int height, int lineHeight) {
        int fits = Math.max(1, (height - PAD - 2) / lineHeight);
        if (folded.size() <= fits) {
            return folded;
        }
        List<String> out = new ArrayList<>(folded.subList(0, Math.max(0, fits - 1)));
        out.add(ELLIPSIS);
        return List.copyOf(out);
    }

    /**
     * The first placement that fits entirely, or the first one clamped inside the screen.
     *
     * <p>Clamping is a real answer and not a fallback for a bug: a box that fits the screen but no
     * placement around its pointer can still be drawn — it simply shares the pointer's pixels, which is
     * better than being half off the edge. The clamp's arithmetic is total because {@link #shape} bounds
     * the box by the screen, so `low` is never above `high`; if that ever stopped being true the box would
     * be wider than the screen and this whole class would be a lie.
     */
    private static Placed place(Shape shape, List<int[]> candidates, int screenWidth, int screenHeight) {
        int[] first = candidates.get(0);
        for (int[] at : candidates) {
            if (inside(at[0], at[1], shape, screenWidth, screenHeight)) {
                return new Placed(at[0], at[1], shape.width(), shape.height(), shape.lines());
            }
        }
        return new Placed(
                clamp(first[0], MARGIN, screenWidth - shape.width() - MARGIN),
                clamp(first[1], MARGIN, screenHeight - shape.height() - MARGIN),
                shape.width(), shape.height(), shape.lines());
    }

    /** Whether a box of this shape at this top-left is wholly inside the screen, margins included. */
    private static boolean inside(int x, int y, Shape shape, int screenWidth, int screenHeight) {
        return x >= MARGIN && y >= MARGIN
                && x + shape.width() <= screenWidth - MARGIN
                && y + shape.height() <= screenHeight - MARGIN;
    }

    /** Low wins when the window is smaller than the box, which {@link #shape} has already prevented. */
    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    /** The box itself: the themed panel, then its lines. */
    private static void paint(GuiRenderer r, Placed placed) {
        ArmatureTheme.panel(r, placed.x(), placed.y(), placed.width(), placed.height(),
                ArmatureTheme.tooltipFill(), ArmatureTheme.tooltipEdge());
        int lineY = placed.y() + PAD;
        for (String line : placed.lines()) {
            r.text(line, placed.x() + PAD, lineY, ArmatureTheme.tooltipText());
            lineY += r.lineHeight();
        }
    }
}

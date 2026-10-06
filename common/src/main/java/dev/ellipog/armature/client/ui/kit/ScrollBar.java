package dev.ellipog.armature.client.ui.kit;

import dev.ellipog.armature.client.render.GuiRenderer;

import java.util.Objects;

/**
 * A scrollbar: where its grip is drawn, what the pointer is doing to it, and the mapping between the
 * two.
 *
 * <h2>Why this is its own class rather than a corner of the scroll view</h2>
 *
 * <p>Because the bar was a copy before it was a class, and by the time anybody counted there were
 * three of them. {@code ScrollView} had one, and two more grew beside it in the screen that needed them
 * most — a static {@code scrollThumb} helper for a pair of picker lists, and a {@code thumb} method on
 * one mod's own layout class — while four further lists scrolled with no bar at all. Every copy
 * carried the same two formulae (where the grip goes, and what a pointer at a given y means) and the
 * same two constants, and the arithmetic is exactly the part that must not be written twice: {@link #thumb} computes a top from
 * an offset and {@link #dragTo} computes an offset from a top, and a pair that agrees on the day it
 * is written drifts on the next edit — the symptom being a grip that walks away from the pointer as
 * you drag, which reads as the list being over-sensitive rather than as an arithmetic fault.
 *
 * <p>So the bar owns the strip, the range it describes, and every gesture. A container says where
 * the strip is and how tall its content is; it does not do the arithmetic, and it cannot get it
 * wrong in a way this class gets right.
 *
 * <h2>Three states, and a press that means one of two things</h2>
 *
 * <p>A press <b>on the grip</b> drags it, holding the point that was grabbed, for as long as the
 * button is down — including when the pointer leaves the bar and leaves the strip entirely, which is
 * what a drag is everywhere else in the world. A press <b>on the groove</b> is the other gesture:
 * it pages one screen toward the pointer and keeps paging while the button is held. The two are one
 * press with two meanings rather than one meaning with a modifier, and which one it is is decided by
 * the grip's own rectangle — the same rectangle that was drawn.
 *
 * <h2>The drag owns the bar, so the wheel loses to it</h2>
 *
 * <p>A wheel notch during a thumb drag is refused rather than applied. The alternative was measured
 * on the canvas, where a drag and a zoom fight over one offset and the drag used to drag the view
 * back to where it started once per notch (see {@code Viewport.dragTo}); the bar could be made to
 * behave that way too, by retargeting the grab, and it would be a second design in the same kit for
 * the same collision. Refusing is one line, cannot fight, and is defensible out loud: the pointer is
 * committed to the bar, and the wheel is a different control.
 *
 * <h2>Time is passed in, like everything else in this kit</h2>
 *
 * <p>The hold-repeat and the hover's ease both take a millisecond value rather than reading a clock,
 * so a test advances them by passing a larger number. A hold-repeat is the one gesture in this
 * toolkit that has no input event to hang off — Minecraft has no mouse auto-repeat — so it is driven
 * from the frame, which is why {@link #advance} exists and why the caller must call it.
 *
 * <h2>Game-free by design</h2>
 *
 * <p>A viewport, some rectangles and two numbers. No widget, no theme, no font — {@link #draw} takes
 * the colours it fills with as a {@link Skin} rather than asking for them, which is the same rule
 * {@code ScrollView} already stated for its two colours and is what keeps this file off
 * {@code check_kit.py}'s exception list.
 */
public final class ScrollBar {

    /** What the pointer is doing to this bar. */
    public enum State {

        /** Nothing. The resting appearance. */
        IDLE,

        /** The pointer is over the grab band and no button is down. */
        HOVER,

        /** The grip is being dragged. */
        DRAGGING,

        /** The groove was pressed: paging toward the pointer, repeating while held. */
        GUTTER
    }

    /**
     * The four colours a state is drawn from, and nothing else.
     *
     * <p>A record rather than four arguments because the caller has to build it once per frame and
     * every site wants the same four; a parameter list would be four chances to pass two of them in
     * the wrong order, and the two that would be swapped — {@code thumbHover} and {@code thumbActive}
     * — differ by a shade, so the mistake would look like a rendering bug rather than a call-site bug.
     *
     * <p>Built by {@code ArmatureScrollStyle} from the theme's own two scrollbar tokens. The kit does
     * not know the theme exists, which is deliberate: see the class note.
     */
    public record Skin(int track, int thumb, int thumbHover, int thumbActive) {
    }

    /** How wide the grip is drawn. Three, which is what every bar in this project has always been. */
    public static final int WIDTH = 3;

    /**
     * Where the bar sits when it is outside the region it scrolls: this many pixels past its right
     * edge, leaving room for the {@link #GRAB} band to start before the bar rather than on a row.
     */
    public static final int OFFSET = 4;

    /** How far a press still counts, measured from the grip's own rectangle. Six, so the band is generous. */
    public static final int GRAB = 6;

    /**
     * The shortest a grip may be drawn.
     *
     * <p>Because a scaled-to-proportion grip on a very long document becomes a two-pixel line that
     * reads as an artefact rather than as a control. There used to be two of these — sixteen here and
     * six in the two hand-rolled helpers — which is two answers to one question and, worse, a grip
     * that changed size when the same list was drawn by a different mechanism.
     */
    public static final int MIN_THUMB = 16;

    /**
     * What one wheel notch moves when a list has no row of its own to move by.
     *
     * <p>A list with rows passes its own pitch instead, so a notch moves exactly one row — see
     * {@link #pitch}. This is the fallback for a column of prose, and it is one number rather than the
     * eighteen {@code scrollY * 30} expressions this replaces.
     */
    public static final int WHEEL_PITCH = 30;

    /** How long a groove press pages once before the repeat starts. Long enough not to fight a click. */
    public static final long REPEAT_DELAY_MILLIS = 400L;

    /** How often it pages after that. Sixteen a second: continuous, and slow enough to stop on a row. */
    public static final long REPEAT_INTERVAL_MILLIS = 60L;

    /** How much of the groove lights up while it is being held. A wash, not a colour of its own. */
    private static final float GUTTER_WASH = 0.18F;

    private final Viewport viewport;

    /**
     * What to do after the offset moves, or null.
     *
     * <h2>Why a bar needs to tell anybody anything</h2>
     *
     * <p>Because moving an offset is not the same as moving what is drawn in it. A viewport is a
     * transform, and a {@link ScrollView} is a transform <b>plus widgets it has to re-place</b>: a
     * control scrolled past the top of a list is at a new screen position, and only the container knows
     * that. The API this class replaced had that built in — {@code ScrollView.scrollBy} and
     * {@code scrollTo} re-placed the children themselves — and moving the writes into the bar quietly
     * dropped it: the offset moved, the drawn rows moved, and the widgets stayed where they were. Two of
     * the nine views in one consumer both register widgets *and* place them only during a rebuild, so the
     * fault was visible there and hidden in the other seven — which is the worst shape a fault can have.
     *
     * <p>So the bar runs this after <b>every</b> write of its own, and {@link ScrollView} sets it in its
     * constructor. A caller cannot forget it because a caller never sets it, and a future write path
     * cannot bypass it because every one of them goes through {@link #scrolled}.
     */
    private Runnable afterScroll;

    /** The ease between the resting and hovered grip. Through {@link Motion}, so a theme's motion reaches it. */
    private final Tween hover = Motion.tween(0F);

    private int stripX;
    private int stripY;
    private int stripWidth;
    private int stripHeight;

    private int pitch = WHEEL_PITCH;

    private State state = State.IDLE;
    private boolean over;

    private int grabOffset;
    private int gutterSign;
    private long nextRepeatAt;

    private double wheelRemainder;
    private int wheelSign;

    /**
     * A bar over a viewport.
     *
     * <p>The strip is <b>not</b> derived here. A bar outside its region and a bar inside it are both
     * real — {@code ScrollView}'s sits past the right edge, the pack page's sits in a strip its layout
     * reserved — and only the caller knows which, so {@link #strip} is how it says and a bar that has
     * never been bound draws nothing rather than guessing.
     */
    public ScrollBar(Viewport viewport) {
        this.viewport = Objects.requireNonNull(viewport, "viewport");
    }

    /**
     * What to run after the offset moves — {@link ScrollView}'s placement pass, in practice.
     *
     * <p>See {@link #afterScroll} for the fault this exists to prevent. Set once, by the container that
     * owns the widgets; a bar with no hook scrolls a bare offset, which is what a list of drawn rows
     * wants: they are read from {@code scrollY()} every frame and there are no children to place.
     */
    public ScrollBar onScrolled(Runnable after) {
        this.afterScroll = after;
        return this;
    }

    /** Runs the after-scroll hook. Called after every write this class makes. */
    private void scrolled() {
        if (afterScroll != null) {
            afterScroll.run();
        }
    }

    /** The region this bar scrolls. The caller reads and writes the offset through it. */
    public Viewport viewport() {
        return viewport;
    }

    // ------------------------------------------------------------------
    // Binding, per frame
    // ------------------------------------------------------------------

    /**
     * Where the bar is drawn, and how tall its groove is.
     *
     * <p>Re-applied every frame, like {@link Viewport#bounds}, and for the same reason: a window
     * resize is announced to one place and the drawing runs from several, so a rectangle stored once
     * describes the previous window for an unknown number of frames.
     */
    public ScrollBar strip(int x, int y, int width, int height) {
        this.stripX = x;
        this.stripY = y;
        this.stripWidth = width;
        this.stripHeight = height;
        return this;
    }

    /**
     * The usual strip for a bar that sits outside the region: {@link #OFFSET} past its right edge, as
     * tall as the region.
     *
     * <p>One call rather than the four numbers at each site, because the relationship is the kit's:
     * every bar outside its region is in the same place relative to it, and the room for it is the
     * caller's business (see {@code BookGeometry.SIDEBAR_SCROLLBAR}).
     */
    public ScrollBar stripOutsideViewport() {
        return strip(viewport.viewRight() + OFFSET, viewport.originY(), WIDTH, viewport.viewHeight());
    }

    /**
     * How far one row of this list is, for the wheel and for a page.
     *
     * <p>A list with rows of its own passes them here and gets a notch that lands on a row boundary
     * and a page that always shows one row it already showed. A list without rows leaves the default.
     */
    public ScrollBar pitch(int value) {
        this.pitch = Math.max(1, value);
        return this;
    }

    /** The pitch in force. See {@link #pitch(int)}. */
    public int pitch() {
        return pitch;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * Where the groove is drawn, or null when there is nothing to scroll.
     *
     * <p>One condition gates the drawing, the hit test and the press — a bar nobody drew must not
     * swallow a click in the margin beside a short list, which reads as a dead zone.
     */
    public Slot track() {
        if (stripHeight <= 0 || stripWidth <= 0 || viewport.maxScrollY() <= 0 || thumbHeight() <= 0) {
            return null;
        }
        return new Slot(null, stripX, stripY, stripWidth, stripHeight);
    }

    /** Where the grip is drawn, or null when there is nothing to scroll. */
    public Slot thumb() {
        Slot track = track();
        if (track == null) {
            return null;
        }
        return new Slot(null, track.x(), thumbTop(), track.width(), thumbHeight());
    }

    /**
     * Whether a point should count as a press on the bar.
     *
     * <h2>Why the band is deliberately wider than the bar, and why it stops at the region</h2>
     *
     * <p>The grip is three pixels. Three pixels is a target you miss, and a miss on a scrollbar lands
     * on the list underneath — so the failure mode of an exact hit test is not "nothing happened", it
     * is "I clicked the thing behind it". The band therefore runs {@link #GRAB} pixels past the bar on
     * each side <b>except</b> where the region is: a bar outside its region cannot be pressed through
     * the rows, so the band starts one pixel past the region's right edge; a bar inside its region —
     * whose strip the layout carved out of the band — stops at the strip's own left edge, because
     * widening it there would take the last few pixels of every row.
     */
    public boolean hit(double screenX, double screenY) {
        Slot track = track();
        if (track == null) {
            return false;
        }
        int left = track.x() >= viewport.viewRight()
                ? Math.max(viewport.viewRight() + 1, track.x() - GRAB)
                : track.x();
        return screenX >= left && screenX < track.right() + GRAB
                && screenY >= track.y() && screenY < track.bottom();
    }

    /** What the pointer is doing to this bar. */
    public State state() {
        if (state != State.IDLE) {
            return state;
        }
        return over ? State.HOVER : State.IDLE;
    }

    /** Whether a gesture is in progress. A caller routes move and release on this. */
    public boolean held() {
        return state == State.DRAGGING || state == State.GUTTER;
    }

    /** How hovered the grip is right now: 0 at rest, 1 fully hovered, and whatever is between. */
    public float hoverAmount(long nowMillis) {
        return hover.value(nowMillis);
    }

    /**
     * One page, less the row that carries over.
     *
     * <p>A page that moved exactly its own height would put every row somewhere new and the reader
     * would have to find their place again; keeping one row means the page after a page starts with
     * something that was already on screen. The pitch is the row, and it is floored at one pixel so a
     * strip taller than its content still moves.
     */
    public int pageStep() {
        return Math.max(1, viewport.viewHeight() - Math.max(1, pitch));
    }

    // ------------------------------------------------------------------
    // The gestures
    // ------------------------------------------------------------------

    /**
     * Takes a press, and says which gesture it started.
     *
     * @return whether the press was this bar's. False leaves it to the caller's other controls, and
     *     is also the answer for a bar with nothing to scroll.
     */
    public boolean press(double screenX, double screenY, long nowMillis) {
        if (!hit(screenX, screenY)) {
            return false;
        }
        Slot grip = thumb();
        if (grip != null && grip.contains(screenX, screenY)) {
            state = State.DRAGGING;
            // Where inside the grip the pointer took hold, so it does not jump to put its top under
            // the pointer: the thing that makes a hand-rolled scrollbar feel wrong rather than almost
            // right. Every later position is computed from it.
            grabOffset = (int) screenY - grip.y();
            return true;
        }

        // The groove: page toward the pointer, and keep paging while it is held. The direction is
        // taken once, at the press, and never re-read — a repeat that reversed itself because the
        // grip had moved past the pointer would be a bar that cannot be held down.
        gutterSign = screenY < grip.y() + grip.height() / 2.0 ? -1 : 1;
        state = State.GUTTER;
        nextRepeatAt = nowMillis + REPEAT_DELAY_MILLIS;
        page(gutterSign);
        return true;
    }

    /**
     * Moves the grip so the pointer still holds the same point on it, and scrolls to match.
     *
     * <p>The arithmetic is the inverse of {@link #thumb}'s, and it is written as that inverse on
     * purpose — see the class note. It is also the whole of "the grab survives leaving the bar": the
     * pointer may be a thousand pixels past either end and the offset is clamped to the track's
     * travel, so the grip cannot escape and the list cannot run off its own end.
     *
     * <p>Rounded rather than integer-divided. An integer division loses up to a pixel per event, and
     * this is called on every mouse move — so a slow drag across a long list would arrive short of
     * the bottom, which reads as the scroll range being wrong rather than as rounding.
     */
    public void dragTo(double screenY) {
        if (state != State.DRAGGING) {
            return;
        }
        Slot track = track();
        int travel = track.bottom() - thumbHeight() - track.y();
        if (travel <= 0) {
            // The grip fills the groove, so there is nowhere to drag it. Not an error: it is what a
            // list one row taller than its viewport produces.
            return;
        }
        int wanted = (int) screenY - grabOffset - track.y();
        int most = viewport.maxScrollY();
        int before = viewport.scrollY();
        viewport.setScrollY(Math.round(Math.max(0, Math.min(travel, wanted)) * (float) most / travel));
        if (viewport.scrollY() != before) {
            scrolled();
        }
    }

    /**
     * The hold-repeat, called once per frame.
     *
     * @return whether it paged this frame, so a caller can tell a still bar from a moving one
     */
    public boolean advance(long nowMillis) {
        if (state != State.GUTTER || nowMillis < nextRepeatAt) {
            return false;
        }
        nextRepeatAt = nowMillis + REPEAT_INTERVAL_MILLIS;
        return page(gutterSign);
    }

    /**
     * One page in a direction. Clamped by the viewport, so an overshoot is the end and not past it.
     *
     * <p>Runs the after-scroll hook when it moved — see {@link #afterScroll}: a page that moves the
     * offset without re-placing the widgets is a list whose rows scroll and whose controls do not.
     */
    private boolean page(int direction) {
        int was = viewport.scrollY();
        viewport.scrollBy(direction * pageStep());
        if (viewport.scrollY() == was) {
            return false;
        }
        scrolled();
        return true;
    }

    /**
     * Ends whatever gesture was in progress.
     *
     * <p>Consumed unconditionally by the caller — deliberately not "only if the pointer is still over
     * the bar". Letting go outside the bar is how a drag ends in every program ever written.
     *
     * @return whether there was one. Idempotent, so a doubled release is harmless.
     */
    public boolean release() {
        boolean was = held();
        state = State.IDLE;
        grabOffset = 0;
        gutterSign = 0;
        return was;
    }

    // ------------------------------------------------------------------
    // The wheel
    // ------------------------------------------------------------------

    /**
     * How many whole notches a wheel delta asks for, carrying the fraction over.
     *
     * <h2>Why the fraction is kept</h2>
     *
     * <p>Because a real wheel is not always a whole notch. Minecraft passes the delta through
     * untouched when {@code discreteMouseScroll} is off, so a trackpad or a smooth-scrolling mouse
     * arrives as a run of small fractions — and the {@code (int) (scrollY * 30)} this replaces threw
     * every one of them away, which is a list that does not move at all under a light two-finger
     * swipe. The remainder is per bar, so scrolling one list never spends another's.
     *
     * <p>A direction change throws the remainder away rather than letting it cancel. A reversed flick
     * that had to pay off the previous direction's fraction first is a flick that does nothing, which
     * is the same fault from the other side.
     *
     * <p>The denominator is the caller's: a list with rows passes its own pitch to {@link #wheel} and
     * gets a notch that lands on a row. This method is the count alone, for the one caller that has
     * its own snapping to do — the book's sidebar.
     */
    public int notches(double wheelDelta) {
        int sign = wheelDelta > 0 ? 1 : (wheelDelta < 0 ? -1 : 0);
        if (sign == 0) {
            return 0;
        }
        if (sign != wheelSign) {
            wheelRemainder = 0;
            wheelSign = sign;
        }
        wheelRemainder += wheelDelta;
        int steps = (int) wheelRemainder;
        wheelRemainder -= steps;
        return steps;
    }

    /**
     * Applies a wheel delta: one pitch per notch, down for down.
     *
     * <p>Refused while the grip is being dragged — see the class note — and refused <b>before</b> the
     * delta is spent, so a wheel turned during a drag does not bank up and jump when the drag ends.
     *
     * <p>Runs the after-scroll hook when the offset actually moved — see {@link #afterScroll}, which is
     * the half of this that a {@link ScrollView} needs and a drawn list does not.
     */
    public void wheel(double wheelDelta, int pitch) {
        if (held()) {
            return;
        }
        int steps = notches(wheelDelta);
        if (steps == 0) {
            return;
        }
        int before = viewport.scrollY();
        viewport.scrollBy(-steps * Math.max(1, pitch));
        if (viewport.scrollY() != before) {
            scrolled();
        }
    }

    /** The same, at this bar's own pitch. */
    public void wheel(double wheelDelta) {
        wheel(wheelDelta, pitch);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * The bar, in the state the pointer puts it in.
     *
     * <p>Takes the pointer and the time rather than being told what state to draw, because the two
     * halves of "what the bar looks like" have to come from the same answer: the rectangle the press
     * was hit-tested against is the rectangle drawn, and the hover is the one the pointer produced.
     * A caller passing a state it computed itself is a second description of the same fact.
     *
     * <p>The track is drawn first and always, which the two hand-rolled bars did not do: a grip
     * floating on the list behind it has no groove to be a proportion of, and the whole point of a
     * bar is that its length means something.
     *
     * <p>The three states are distinguishable without a colour being chosen for them: the resting
     * grip is the theme's own thumb colour, a hovered one is a shade of it away from the track, and a
     * held one is a longer shade of the same plus a notch down its middle — so a theme with a pale
     * grip on a pale groove still reads, which hand-picking two more colours per theme would not
     * guarantee.
     */
    public void draw(GuiRenderer renderer, Skin skin, double mouseX, double mouseY, long nowMillis) {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(skin, "skin");

        over = hit(mouseX, mouseY);
        // A held bar is not hovered: the state is the louder fact, and an ease towards a hover the
        // pointer has already left is a grip that brightens as it is being dragged away.
        hover.retarget(!held() && over ? 1F : 0F, nowMillis);

        Slot track = track();
        if (track == null) {
            // Everything fits. A track with a full-height grip in it says "there is more" and lies.
            return;
        }

        renderer.fill(track.x(), track.y(), track.right(), track.bottom(), skin.track());
        if (state == State.GUTTER) {
            renderer.fill(track.x(), track.y(), track.right(), track.bottom(),
                    Colour.translucent(skin.thumbActive(), GUTTER_WASH));
        }

        Slot grip = thumb();
        int fill = held()
                ? skin.thumbActive()
                : Colour.lerp(skin.thumb(), skin.thumbHover(), hover.value(nowMillis));
        renderer.fill(grip.x(), grip.y(), grip.right(), grip.bottom(), fill);

        if (held()) {
            // The pressed-in notch: one column, inset a pixel at each end so the lit rim reads all
            // the way round. The groove's own colour, because that is a colour every theme has
            // already chosen to be visible against the grip — the honest source for "a hole in it".
            int core = grip.x() + grip.width() / 2;
            renderer.fill(core, grip.y() + 1, core + 1, grip.bottom() - 1, skin.track());
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** The grip's drawn height, or 0 when there is no bar. */
    private int thumbHeight() {
        int height = stripHeight;
        if (height <= 0 || viewport.maxScrollY() <= 0) {
            return 0;
        }
        int content = Math.max(1, viewport.scaled(viewport.contentHeight()));
        return Math.min(height, Math.max(MIN_THUMB, height * height / content));
    }

    /** The grip's drawn top. The forward formula; {@link #dragTo} is its inverse. */
    private int thumbTop() {
        Slot track = track();
        int travel = track.bottom() - thumbHeight() - track.y();
        if (travel <= 0) {
            return track.y();
        }
        return track.y() + travel * viewport.scrollY() / Math.max(1, viewport.maxScrollY());
    }

    @Override
    public String toString() {
        return "ScrollBar(" + state + ", strip " + stripX + "," + stripY + " " + stripWidth + "x"
                + stripHeight + ", " + viewport + ")";
    }
}

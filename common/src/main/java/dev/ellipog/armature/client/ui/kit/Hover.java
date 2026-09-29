package dev.ellipog.armature.client.ui.kit;

import java.util.Objects;

/**
 * Which of a set of things the pointer is over, eased — <b>one</b> hovered thing at a time.
 *
 * <h2>Why one, and why that is the design rather than a limitation</h2>
 *
 * <p>A pointer is a single point. It is over one row or none, one node or none, and no amount of
 * interface design changes that. So this tracks <b>one current key and one previous one</b>, and the
 * two of them are the whole of the state:
 *
 * <ul>
 *   <li>{@link #amount} of the current key is the transition's own progress, 0 to 1.</li>
 *   <li>{@link #amount} of the previous key is <b>one minus</b> that progress, so it falls as the
 *       other rises.</li>
 *   <li>Every other key is zero, and stays zero without being remembered.</li>
 * </ul>
 *
 * <p>The obvious alternative — a map of key to {@link Tween}, one per row — is what this deliberately
 * is not. It would mean a tween for every row of every section, most of them permanently at zero,
 * allocated and kept and iterated over every frame; and the fade-out of the row you just left would
 * still need the previous key, so the map would not even remove the special case. Two fields and a
 * subtraction do the whole job.
 *
 * <h2>The two amounts always sum to one, and that is the property worth having</h2>
 *
 * <p>Because a row's highlight is a wash over a shared background, two rows lit at once is not a
 * smoother transition — it is two rows that both look half-selected, and a list where the eye cannot
 * tell which one the pointer is on. Summing to one means the crossfade is exactly a handover: as one
 * brightens the other dims by the same amount, and there is never a frame where both are lit or
 * neither is.
 *
 * <h2>A change restarts the transition rather than continuing it</h2>
 *
 * <p>Worth stating plainly, because it is a choice with a visible consequence. When the pointer moves
 * from A to B, the transition <b>restarts from zero</b>: B begins at dark and A at full, whatever
 * either of them was mid-fade.
 *
 * <p>The tempting alternative is to carry on from wherever each one was, which needs a tween per key —
 * and it is wrong for this UI for a reason that is about the crossfade invariant rather than about
 * cost. If A is half-lit and B is dark when the pointer moves, then continuing each from where it is
 * means A fades from a half and B brightens from nothing: the two sums are 0.5 and then 1.5, so the
 * list spends the transition with either a gap in it or a double-lit pair. Restarting keeps the sum at
 * exactly one for every frame of every transition.
 *
 * <p>The consequence, honestly: a pointer swept quickly across a list produces a trail of rows that
 * each flash to full and fade rather than a trail that barely lights. That reads as a glow following
 * the cursor, which is what the effect is for. A pointer that changes direction mid-transition
 * re-starts the crossfade, so a fast jitter between two rows settles a little later than it otherwise
 * would — which is not visible at 140 milliseconds, and is the price of the invariant above.
 *
 * <h2>Time is passed in, like everything else in this kit</h2>
 *
 * <p>Nothing here reads a clock, so two frames can be produced by two calls with two numbers. A test
 * asserts a fade by passing a larger millisecond value rather than by sleeping, which is the property
 * that makes every animation in this toolkit checkable at all.
 *
 * <h2>Keys are objects, and identity is by {@code equals}</h2>
 *
 * <p>The same rule as {@link Layout}'s slots, and for the same reason: this toolkit has no business
 * deciding how a caller names its rows. A row keyed by its index works, so does one keyed by a record,
 * so does one keyed by a quest id — and the two places this is used in practice do one of each.
 */
public final class Hover {

    private final Tween transition;

    private Object current;
    private Object previous;

    /**
     * A hover tracker at rest.
     *
     * <p>Built through {@link Motion#tween} rather than with a duration of its own, so the client's
     * motion setting applies to it without this class knowing the setting exists.
     */
    public Hover() {
        // `Motion.tween(1F)` and not `this(Tween.DEFAULT_MILLIS)`. The two look equivalent and are
        // not: the latter tells Motion to scale a constant, so the client's own default duration --
        // which is what a theme sets -- never reaches this object. See the other constructor.
        this.transition = Motion.tween(1F);
    }

    /** The same, over a chosen duration. */
    public Hover(long durationMillis) {
        // Through Motion, so a theme's motion setting reaches this without the class knowing the
        // setting exists -- and so turning motion off makes this snap along with everything else.
        //
        // The one-argument form is used by `Hover()`, and it has to be: passing `Tween.DEFAULT_MILLIS`
        // here instead asks Motion to scale *that* number, which is not the same as the client's
        // current default. A theme declaring zero motion was ignored by every default-constructed
        // Hover -- the hover had already been told 140 milliseconds by a constant, so the theme's
        // answer never reached it. That is the failure mode this constructor's own javadoc warns
        // about, one step in: not a control that builds its own Tween, but a control that builds one
        // with a duration it named itself.
        this.transition = Motion.tween(1F, durationMillis);
    }

    /**
     * Tells the tracker what the pointer is over now, and starts the crossfade if it changed.
     *
     * <p>Called once per frame, before anything asks for an amount. A key equal to the current one is a
     * no-op — which matters more than it looks, because a draw method runs sixty times a second and
     * restarting the transition on every frame would keep the highlight creeping and never arriving. On
     * screen that reads as a stutter.
     */
    public void update(Object keyNow, long nowMillis) {
        if (Objects.equals(keyNow, current)) {
            return;
        }

        // The key being left becomes the one that fades out, and any key before that is forgotten --
        // because a pointer cannot be over two things, so a crossfade has at most two subjects.
        previous = current;
        current = keyNow;

        // Restart, deliberately and for the reason in the class note: the two amounts must sum to one
        // at every instant, and carrying each key on from its own mid-fade value cannot do that.
        //
        // `snapTo` then `retarget` rather than a single call, because retarget to the same target is a
        // no-op by design -- and when the pointer moves from one row to another, the target *is* the
        // same one. That is precisely the case this class was written for, and the first version of it
        // got this wrong: the fade-out never ran, so the row you left went dark instantly while the row
        // you arrived at snapped bright. It looked like flicker rather than like motion, and a test
        // asserting both halves sum to one is what found it.
        transition.snapTo(0F);
        transition.retarget(1F, nowMillis);
    }

    /**
     * How hovered a key looks right now: 0 at rest, 1 fully hovered, and whatever is between.
     *
     * <p>An unknown key is 0 without being asked about, which is what makes this cheap for a list of
     * forty rows.
     */
    public float amount(Object key, long nowMillis) {
        if (key == null) {
            // Guarded before the comparison, because `Objects.equals(null, null)` is true -- so with
            // nothing hovered, a caller asking about "no key" would be told it was fully hovered.
            return 0F;
        }
        if (Objects.equals(key, current)) {
            return transition.value(nowMillis);
        }
        if (Objects.equals(key, previous)) {
            // The complement, not a second tween. The row being left started at 1 and the transition
            // runs to 1 meaning "the handover is complete", so its amount is exactly one minus that --
            // and one subtraction cannot drift from the other the way two tweens can.
            return 1F - transition.value(nowMillis);
        }
        return 0F;
    }

    /** The key the pointer is over, or null. */
    public Object current() {
        return current;
    }

    /** The key it was over before, which is the one still fading out. */
    public Object previous() {
        return previous;
    }

    /**
     * Forgets everything, at rest.
     *
     * <p>For a screen closing, or a list being rebuilt for a different quest — where the row keys mean
     * something else now, so a fade carried over would light up a row nobody is pointing at. Cheap
     * enough to call unconditionally.
     */
    public void clear() {
        current = null;
        previous = null;
        // Back to 1, which is the settled state: no transition in progress, nothing hovered.
        transition.snapTo(1F);
    }

    /**
     * Whether the crossfade has finished.
     *
     * <p>Exists so a caller <i>can</i> skip a frame's work, not so it must: every use in this project
     * draws regardless, because a row that is fading out is a row that still has something to draw.
     * It is here because "is it settled" is a reasonable question and the alternative is reaching into
     * the tween.
     */
    public boolean settled(long nowMillis) {
        return transition.settled(nowMillis);
    }

    @Override
    public String toString() {
        return "Hover(" + (current == null ? "nothing" : current)
                + (previous == null ? "" : ", leaving " + previous) + ")";
    }
}

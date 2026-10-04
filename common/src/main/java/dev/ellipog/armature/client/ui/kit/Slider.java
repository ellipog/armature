package dev.ellipog.armature.client.ui.kit;

/**
 * A slider's arithmetic: a value in a range, snapped to a step, and where its thumb sits.
 *
 * <h2>Why the arithmetic is its own class</h2>
 *
 * <p>Every fault a slider can have is here rather than in its drawing: a value off the step, a thumb
 * that cannot reach either end, a drag past the edge that keeps counting, a click that lands a pixel
 * away from where the thumb was put. The widget is a rectangle and three fills; this is the part
 * worth asserting, and it is game-free so a test drives it with numbers alone.
 *
 * <h2>Snapping, and which end wins</h2>
 *
 * <p>A value is snapped to the nearest step from {@code min}, rounded — so a 5% step on 1.0..1.5
 * offers exactly 1.00, 1.05 … 1.50, and both ends are reachable because both are steps from the
 * minimum. A step that does not divide the range does not make the maximum unreachable either: a raw
 * value at or past {@code max} snaps to {@code max} exactly, which is the reading a person expects
 * from a slider dragged to the end.
 */
public final class Slider {

    private final double min;
    private final double max;
    private final double step;
    private double value;

    /**
     * @param min   the lowest value, which is the left end of the track
     * @param max   the highest, which is the right end; must be above {@code min}
     * @param step  the smallest change one move makes; must be positive
     * @param value where the thumb starts, snapped into range
     */
    public Slider(double min, double max, double step, double value) {
        if (!(max > min)) {
            throw new IllegalArgumentException("a slider's max must be above its min: " + min + ".." + max);
        }
        if (!(step > 0)) {
            throw new IllegalArgumentException("a slider's step must be positive: " + step);
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = snap(value);
    }

    /** The value in force, always on a step and inside the range. */
    public double value() {
        return value;
    }

    /** Where the value sits in the range: 0 at the left end, 1 at the right. */
    public double fraction() {
        return (value - min) / (max - min);
    }

    /** This slider with a new value, snapped and clamped. The original is untouched. */
    public Slider withValue(double raw) {
        return new Slider(min, max, step, raw);
    }

    /** The value at a fraction of the track, snapped: what a click or a drag asks for. */
    public double valueAt(double fraction) {
        return snap(min + Math.max(0D, Math.min(1D, fraction)) * (max - min));
    }

    /**
     * The value a pointer at {@code x} asks for.
     *
     * <p>A track of no width answers the minimum rather than dividing by zero — a degenerate control
     * is a layout accident, and a value is still owed to the caller.
     */
    public double valueAtX(double x, int left, int width) {
        if (width <= 0) {
            return min;
        }
        return valueAt((x - left) / width);
    }

    /** The nearest step to a raw value, clamped into the range. Both ends are exact. */
    public double snap(double raw) {
        if (Double.isNaN(raw)) {
            return min;
        }
        if (raw <= min) {
            return min;
        }
        if (raw >= max) {
            return max;
        }
        double steps = Math.round((raw - min) / step);
        double snapped = min + steps * step;
        // Rounding a float sum can land a hair outside the range; the clamp is what keeps the ends
        // reachable for a step that does not divide the span.
        return Math.max(min, Math.min(max, snapped));
    }

    /** The smallest change one move makes, for a caller stepping with a key. */
    public double step() {
        return step;
    }
}

package dev.ellipog.armature.client.ui.kit;

/**
 * ARGB arithmetic: blending two colours, scaling one's alpha, and taking one apart.
 *
 * <h2>Everything is eight-digit ARGB, always</h2>
 *
 * <p>The same convention {@code ArmatureTheme} documents at length: a colour is
 * {@code 0xAARRGGBB} with the alpha written, never the six-digit form. Three of this class's four
 * methods are meaningless without an alpha channel, and the fourth would silently produce
 * {@code 0x00RRGGBB} — fully transparent — for a six-digit input, which draws nothing and reads as
 * the panel having disappeared.
 *
 * <h2>The blend is per-channel in sRGB, and that is what everything else does</h2>
 *
 * <p>Strictly, fading between two colours should happen in linear light: sRGB values are
 * gamma-encoded, so a mid-point blend of black and white is 128 in sRGB and about 188 in linear.
 * Every game UI blends in sRGB — vanilla's own {@code fillGradient} does, and it is what a person
 * comparing two screenshots of this mod against one of vanilla would see. Matching that matters more
 * than being correct, because a UI that is subtly right and visibly different from its own gradients
 * looks broken. Worth writing down so the next person does not "fix" it.
 *
 * <h2>Blending alpha is not the same as blending a colour</h2>
 *
 * <p>{@link #lerp} interpolates all four channels, which is right when one colour is being replaced by
 * another — a hover darkening a button. When a colour is being <b>faded</b> — a panel appearing — the
 * RGB should stay put and only the alpha should move, which is {@link #withAlpha}. Getting these
 * confused fades a red panel through pink, because the blend also walks the RGB towards whatever the
 * other endpoint is.
 */
public final class Colour {

    private Colour() {
    }

    /** The alpha channel, 0..255. */
    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** The colour without its alpha, so {@code 0x00RRGGBB}. */
    public static int rgb(int argb) {
        return argb & 0x00FFFFFF;
    }

    /** The same colour with a new alpha, 0..255. Clamped rather than wrapped. */
    public static int withAlpha(int argb, int newAlpha) {
        int clamped = newAlpha < 0 ? 0 : (newAlpha > 255 ? 255 : newAlpha);
        return (clamped << 24) | rgb(argb);
    }

    /** A colour semi-transparent, for a wash. Black at half opacity is {@code translucent(0, 0.5f)}. */
    public static int translucent(int argb, float factor) {
        return withAlpha(argb, Math.round(alpha(argb) * clamp01(factor)));
    }

    /** The same RGB at a given alpha, 0..1. The form most call sites actually want. */
    public static int alphaOf(int argb, float fraction) {
        return withAlpha(argb, Math.round(255 * clamp01(fraction)));
    }

    /**
     * A blend between two colours: {@code t == 0} gives {@code from}, {@code t == 1} gives {@code to}.
     *
     * <p>{@code t} is clamped, which is what makes this safe to hand an {@link Easing#BACK_OUT} result.
     * That curve deliberately passes 1 in the middle, and an unclamped lerp would push a channel past
     * 255 — where the cast to a byte wraps, so a colour on its way to white flashes through black for
     * a frame or two. Clamping flattens the overshoot instead, which loses the bounce and draws
     * nothing wrong.
     *
     * @return the blended colour, or {@code from} exactly when {@code t} is 0 and {@code to} exactly
     *     when it is 1 — so an animation that has settled is bit-identical to the colour it settled on,
     *     and a caller comparing a settled tween against a constant is comparing the same number
     */
    public static int lerp(int from, int to, float t) {
        float clamped = clamp01(t);
        if (clamped == 0F) {
            return from;
        }
        if (clamped == 1F) {
            return to;
        }

        int a = Math.round(alpha(from) + (alpha(to) - alpha(from)) * clamped);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * clamped);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * clamped);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * clamped);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Whether a colour would draw nothing at all, so a caller can skip the call. */
    public static boolean invisible(int argb) {
        return alpha(argb) == 0;
    }

    /**
     * A colour from a hex string, for a theme file a person wrote.
     *
     * <h2>Six digits or eight, and six means opaque</h2>
     *
     * <p>{@code #RRGGBB} is what anyone writing a colour by hand will type, so it is accepted and read
     * as fully opaque. {@code #AARRGGBB} is accepted too, because a theme genuinely needs translucent
     * values — {@code rowHover} and {@code nodeDim} are washes whose whole nature is their alpha.
     *
     * <p><b>Opposite to the class's own rule for Java code, deliberately.</b> In source a colour without
     * alpha is the mistake this class's note warns about, because the value is computed rather than
     * read: {@code 0x00RRGGBB} is transparent black and draws nothing. A hex string in a config file is
     * read by a human and checked by this method, so there is no silent failure to prevent — and
     * requiring eight digits would mean every theme file repeating {@code ff} forty times, which is
     * exactly the kind of friction that stops people theming anything.
     *
     * <p>The leading {@code #} is optional and whitespace is ignored, for the same reason: it is a
     * number a person typed into a file, and refusing {@code 1e1e2e} for want of a hash would be
     * pedantry with a support cost.
     *
     * @return the colour, or {@code null} when the text is not a hex colour at all — so a caller can
     *     name the offending value in a message rather than drawing the wrong colour silently
     */
    public static Integer fromHex(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() != 6 && trimmed.length() != 8) {
            return null;
        }
        // Long rather than Integer, because 8 hex digits is exactly 32 bits and the top bit set makes
        // an int negative -- `Integer.parseInt("ff000000", 16)` throws rather than returning a value.
        // The parse that looks more careful is the one that fails on every opaque colour.
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.digit(trimmed.charAt(i), 16) < 0) {
                return null;
            }
        }
        long value = Long.parseLong(trimmed, 16);
        return trimmed.length() == 6 ? (int) (0xFF000000L | value) : (int) value;
    }

    /** A colour as {@code #AARRGGBB}, which is the form a theme file should carry. */
    public static String toHex(int argb) {
        return String.format("#%08X", argb);
    }

    /**
     * The same colour multiplied towards black or white, for a general lightening or darkening.
     *
     * <p>Not a blend between two named colours — that is {@link lerp}. This is the "make this a bit
     * darker without deciding what darker is" operation, which is what a theme's derived shades need.
     *
     * @param amount positive lightens towards white, negative darkens towards black. 1 is white,
     *     -1 is black.
     */
    public static int shade(int argb, float amount) {
        float clamped = amount < -1F ? -1F : (amount > 1F ? 1F : amount);
        int targetChannel = clamped >= 0F ? 0xFF : 0x00;
        float mix = clamped >= 0F ? clamped : -clamped;

        int r = Math.round(((argb >> 16) & 0xFF) + (targetChannel - ((argb >> 16) & 0xFF)) * mix);
        int g = Math.round(((argb >> 8) & 0xFF) + (targetChannel - ((argb >> 8) & 0xFF)) * mix);
        int b = Math.round((argb & 0xFF) + (targetChannel - (argb & 0xFF)) * mix);

        // Alpha is preserved, deliberately: "darker" is a statement about the colour, not about whether
        // the thing is there. Shading the alpha too would make a hover darken a panel towards
        // invisible, which is not what any caller means.
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * How bright a colour is, as the sum of its three channels. Alpha is ignored.
     *
     * <h2>Why this exists, and why it is not perceptual luminance</h2>
     *
     * <p>Because one question in this toolkit is "which of these two colours reads as the lighter
     * one?", and it has to be answered from data rather than chosen by hand. The scrollbar is the
     * caller: its hover and held states are derived from the thumb by {@link #shade}, and the
     * <b>direction</b> of that shade has to be away from the track — a theme whose grip is dark on a
     * light groove must be brightened by a hover, and one whose grip is light on a dark groove must be
     * brightened too, in the sense that matters. Hand-picking per theme would be thirty values nobody
     * can keep consistent; asking the two colours what their relationship is makes it one rule.
     *
     * <p>The weights are deliberately absent. A perceptual figure (0.2126 R + 0.7152 G + 0.0722 B)
     * would be the right answer to "how bright does this look", and it is the wrong answer to the
     * question here, which is only ever a comparison between two colours in one theme. A plain sum
     * orders colours the same way a perceptual figure does for every pair this is asked about — a
     * grey against a darker grey — and it has the property that matters for a rule like this one:
     * it can be checked by reading it. {@code ThemeTest} used exactly this sum for its own yardstick
     * before this method existed, and that is the strongest evidence that it is the right one: the
     * test's claim and the production rule now measure brightness the same way instead of by two
     * implementations that agree until one is changed.
     *
     * <p><b>Alpha is excluded, and the reason is the same as {@link #shade}'s.</b> "Lighter" is a
     * statement about a colour, not about whether the thing is there; including alpha would make a
     * translucent colour read as darker than the same colour opaque, and the scrollbar's own track is
     * a wash in several themes.
     */
    public static int luminance(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }

    private static float clamp01(float value) {
        return value < 0F ? 0F : (value > 1F ? 1F : value);
    }
}

package dev.ellipog.armature.client.ui.kit;

/**
 * What counts as one character to a caret, a mark and a deletion: a code point -- one {@code char} for
 * everything in the basic plane, two for everything outside it.
 *
 * <h2>Why the text models cannot count chars</h2>
 *
 * <p>They did, and the fault has an exact trigger: a character outside the basic plane -- an emoji, a
 * rare CJK character -- is two {@code char}s, so Left stepped between its halves, Backspace left half of
 * it behind, and a click could put the caret somewhere no character boundary exists. Those characters
 * are exactly what an input method commits, and committed text is all an input method hands this game:
 * Minecraft 1.21.1 has no composition or preedit API at all -- the composition window is the operating
 * system's own -- so what arrives at a widget is the finished character, and handling it as one character
 * is the part of "IME" a widget on this platform can actually own.
 *
 * <p>A character here is a code point, not a grapheme cluster: a family emoji built from several code
 * points is several characters, and this class does not pretend otherwise. That is the same rule
 * vanilla's own {@code Util.offsetByCodepoints} follows, which is the argument for it -- a field should
 * not be wrong about where its caret is in a way Minecraft's own field is not.
 *
 * <p>Game-free by design: four functions on a {@code CharSequence} and an index, asserted in
 * {@code CodepointsTest}, and used by {@link TextField} and {@link TextArea} so the two cannot disagree
 * about where a character ends.
 */
public final class Codepoints {

    private Codepoints() {
    }

    /**
     * Where the character that ends at {@code at} begins: one back, or two over a surrogate pair.
     *
     * <p>Clamped at both ends, so a caller does not have to be right about its own index to be safe.
     */
    public static int before(CharSequence text, int at) {
        int end = Math.max(0, Math.min(text.length(), at));
        if (end >= 2 && Character.isLowSurrogate(text.charAt(end - 1))
                && Character.isHighSurrogate(text.charAt(end - 2))) {
            return end - 2;
        }
        return Math.max(0, end - 1);
    }

    /**
     * Where the character that starts at {@code at} ends: one forward, or two over a surrogate pair.
     *
     * <p>Clamped at both ends for the same reason as {@link #before}.
     */
    public static int after(CharSequence text, int at) {
        int from = Math.max(0, Math.min(text.length(), at));
        if (from + 2 <= text.length() && Character.isHighSurrogate(text.charAt(from))
                && Character.isLowSurrogate(text.charAt(from + 1))) {
            return from + 2;
        }
        return Math.min(text.length(), from + 1);
    }

    /**
     * A position moved out of the middle of a pair -- to the pair's start, because half a character is
     * not a place a caret can be. A position that is already a character boundary is left where it is,
     * and anything outside the text is clamped to it.
     */
    public static int snap(CharSequence text, int position) {
        int at = Math.max(0, Math.min(text.length(), position));
        if (at > 0 && at < text.length() && Character.isLowSurrogate(text.charAt(at))
                && Character.isHighSurrogate(text.charAt(at - 1))) {
            return at - 1;
        }
        return at;
    }

    /**
     * The longest prefix of {@code text} no longer than {@code limit} that is whole characters: a limit
     * that lands between a pair's halves keeps neither half.
     */
    public static String prefix(CharSequence text, int limit) {
        if (text.length() <= limit) {
            return text.toString();
        }
        int end = Math.max(0, limit);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.subSequence(0, end).toString();
    }
}

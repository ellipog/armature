package dev.ellipog.armature.client.ui.kit;

import java.util.Random;

/**
 * A stand-in font with the shape of the game's, for the fit searches whose answers rest on that shape.
 *
 * <h2>What it copies, and why each part is here</h2>
 *
 * <p>The searches in {@link TextWrap} bisect, and a bisection is only the old character walk's answer
 * when a longer prefix is never narrower than a shorter one. {@link Measure#monospace} always has that
 * property, so it cannot tell a search that relies on it from one that does not. This copies the parts
 * of the game font's string width that bear on it, from {@code StringDecomposer.iterateFormatted}:
 *
 * <ul>
 *   <li>proportional advances, never negative;</li>
 *   <li>a section sign styles what follows rather than being drawn: bold adds a pixel per glyph after it,
 *       a reset or a colour ends that, a trailing section sign is nothing, and the character after one is
 *       read as its code whatever it is;</li>
 *   <li>half a surrogate pair is drawn as the replacement character, and a whole pair as its own glyph,
 *       which is sometimes narrower than the replacement and sometimes wider, so a prefix ending inside a
 *       pair can be wider than the prefix one longer. That is the one place the order breaks.</li>
 * </ul>
 *
 * <p>The characters outside ASCII are written as code points rather than escapes or glyphs, so the
 * source says which character each one is.
 */
final class GameShapedFont {

    /** The formatting code introducer, the section sign. */
    static final char SECTION = (char) 0xA7;

    /** A space that {@code \s} does not match and {@code strip} does not trim. */
    static final char NO_BREAK_SPACE = (char) 0xA0;

    /** A space that {@code strip} trims but {@code \s} does not match, so the word split keeps it. */
    static final char EM_SPACE = (char) 0x2003;

    /** A vertical tab, which {@code \s} matches. */
    private static final char VERTICAL_TAB = (char) 0x0B;

    /** The first emoji, U+1F600: the pairs below are it and the code points after it. */
    private static final int FIRST_PAIR = 0x1F600;

    /** What half a surrogate pair is drawn as. */
    private static final int REPLACEMENT = 9;

    /** The same font as a {@link Measure}, nine pixels a line like the game's at a scale of one. */
    static final Measure MEASURE = Measure.of(GameShapedFont::width, 9);

    private GameShapedFont() {
    }

    static int width(String text) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION) {
                if (i + 1 >= text.length()) {
                    break;
                }
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'l') {
                    bold = true;
                }
                else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    bold = false;
                }
                i++;
                continue;
            }
            int advance;
            if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                // Five to eleven: either side of the replacement character, so both orders occur.
                advance = 5 + Character.toCodePoint(c, text.charAt(i + 1)) % 7;
                i++;
            }
            else if (Character.isSurrogate(c)) {
                advance = REPLACEMENT;
            }
            else {
                advance = switch (c) {
                    case 'i', 'l', '!', '.', ',', '\'' -> 2;
                    case ' ', 't', 'I', '[', ']' -> 4;
                    case 'f', 'k', '(', ')' -> 5;
                    case 'W', 'M', '@', EM_SPACE -> 8;
                    default -> 6;
                };
            }
            width += advance + (bold ? 1 : 0);
        }
        return width;
    }

    /** One whole surrogate pair: the emoji {@code offset} code points after the first. */
    static String pair(int offset) {
        return new String(Character.toChars(FIRST_PAIR + offset));
    }

    // ------------------------------------------------------------------
    // Random text with every shape the searches have to get right
    // ------------------------------------------------------------------

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzWMI.,'!ft";

    /** The gaps a word split meets: the whitespace {@code \s} matches, and two spaces it does not. */
    private static final String GAPS = "\t" + VERTICAL_TAB + "\f\r" + NO_BREAK_SPACE + EM_SPACE;

    /** The letters a formatting code is tried with: bold, reset, a colour, and two that change nothing. */
    private static final String CODES = "lLra0kx";

    /**
     * One random piece of text: a word, a gap, a formatting code, a pair or half of one.
     *
     * <p>{@code newlines} lets a caller that wraps whole texts have paragraphs; a caller that fits one
     * line passes false.
     */
    static String piece(Random random, boolean newlines) {
        int kind = random.nextInt(newlines ? 13 : 12);
        return switch (kind) {
            case 0, 1, 2, 3 -> word(random, 1 + random.nextInt(8));
            case 4 -> word(random, 12 + random.nextInt(40));              // longer than a narrow column
            case 5 -> " ".repeat(1 + random.nextInt(4));
            case 6 -> String.valueOf(GAPS.charAt(random.nextInt(GAPS.length())));
            case 7 -> String.valueOf(SECTION) + CODES.charAt(random.nextInt(CODES.length()));
            // A section sign on its own: at the end of a text, or reading whatever piece comes next as its
            // code -- half a pair included.
            case 8 -> String.valueOf(SECTION);
            case 9, 10 -> pair(random.nextInt(80));
            case 11 -> {
                String whole = pair(random.nextInt(80));
                yield String.valueOf(whole.charAt(random.nextInt(2)));     // half a pair on its own
            }
            default -> "\n".repeat(1 + random.nextInt(2));
        };
    }

    /** A random text of about {@code length} characters, built from {@link #piece}s. */
    static String text(Random random, int length, boolean newlines) {
        StringBuilder out = new StringBuilder();
        while (out.length() < length) {
            out.append(piece(random, newlines));
        }
        return out.toString();
    }

    private static String word(Random random, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        return out.toString();
    }
}

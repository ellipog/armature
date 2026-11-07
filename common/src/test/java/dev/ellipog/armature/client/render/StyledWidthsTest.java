package dev.ellipog.armature.client.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The memo behind {@code styledWidth}: the font's own width of a run, one memo per pairing of bold and italic.
 *
 * <h2>What is held</h2>
 *
 * <p>That a remembered width is the width the font gives, in every style; that the font is asked once per
 * string and style until the epoch moves; that two styles of one string never share an answer (bold is wider,
 * and a bold run laid out at its plain width runs past its column); and that applying the run's scale after
 * the lookup gives exactly what applying it to a fresh measurement gave, which is the old arithmetic, kept
 * here as the oracle.
 */
@DisplayName("Styled widths")
class StyledWidthsTest {

    /** How many times the stand-in font was asked about each string in each style. */
    private final Map<String, Integer> asked = new HashMap<>();

    /** A stand-in font: six a glyph, one more a glyph when bold, and two more in all when italic. */
    private int font(String text, boolean bold, boolean italic) {
        asked.merge(text + "|" + bold + "|" + italic, 1, Integer::sum);
        return text.length() * (bold ? 7 : 6) + (italic ? 2 : 0);
    }

    private static final List<String> TEXTS = List.of("", "a", "bold", "a longer run of prose", "été");

    @Test
    @DisplayName("answers what the font answers, in every style")
    void answersWhatTheFontAnswers() {
        GuiGraphicsRenderer.StyledWidths widths = new GuiGraphicsRenderer.StyledWidths(this::font, () -> 0L);
        for (String text : TEXTS) {
            for (boolean bold : new boolean[] {false, true}) {
                for (boolean italic : new boolean[] {false, true}) {
                    int fresh = text.length() * (bold ? 7 : 6) + (italic ? 2 : 0);
                    assertEquals(fresh, widths.width(text, bold, italic), text + " bold=" + bold + " italic=" + italic);
                }
            }
        }
    }

    @Test
    @DisplayName("asks the font once per string and style, and the styles never share an answer")
    void asksOncePerStringAndStyle() {
        GuiGraphicsRenderer.StyledWidths widths = new GuiGraphicsRenderer.StyledWidths(this::font, () -> 0L);
        for (int frame = 0; frame < 5; frame++) {
            for (String text : TEXTS) {
                widths.width(text, false, false);
                widths.width(text, true, false);
                widths.width(text, false, true);
                widths.width(text, true, true);
            }
        }
        assertEquals(TEXTS.size() * 4, asked.size(), "every string in every style was measured: " + asked);
        asked.forEach((key, count) -> assertEquals(1, count, key + " was measured more than once"));

        assertEquals(28, widths.width("bold", true, false));
        assertEquals(24, widths.width("bold", false, false), "the plain answer is not the bold one");
        assertEquals(26, widths.width("bold", false, true));
        assertEquals(30, widths.width("bold", true, true));
    }

    @Test
    @DisplayName("forgets everything when the epoch moves, as a font or text-scale change moves it")
    void anEpochMoveForgets() {
        AtomicLong epoch = new AtomicLong();
        GuiGraphicsRenderer.StyledWidths widths = new GuiGraphicsRenderer.StyledWidths(this::font, epoch::get);
        widths.width("run", true, true);
        widths.width("run", true, true);
        assertEquals(1, asked.get("run|true|true"));

        epoch.incrementAndGet();
        widths.width("run", true, true);
        assertEquals(2, asked.get("run|true|true"), "a new epoch measures again");
    }

    @Test
    @DisplayName("the run's scale applied after the lookup is the old arithmetic on a fresh measurement")
    void theScaleIsAppliedAsBefore() {
        GuiGraphicsRenderer.StyledWidths widths = new GuiGraphicsRenderer.StyledWidths(this::font, () -> 0L);
        float[] scales = {1F, 0.5F, 1.25F, 1.5F, 2F, 2.75F, 3F};
        double[] textScales = {1.0, 0.75, 1.1, 1.5, 2.0};
        for (String text : TEXTS) {
            for (float scale : scales) {
                for (double textScale : textScales) {
                    boolean bold = text.length() % 2 == 0;
                    // The oracle: styledWidth before the memo, measuring fresh and scaling the result.
                    int plainFresh = text.length() * (bold ? 7 : 6);
                    int before = (int) Math.round(plainFresh * scale * textScale);
                    int after = (int) Math.round(widths.width(text, bold, false) * scale * textScale);
                    assertEquals(before, after, text + " at " + scale + " x " + textScale);
                }
            }
        }
    }
}

package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link GuiRenderer} that records what it was asked to draw instead of drawing it.
 *
 * <h2>Why this is the second implementation, and why that matters more than it looks</h2>
 *
 * <p>The plan's reason for scheduling the seam last was fair and worth answering directly: <i>"an
 * interface wrapping one implementation is an indirection nobody can evaluate"</i>. A seam whose only
 * implementation forwards to the thing it abstracts is a layer of indirection with nothing on the
 * other side, and nothing about it can be tested — you cannot tell a correct forwarding from a wrong
 * one without a client, which is the situation the seam was supposed to improve.
 *
 * <p>This is the answer. It is not a renderer; it is a <b>reader</b>. Every call it receives is
 * recorded as a value, so a test can ask what would have been drawn without a window, a font or an
 * item registry — and the questions that become askable are the ones that were previously only
 * answerable by looking at a screenshot:
 *
 * <ul>
 *   <li>Does a hovered node draw an outline that follows its shape rather than a box around it?</li>
 *   <li>Is a label's backdrop behind the label, and is the label inside the node it names?</li>
 *   <li>Does an outline's four fills actually enclose the rectangle they claim to?</li>
 *   <li>Is a clip opened and closed exactly once, even on the path that returns early?</li>
 * </ul>
 *
 * <p>That is what turns this from a deferral into work the tests hold. It also makes the seam
 * itself evaluable: a forwarding bug — {@code fill} passing its arguments in the wrong order, say —
 * is a failing assertion rather than a picture somebody has to notice.
 *
 * <h2>What it deliberately does not simulate</h2>
 *
 * <p>No font metrics and no item rendering. {@link #textWidth} returns a fixed width per character and
 * {@link #icon} always reports success, because a test that needs real metrics is a test that needs a
 * client and there is no point pretending otherwise. What it records is the <i>sequence and geometry
 * of calls</i>, which is the part that is about this code rather than about Minecraft's renderer.
 *
 * <p>{@link #withCharWidth} exists so a caller can reason about wrapping and truncation with
 * arithmetic it chose, the same way {@code Measure.monospace} works for the layout tests.
 */
public final class RecordingRenderer implements GuiRenderer {

    /**
     * One recorded call.
     *
     * <p>A record with an operation name rather than a class per operation: the assertions read
     * {@code fills()} and {@code texts()}, and a test that wants "was this label drawn over that
     * rectangle" compares numbers from two lists. Six types would be six visitors.
     */
    public record Call(Op op, int x, int y, int x2, int y2, int argb, String text,
                       java.util.List<GuiRenderer.StyledRun> runs, Source source, Amount amount) {

        /**
         * Where a scaled texture draw read from, in the texture's own pixels.
         *
         * <p>A nested record rather than four more fields on the call, because it belongs to exactly
         * one operation. A tiling test asserts the destination boxes are adjacent; a cover test
         * asserts the source is centred and matches the destination's aspect — and the second kind
         * of assertion should not have to read past three fields that are always zero.
         */
        public record Source(float u, float v, int sourceWidth, int sourceHeight,
                             int textureWidth, int textureHeight) {
        }

        /**
         * The one number an operation carries that is not geometry.
         *
         * <p>Two operations have one, and they are different kinds of number: a turn's angle in degrees, and
         * a shadowed line's size as a factor. One field rather than two, because no call is ever both — and
         * because a record with a field per operation would be a record that grows every time this seam does.
         * It is a {@code float} for the same reason both of those are: a turn can be fractional, and a size
         * almost always is.
         */
        public record Amount(float value) {
        }

        /** A call with no runs, no source and no number: every op but styled text, a scaled texture, a turn
         * and a shadowed label. */
        public Call(Op op, int x, int y, int x2, int y2, int argb, String text) {
            this(op, x, y, x2, y2, argb, text, java.util.List.of(), null, null);
        }

        /** A call with runs but nothing else: styled text, which is the only one that carries them. */
        public Call(Op op, int x, int y, int x2, int y2, int argb, String text,
                    java.util.List<GuiRenderer.StyledRun> runs) {
            this(op, x, y, x2, y2, argb, text, runs, null, null);
        }

        /** A call that read from a texture: the scaled blit, whose source region is its own data. */
        public Call(Op op, int x, int y, int x2, int y2, int argb, String text,
                    java.util.List<GuiRenderer.StyledRun> runs, Source source) {
            this(op, x, y, x2, y2, argb, text, runs, source, null);
        }

        /** A turn: the pivot is the coordinates and the angle is its own number. */
        public static Call turn(Op op, int pivotX, int pivotY, float degrees) {
            return new Call(op, pivotX, pivotY, 0, 0, 0, "", java.util.List.of(), null,
                    new Amount(degrees));
        }

        /** A shadowed line: the position, the colour and the text are the call, and the size is its number. */
        public static Call shadowed(int x, int y, int argb, String text, float scale) {
            return new Call(Op.SHADOWED_TEXT, x, y, 0, 0, argb, text, java.util.List.of(), null,
                    new Amount(scale));
        }

        /** Whether this call is a filled rectangle covering the given point. */
        public boolean covers(int px, int py) {
            return op == Op.FILL && px >= x && px < x2 && py >= y && py < y2;
        }

        @Override
        public String toString() {
            return switch (op) {
                case FILL -> "fill(" + x + "," + y + " -> " + x2 + "," + y2 + ", " + hex(argb) + ")";
                case TEXT -> "text(\"" + text + "\" at " + x + "," + y + ", " + hex(argb) + ")";
                case SHADOWED_TEXT -> "shadowedText(\"" + text + "\" at " + x + "," + y + ")";
                case STYLED_TEXT -> "styledText(\"" + text + "\" in " + runs.size() + " run(s) at "
                        + x + "," + y + ")";
                case ICON -> "icon(" + x + "," + y + " " + x2 + "px)";
                case FACE -> "face(" + text + " at " + x + "," + y + " " + x2 + "px)";
                case TEXTURE -> "texture(" + text + " at " + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case SPRITE -> "sprite(" + text + " at " + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case TURN -> "turn(about " + x + "," + y + " by "
                        + (amount == null ? "?" : amount.value() + "\u00b0") + ")";
                case UNTURN -> "unturn";
                case BLUR -> "blur(yes)";
                case CLIP -> "clip(" + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case UNCLIP -> "unclip";
                case FLUSH -> "flush";
                case BATCH -> "batch";
                case END_BATCH -> "endBatch";
            };
        }

        private static String hex(int argb) {
            return String.format("#%08X", argb);
        }
    }

    /** What a recorded call was. */
    public enum Op {
        FILL, TEXT, SHADOWED_TEXT, STYLED_TEXT, ICON, FACE, TEXTURE, SPRITE,
        TURN, UNTURN, BLUR, CLIP, UNCLIP, FLUSH, BATCH, END_BATCH
    }

    private final List<Call> calls = new ArrayList<>();
    private final int charWidth;
    private final int lineHeight;
    private final boolean iconsDraw;
    private final Map<ResourceLocation, GuiRenderer.TextureSize> textureSizes = new HashMap<>();

    private int openClips;
    private int deepestClip;
    private int clippedAfterStop;
    private int batches;

    /**
     * Open turns, and the same three numbers a clip keeps.
     *
     * <p>A leaked turn is not the same fault as a leaked clip and is worth its own count: a clip left open
     * hides drawing that was meant to be seen, and a turn left open <b>turns</b> everything after it, which
     * presents as a whole frame drawn at an angle from one bad picture. Both are checked for the same reason
     * — a scope's contract is the thing a recorder can hold and a screenshot cannot.
     */
    private int openTurns;
    private int deepestTurn;
    private int strayTurnPops;

    private RecordingRenderer(int charWidth, int lineHeight, boolean iconsDraw) {
        this.charWidth = charWidth;
        this.lineHeight = lineHeight;
        this.iconsDraw = iconsDraw;
    }

    /**
     * A recorder with the given metrics. Six pixels a character, ten a line, icons drawing — the same
     * stand-in the kit's own layout tests use, so arithmetic in a test is checkable by hand.
     */
    public static RecordingRenderer create() {
        return new RecordingRenderer(6, 10, true);
    }

    /** A recorder with a chosen character width, for a test that needs a label to fit or not. */
    public static RecordingRenderer withCharWidth(int charWidth) {
        return new RecordingRenderer(charWidth, 10, true);
    }

    /** A recorder whose {@link #icon} reports that it drew nothing, for the fallback path. */
    public static RecordingRenderer withoutIcons() {
        return new RecordingRenderer(6, 10, false);
    }

    // ------------------------------------------------------------------
    // GuiRenderer
    // ------------------------------------------------------------------

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        calls.add(new Call(Op.FILL, left, top, right, bottom, argb, ""));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded with the player's id in the text field and the box in {@code argb}, so a test can ask
     * <i>whose</i> face was drawn where. The id is the only thing a caller chooses about a face, and a
     * recording that dropped it could not tell two members' rows apart.
     */
    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        calls.add(new Call(Op.FACE, boxX, boxY, box, box, 0, player == null ? "" : player.toString()));
        return iconsDraw;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Recorded rather than ignored</b>, and that is the whole value of it being here. A flush is
     * where a caller declares a layering boundary — "everything up to here is behind everything after
     * it" — so a test can assert the boundary exists by finding the marker between two draws. An
     * implementation that silently did nothing would make a screen's z-order fix
     * unassertable, which is exactly the class of defect it was written to fix: an item icon landing on
     * top of a button, ordered by batching rather than by the order the code drew them in.
     */
    @Override
    public void flush() {
        calls.add(new Call(Op.FLUSH, 0, 0, 0, 0, 0, ""));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded as an opening and a closing marker around whatever the supplier draws, so a test can assert
     * both halves: that a region was batched, and that its drawing happened <i>inside</i> the markers rather
     * than after them. The supplier runs for real — a recorder that skipped it would record an empty region
     * and could not answer "is this region one batch" about anything.
     */
    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        calls.add(new Call(Op.BATCH, 0, 0, 0, 0, 0, ""));
        batches++;
        try {
            return draw.get();
        }
        finally {
            calls.add(new Call(Op.END_BATCH, 0, 0, 0, 0, 0, ""));
        }
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        calls.add(new Call(Op.TEXT, x, y, 0, 0, argb, text));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Its own op rather than a fourth field on {@link Op#TEXT}, because the two are different pictures and
     * the difference is the whole reason the method exists: a test asserting that a label floating over a
     * canvas asked for vanilla's shadow, and one asserting that every other label did <i>not</i>, are both
     * reading this distinction. A recorder that folded them together could not tell a caller that stopped
     * asking for a shadow from one that started.
     */
    @Override
    public void shadowedText(String text, int x, int y, int argb, float scale) {
        calls.add(Call.shadowed(x, y, argb, text, scale));
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        StringBuilder whole = new StringBuilder();
        for (StyledRun run : runs) {
            whole.append(run.text());
        }
        calls.add(new Call(Op.STYLED_TEXT, x, y, 0, 0, argb, whole.toString(), java.util.List.copyOf(runs)));
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        if (text == null) {
            return 0;
        }
        // Bold is one pixel wider per glyph, which is what the game's font does, and a scaled run is that
        // much larger throughout -- so a fake that ignored either could not tell a correct layout from a
        // wrong one.
        return Math.round(text.length() * (charWidth + (bold ? 1 : 0)) * scale);
    }

    @Override
    public int textWidth(String text) {
        return text == null ? 0 : text.length() * charWidth;
    }

    @Override
    public int lineHeight() {
        return lineHeight;
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        calls.add(new Call(Op.ICON, boxX, boxY, box, box, 0, ""));
        return iconsDraw;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded with the texture's path in the text field and the box in the coordinates, so a test
     * can ask <i>which</i> image was drawn where — the path is the only thing a caller chooses, and a
     * recording that dropped it could not tell the pinned star from the empty one.
     */
    @Override
    public void texture(ResourceLocation texture, int x, int y, int width, int height) {
        calls.add(new Call(Op.TEXTURE, x, y, x + width, y + height, 0, texture.toString()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded like {@link #texture}, with three more things a tiling or covering test needs: the
     * tint in {@code argb}, the source region in {@link Call#source()}, and the destination box in the
     * coordinates. Dropping the source would make the difference between whole-texture tiling and
     * centred cover unassertable, which is exactly the pair of draw calls this operation exists for.
     */
    @Override
    public void scaled(ResourceLocation texture, int x, int y, int width, int height,
                       float u, float v, int sourceWidth, int sourceHeight,
                       int textureWidth, int textureHeight, int argb) {
        calls.add(new Call(Op.TEXTURE, x, y, x + width, y + height, argb, texture.toString(),
                java.util.List.of(), new Call.Source(u, v, sourceWidth, sourceHeight,
                        textureWidth, textureHeight)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Its own op, recorded with the sprite's <b>id</b> in the text field and the tint in {@code argb} —
     * which is everything a caller chooses, and therefore everything a test can be wrong about. Kept apart
     * from {@link Op#TEXTURE} deliberately: the two lookup paths fail differently (an absent file draws
     * nothing, an unknown sprite draws the game's marker), so a test that could not tell which one a picture
     * element asked for could not hold either behaviour.
     */
    @Override
    public void sprite(ResourceLocation atlasSprite, int x, int y, int width, int height, int argb) {
        if (atlasSprite == null) {
            calls.add(new Call(Op.SPRITE, x, y, x + width, y + height, argb, ""));
            return;
        }
        calls.add(new Call(Op.SPRITE, x, y, x + width, y + height, argb, atlasSprite.toString()));
    }

    /**
     * Declares a texture's size for {@link #textureSize}, the way a resource pack would.
     *
     * <p>A settable map rather than a fixed list, and the mirror of {@code withoutIcons}: a test that
     * wants a tile at 24 by 16 says so, and a test that wants the unseeded path says nothing — which
     * is the only way "an image with no readable size draws nothing" can be driven without a client.
     */
    public void putTextureSize(ResourceLocation texture, int width, int height) {
        textureSizes.put(texture, new GuiRenderer.TextureSize(width, height));
    }

    /** The stamp this recorder reports per texture; see {@link #putTextureSize} and {@link #textureStamp}. */
    private final Map<ResourceLocation, Long> textureStamps = new HashMap<>();

    /**
     * Declares that a texture's resource has been replaced, the way a pack reload does.
     *
     * <p>For the one thing a caller caching a size has to survive: the file behind an id changing while
     * the id does not. A recorder with no stamps answers a constant, which is the honest report for one
     * that reads no resource manager — nothing it reported can go stale — so a test that wants the
     * invalidation has to say the resource moved.
     */
    public void bumpTextureStamp(ResourceLocation texture) {
        textureStamps.merge(texture, 1L, Long::sum);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Constant until {@link #bumpTextureStamp} says otherwise, which is what a caller that reads no
     * resource manager should report.
     */
    @Override
    public long textureStamp(ResourceLocation texture) {
        return textureStamps.getOrDefault(texture, 0L);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answers from what {@link #putTextureSize} was told; nothing is read from disk. A texture
     * nobody declared is absent, which is the honest report for a headless recorder and the case a
     * caller must survive.
     */
    @Override
    public Optional<GuiRenderer.TextureSize> textureSize(ResourceLocation texture) {
        return Optional.ofNullable(textureSizes.get(texture));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded, and answers like {@link #icon}: a screen that falls back to a scrim when there is no
     * blur is a path worth being able to drive, and a recorder that always said yes could not drive it.
     */
    @Override
    public boolean blur(float partialTick) {
        calls.add(new Call(Op.BLUR, 0, 0, 0, 0, 0, ""));
        return iconsDraw;
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        calls.add(new Call(Op.CLIP, left, top, right, bottom, 0, ""));
        openClips++;
        deepestClip = Math.max(deepestClip, openClips);

        // An anonymous class rather than a lambda, and the reason is a contract rather than a style:
        // the seam says closing twice is a no-op, and a lambda cannot remember that it has been
        // called. My first version returned a lambda, and the assertion below failed -- correctly,
        // because the recorder was not modelling the thing it exists to model.
        //
        // That is worth keeping as a note: a test double that does not implement the contract being
        // tested does not report a wrong contract, it reports the double. The failure pointed at the
        // recorder, which is where the bug was.
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                calls.add(new Call(Op.UNCLIP, 0, 0, 0, 0, 0, ""));
                openClips--;
                if (openClips < 0) {
                    // A pop with nothing pushed. Counted rather than thrown, so a test can assert that
                    // it did not happen without the failure arriving as an exception from inside a
                    // recorder.
                    clippedAfterStop++;
                    openClips = 0;
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded as an opening and a closing marker around whatever the supplier draws, exactly as a batch
     * and a clip are, and for the same reason: the assertion worth having is not "a turn happened" but "the
     * picture landed <i>inside</i> the turn". The supplier runs for real, because a recorder that skipped it
     * would record an empty turn and could not answer the question.
     *
     * <p>The same double-close guard {@link #clip} has, and it is not symmetry: a second pop here would
     * remove a frame the caller pushed, so a recorder that counted it twice would report a balanced stack for
     * an unbalanced one.
     */
    @Override
    public Scoped turned(int pivotX, int pivotY, float degrees) {
        calls.add(Call.turn(Op.TURN, pivotX, pivotY, degrees));
        openTurns++;
        deepestTurn = Math.max(deepestTurn, openTurns);
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                calls.add(new Call(Op.UNTURN, 0, 0, 0, 0, 0, ""));
                openTurns--;
                if (openTurns < 0) {
                    strayTurnPops++;
                    openTurns = 0;
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Reading it back
    // ------------------------------------------------------------------

    /** Every call, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The filled rectangles, in order. */
    public List<Call> fills() {
        return calls.stream().filter(call -> call.op() == Op.FILL).toList();
    }

    /** The text, in order. */
    public List<Call> texts() {
        return calls.stream().filter(call -> call.op() == Op.TEXT).toList();
    }

    /** The shadowed lines, in order. The other half of the distinction {@link Op#SHADOWED_TEXT} keeps. */
    public List<Call> shadowedTexts() {
        return calls.stream().filter(call -> call.op() == Op.SHADOWED_TEXT).toList();
    }

    /** The sprites, in order, each carrying the sprite's own id. */
    public List<Call> sprites() {
        return calls.stream().filter(call -> call.op() == Op.SPRITE).toList();
    }

    /** The turns, in order, each carrying its pivot and its angle. */
    public List<Call> turns() {
        return calls.stream().filter(call -> call.op() == Op.TURN).toList();
    }

    /** The styled lines, in order, with the runs each carried. */
    public List<Call> styled() {
        return calls.stream().filter(call -> call.op() == Op.STYLED_TEXT).toList();
    }

    /** The icons, in order. */
    public List<Call> icons() {
        return calls.stream().filter(call -> call.op() == Op.ICON).toList();
    }

    /** The textures, in order, each carrying its resource path. */
    public List<Call> textures() {
        return calls.stream().filter(call -> call.op() == Op.TEXTURE).toList();
    }

    /** The clips, in order. */
    public List<Call> clips() {
        return calls.stream().filter(call -> call.op() == Op.CLIP).toList();
    }

    /**
     * Where the layering boundaries were, as indices into {@link #calls()}.
     *
     * <p>Indices rather than the calls themselves, because the question a test asks is <i>relative</i>
     * order — "was the flush between the icons and the panel" — and an index is what a comparison
     * against another call's position needs. Returning the calls would make every such assertion
     * re-derive that.
     */
    public List<Integer> flushes() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).op() == Op.FLUSH) {
                out.add(i);
            }
        }
        return out;
    }

    /** The index of the first call with this op, or -1. For asserting relative order. */
    public int firstIndex(Op op) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).op() == op) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the last call with this op, or -1. */
    public int lastIndex(Op op) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).op() == op) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether every clip was closed exactly once.
     *
     * <p>The assertion worth having on this class, and the reason a scoped clip is the seam's shape
     * rather than a push/pop pair: a leaked clip does not fail loudly, it leaves every later draw in
     * the frame clipped to a rectangle nobody chose. Reading it back off a recorder is the only way to
     * check it without a client.
     */
    public boolean clipsBalanced() {
        return openClips == 0 && clippedAfterStop == 0;
    }

    /** How many clips were still open when this recorder was last read. */
    public int unclosedClips() {
        return openClips;
    }

    /** How many pops happened with nothing pushed. */
    public int strayPops() {
        return clippedAfterStop;
    }

    /**
     * How many batched regions were opened.
     *
     * <p>The count a canvas test asserts on: "everything on the canvas is drawn in one batch" is
     * {@code batches() == 1}, and the number is also what makes a regression — a batch quietly dropped from
     * the draw path — a failing assertion rather than a frame-rate report from a player.
     */
    public int batches() {
        return batches;
    }

    /** How deep the clip nesting went, so a test can tell nesting from replacing. */
    public int deepestClip() {
        return deepestClip;
    }

    /**
     * Whether every turn was closed exactly once.
     *
     * <p>The same assertion {@link #clipsBalanced} makes, and worth having for a stronger reason: a clip left
     * open hides what follows it, and a turn left open <b>rotates</b> it. A test that draws a turned picture
     * and then asserts the next thing drawn was upright is only meaningful if the scope really closed, and
     * that is this.
     */
    public boolean turnsBalanced() {
        return openTurns == 0 && strayTurnPops == 0;
    }

    /** How many turns were still open when this recorder was last read. */
    public int unclosedTurns() {
        return openTurns;
    }

    /** How many turn-scopes were closed with nothing open. */
    public int strayTurnPops() {
        return strayTurnPops;
    }

    /** How deep the turn nesting went, so a test can tell a turn inside a turn from two in a row. */
    public int deepestTurn() {
        return deepestTurn;
    }

    /** Whether any text was drawn with the given string. */
    public boolean drewText(String text) {
        return calls.stream().anyMatch(call -> call.op() == Op.TEXT && text.equals(call.text()));
    }

    /** Whether a filled rectangle covers this point. */
    public boolean covered(int x, int y) {
        return calls.stream().anyMatch(call -> call.covers(x, y));
    }

    /** The text drawn with the given string, or null. The first, if it was drawn more than once. */
    public Call callFor(String text) {
        return calls.stream().filter(call -> call.op() == Op.TEXT && text.equals(call.text()))
                .findFirst().orElse(null);
    }

    /** Forgets everything recorded, so one recorder can serve several assertions. */
    public void reset() {
        calls.clear();
        openClips = 0;
        deepestClip = 0;
        clippedAfterStop = 0;
        batches = 0;
        openTurns = 0;
        deepestTurn = 0;
        strayTurnPops = 0;
    }

    @Override
    public String toString() {
        return "RecordingRenderer(" + calls.size() + " call(s))";
    }
}

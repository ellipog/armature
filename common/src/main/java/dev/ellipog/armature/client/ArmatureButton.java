package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.render.TextEpoch;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Tween;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * A themed button, for a UI that is not the vanilla one.
 *
 * <h2>Why not {@code Button}</h2>
 *
 * <p>Vanilla's button draws a 200×20 nine-sliced texture with vanilla's widget sprites. In a screen
 * with its own flat palette it looks like a piece of another program pasted into this one — which is
 * exactly what it is. Restyling it means overriding the drawing anyway, and at that point the texture
 * and the sprite machinery are along for the ride.
 *
 * <p>So this extends {@link AbstractWidget}, which supplies the input handling, focus, narration and
 * positioning, and draws everything itself. That is a real saving of work: hit testing, tab order,
 * keyboard activation and the narration system are all subtle and all already correct in the base.
 *
 * <p>One thing worth knowing about the base, since it decides the shape of {@link #mouseClicked}:
 * {@code AbstractWidget.onClick} fires on mouse <b>down</b>, not on release. A button that wants to
 * look pressed while the pointer is down has to override {@code mouseClicked} instead of using
 * {@code onClick}. The method comment says so with the source quoted.
 *
 * <h2>What it can be</h2>
 *
 * <p>Text, or text with a leading item icon. An icon is what makes a chapter list or an entry action
 * readable at a glance, and there is no other way to put one in a control. The icon is drawn at a
 * fixed offset and the text is centred in what is left, so a row of buttons with and without icons
 * still lines its labels up.
 *
 * <h2>Accent, selected and flat</h2>
 *
 * <p>{@link #accent(boolean)} marks the one control a screen is really about — a Done, a Confirm.
 * Used more than once per screen it stops meaning anything, which is why it is a flag rather than a
 * colour: {@link ArmatureControlStyle} decides what "accent" looks like, and there is one place to
 * change it.
 *
 * <p>{@link #selected(boolean)} marks the current one — the chapter being shown, the tab being read.
 * It is a <b>fill</b>, not the absence of one, and that distinction cost a round: the book screen's
 * chapter list used {@link #flat(boolean)} for its selected row, so the current chapter was the only
 * one drawn with no box at all and read as the missing or disabled one. Flat means "this is a label
 * that happens to be clickable"; it does not mean "this one is on".
 *
 * <p>{@link #flat(boolean)} drops the fill for a control that should read as a label — a link, a tab
 * that is not current. It draws <b>nothing at all</b>, which is right for a link inside a paragraph
 * and wrong for a list's heading: a heading had no affordance, so a working control read as broken
 * text. {@link #section(boolean)} is that case — a rule underneath, no fill — and it exists because
 * "no box" and "no indication whatsoever" turned out to be different requirements.
 *
 * <p>{@link #alignLeft(boolean)} puts the label against the left edge. Centred is right for a control
 * on its own and wrong for a column of them, where it leaves the left edge ragged.
 *
 * <h2>Where the colours come from</h2>
 *
 * <p>Every state is resolved by {@link ArmatureControlStyle}, not here. That is deliberate and it is
 * the fix for a real drift: the preview that draws this UI outside the game used to carry its own copy
 * of these rules and drew a selected control differently from the way this class does. One description,
 * called by the button and printed by the tool.
 */
public class ArmatureButton extends AbstractWidget {

    /** Tooltip lines, or null. Drawn by the screen, since a widget cannot draw outside itself. */
    private List<String> tooltip;

    /**
     * How hovered this control looks, from 0 to 1, eased over {@link Tween#DEFAULT_MILLIS}.
     *
     * <h2>Why a control needs state to change colour</h2>
     *
     * <p>Because the alternative is a snap, and a cursor crossing a column of chapters snaps eight
     * times in a few hundred milliseconds — which reads as flicker rather than as response. Eased, the
     * same movement reads as the interface following the pointer.
     *
     * <h2>What this costs, and why it is the right trade</h2>
     *
     * <p>A field and one {@code retarget} per frame per visible control. The retarget is a comparison
     * and an assignment when nothing has changed, which is most frames, so the common path is two
     * branches. The window is a fixed one: a control's tween has no allocation, no list, and nothing
     * that grows.
     *
     * <p>Not {@code volatile} and not synchronised: every read and write is on the client thread,
     * because that is where a widget is drawn.
     */
    private final Tween hoverTween = Tween.settled(0F);

    /** What to do when pressed. Never null — an inert button is {@code active = false}. */
    private final Consumer<ArmatureButton> onPress;

    /** A 16×16 item drawn before the label. The size is the control's, not this. */
    private net.minecraft.world.item.ItemStack icon;

    /**
     * A texture file drawn before the label instead of the item, or null.
     *
     * <p>For a control whose picture is a file rather than an item — a chapter row wearing a pack's
     * emblem. Drawn stretched into the same box the item would take, so a row of controls with and
     * without icons still lines its labels up. An item set beside it wins, because the item is what
     * every control drawn before this field existed wears; the two never arrive together from one
     * icon, which carries one arm or the other.
     */
    private net.minecraft.resources.ResourceLocation texture;

    /**
     * How far the icon sits inside the control's own edge, on every side.
     *
     * <h2>Why this is a field rather than the two numbers it was</h2>
     *
     * <p>The box was {@code height - 6} and the left edge was {@code getX() + 3}: the same 3 written
     * twice, so a control could not say "this sprite is two pixels in" without both numbers moving
     * together and nothing saying they had to. One inset gives one rule — <b>the sprite is inset by this
     * much on all four sides</b> — because the box is the height less twice the inset and the vertical
     * placement centres exactly that box. Three is what every control in both mods drew before this was
     * a field, so nothing already on a screen moves.
     */
    private int iconInset = 3;

    private boolean accent;
    private boolean selected;
    private boolean flat;
    private boolean section;
    private boolean borderless;
    private boolean alignLeft;

    /**
     * How far a left-aligned label sits from its control's left edge.
     *
     * <h2>Why two was too few, and what it looked like</h2>
     *
     * <p>Two pixels was chosen to match what the *centred* branch leaves on the narrower side, so a
     * column of controls would line up whichever way each one was aligned. That reasoning holds for a
     * label and not for a control with a fill behind it: on a filled row a label two pixels from the
     * border reads as touching it, and the report was exactly that -- the chapter rows "missing some
     * space on left between border and text".
     *
     * <p>Six, which is roughly the padding every other filled row in this UI uses, and is applied to
     * every left-aligned control rather than to the sidebar's rows alone: the party panel's rows are
     * filled the same way and would have had the same complaint.
     */
    private static final int LABEL_INSET = 6;

    /**
     * Which theme ink an ordinary label is drawn in, resolved while drawing.
     *
     * <p>An enum rather than a captured colour, and that is the fix rather than a nicety: a colour read
     * in the constructor freezes whatever theme was in force when the control was <i>built</i>, and a
     * screen that rebuilds its controls once and then draws them inside a chapter's scope would show
     * that chapter's fills under the main theme's label ink. The two roles that matter are here; a
     * colour a screen really does mean to pin uses {@link #textColour(int)}.
     */
    public enum Ink {
        /** The brightest text, for a label that stands alone. The default. */
        TITLE,
        /** Ordinary text, for a label beside other content. */
        BODY,
        /** The blocked ink, for a label whose meaning is "this one is not available". */
        BLOCKED,

        /**
         * The warning ink, for a label whose meaning is "this one destroys something".
         *
         * <h2>Why this is not {@link #BLOCKED}</h2>
         *
         * <p>{@code BLOCKED} resolves to the theme's blocked colour — a grey — and a grey label reads
         * as <i>disabled</i>. A destructive control drawn with it produced exactly that report: a
         * Disband button a player asked about, because it looked like a button they were not allowed
         * to press. The palette has no red, so a "danger" ink has to be the warning colour it does
         * carry; that is {@code inProgress}, the amber the node states already use for "caution".
         */
        DANGER
    }

    private Ink ink = Ink.TITLE;

    /** A raw override, or null to resolve {@link #ink} from the theme as it is drawn. */
    private Integer textColour;

    /** Whether the pointer is down on this button. Held so the pressed state can be drawn. */
    private boolean held;

    /**
     * Hover as told by the caller, or null to work it out the usual way.
     *
     * <h2>Why a control can need telling</h2>
     *
     * <p>Because a widget's hover is worked out in {@code render} -- the base class's drawing pass checks
     * the pointer against the control's rectangle and then calls {@code renderWidget}. A control that
     * pass never draws therefore never hears about the pointer: its presses still work, because input
     * walks the children, and its hover never does. The book's header is exactly that case, since the
     * pass is clipped below the title bar -- and its two controls were the only ones in that screen that
     * did not fade, through several rounds of looking in the theme, the variant and the registration.
     *
     * <p>A {@code Boolean} rather than a {@code boolean} because "nobody told me" and "I was told no" are
     * different answers: the first falls back to the widget's own reading, the second overrides it.
     */
    private Boolean toldHover;

    public ArmatureButton(int x, int y, int width, int height, Component label,
                          Consumer<ArmatureButton> onPress) {
        super(x, y, width, height, label);
        this.onPress = onPress;
    }

    public ArmatureButton(int x, int y, int width, int height, Component label, Runnable onPress) {
        this(x, y, width, height, label, button -> onPress.run());
    }

    // ------------------------------------------------------------------
    // Configuration. All of these return this, so a control reads as one expression.
    // ------------------------------------------------------------------

    /** Mark this as the screen's primary action. Use on one control per screen. */
    public ArmatureButton accent(boolean value) {
        this.accent = value;
        return this;
    }

    /**
     * Mark this as the current one — the chapter being shown, the tab being read.
     *
     * <p>A fill of its own, and not the absence of one. See the class comment: using {@link #flat} for
     * this made the selected chapter the only row with no box, which reads as the broken one rather
     * than the active one.
     */
    public ArmatureButton selected(boolean value) {
        this.selected = value;
        return this;
    }

    /** No fill behind the text. For a control that should read as a link or a label. */
    public ArmatureButton flat(boolean value) {
        this.flat = value;
        return this;
    }

    /** Fill but no border. For a control that sits inside another filled area. */
    public ArmatureButton borderless(boolean value) {
        this.borderless = value;
        return this;
    }

    /** A raw colour for the label, pinned rather than resolved from the theme. Rarely what you want. */
    public ArmatureButton textColour(int colour) {
        this.textColour = colour;
        return this;
    }

    /** The label's ink role, resolved against the theme in force while drawing. */
    public ArmatureButton ink(Ink role) {
        this.ink = role;
        return this;
    }

    /**
     * A 16×16 item drawn before the label.
     *
     * <p>16 is the item's own size, not the slot's: the icon is scaled to the control's inner height,
     * less the inset on each side ({@link #iconInset}), so passing an item here does not decide how big
     * it is drawn. See {@link GuiRenderer#icon}.
     */
    public ArmatureButton icon(net.minecraft.world.item.ItemStack stack) {
        this.icon = stack == null || stack.isEmpty() ? null : stack;
        return this;
    }

    public ArmatureButton icon(net.minecraft.world.item.Item item) {
        return icon(new net.minecraft.world.item.ItemStack(item));
    }

    /**
     * A texture file drawn before the label instead of the item.
     *
     * <p>Null clears it. An item set beside it wins when both are set, so a control that learned a
     * texture never changes what it drew for an item. See {@link #texture} for why the two never
     * arrive together.
     */
    public ArmatureButton texture(net.minecraft.resources.ResourceLocation id) {
        this.texture = id;
        return this;
    }

    /**
     * How far the sprite sits inside this control's edge, on every side. Three by default.
     *
     * <p>For a control whose icon is the whole of it — a HUD element drawn at the size it is in the
     * game, say — the inset is what decides how much of the box the sprite fills, and the caller that
     * owns the box is the one that knows. See {@link #iconInset} for why the rule is one number.
     */
    public ArmatureButton iconInset(int value) {
        this.iconInset = Math.max(0, value);
        return this;
    }

    public ArmatureButton tooltip(Component line) {
        return tooltip(List.of(line));
    }

    /**
     * Tooltip lines.
     *
     * <p>Stored rather than passed to {@code setTooltip}, because the base class's tooltip is wired to
     * vanilla's tooltip renderer and would draw in vanilla's style. The screen asks for
     * {@link #tooltip()} when the pointer is over this widget and draws it itself.
     *
     * <h2>Strings rather than {@code FormattedCharSequence}, and nothing was lost</h2>
     *
     * <p>These used to be {@code Component#getVisualOrderText}, which is a {@code FormattedCharSequence}
     * — a sequence of {@code (codepoint, Style)} pairs, which is what the renderer wants and a shape
     * the renderer seam cannot accept without taking {@code Style} with it. So they are plain strings
     * now, and the reason that costs nothing is worth stating rather than assuming: every tooltip in
     * both mods is built from {@code Component.literal}, so none of them carries a style to lose. If
     * one ever does, it will draw unstyled — visibly, not silently, and the fix is to give the seam a
     * styled-text type rather than to put {@code Style} back into the caller's hands. One did (an entry
     * description is markdown), and the fix was made exactly so: {@link GuiRenderer.StyledRun}.
     */
    public ArmatureButton tooltip(List<Component> lines) {
        this.tooltip = lines.isEmpty() ? null : lines.stream()
                .map(Component::getString)
                .toList();
        return this;
    }

    /**
     * A heading: no fill, a rule underneath, and the brightest label.
     *
     * <p>The shape a collapsible list needs, and the reason it was added: the book screen's group
     * headings were drawn {@link #flat(boolean)}, which draws nothing at all, so a row whose whole
     * width is clickable looked exactly like a word. See {@link
     * ArmatureControlStyle.Variant#SECTION}.
     */
    public ArmatureButton section(boolean value) {
        this.section = value;
        return this;
    }

    /**
     * Draws the label from the left edge rather than centred.
     *
     * <h2>Why a list needs this and a lone button does not</h2>
     *
     * <p>Centring is right for a control that sits on its own — a Close, a Confirm — because the label
     * is the whole of the thing and the eye should land in the middle of it. It is wrong for a
     * <b>column</b> of controls, and visibly so: a list of chapter titles centred in a sidebar has a
     * ragged left edge, so nothing lines up and a short title floats away from the row it belongs to.
     * A reader scanning a list tracks the left edge, and there is not one.
     *
     * <p>So this is the caller's decision, not a heuristic. Nothing in here inspects the label's length
     * or the control's width to guess which alignment "looks better" — a rule like that would be wrong
     * in exactly the case somebody had a reason for.
     */
    public ArmatureButton alignLeft(boolean value) {
        this.alignLeft = value;
        return this;
    }

    /** Which kind of control this is, for {@link ArmatureControlStyle}. Accent wins over selected. */
    private ArmatureControlStyle.Variant variant() {
        if (accent) {
            return ArmatureControlStyle.Variant.ACCENT;
        }
        if (selected) {
            return ArmatureControlStyle.Variant.SELECTED;
        }
        if (section) {
            // Before `flat`, which it shares its lack of a box with: a section is the more specific
            // case and has a rule, so it must win. The two are not mutually exclusive as fields --
            // clearing one does not clear the other -- so the order here is the whole of what decides.
            return ArmatureControlStyle.Variant.SECTION;
        }
        return flat ? ArmatureControlStyle.Variant.FLAT : ArmatureControlStyle.Variant.PLAIN;
    }

    /**
     * Tells this control whether the pointer is over it. See {@link #toldHover} for why a caller would.
     *
     * <p>For a screen that draws one of these itself, which is the only situation the pass cannot answer:
     * say where the pointer is on the way past, and the control fades like every other one.
     */
    public ArmatureButton hoverTold(boolean over) {
        toldHover = over;
        return this;
    }

    /** The tooltip lines, or null. For the owning screen to draw. */
    public List<String> tooltip() {
        return tooltip;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The one forced signature in this class, and it is Minecraft's rather than a choice: the base
        // class hands over a GuiGraphics and there is no overload that does not. So this wraps and
        // delegates immediately, and the body — where every actual decision lives — never sees one.
        //
        // That is the whole shape of the seam at a call site: one line here, and everything below is
        // expressed in terms of fill, text and icon. `.utils/check_seam.py` counts these files, so a
        // third one cannot appear without somebody writing down why.
        //
        // And it is drawn inside a **batch**, which is the one thing this wrapper does beyond
        // delegating. A control's own drawing is a panel — the border's whole footprint and then a fill
        // inset by a pixel, each a run of rows — plus a rule, a label and maybe an icon, and every fill
        // in that would be its own GPU submission on its own: `GuiGraphics.fill` ends in
        // `flushIfUnmanaged`, so an unmanaged context pays one `endBatch` per rectangle. Verified from
        // the 1.21.1 sources rather than assumed -- `fill` and `drawString` both flush when unmanaged,
        // and text is a different render type from a fill, which is why the label still costs a
        // boundary inside the batch and the panel no longer costs one per row.
        GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
        renderer.batched(() -> {
            draw(renderer);
            return null;
        });
    }

    /**
     * Draws this control, animating any hover it is part-way through.
     *
     * <p>Takes a renderer, so it is testable without a client and shares nothing with the version being
     * ported. The clock is read here rather than inside, so the animated overload below can be driven
     * from a test with time it chose.
     */
    public void draw(GuiRenderer renderer) {
        draw(renderer, net.minecraft.Util.getMillis());
    }

    /**
     * The same, at a time the caller supplies.
     *
     * <p>This is the one a test drives. An animation asserted by sleeping is flaky on a loaded machine
     * and slow always; asserting one by passing a larger number is neither.
     */
    public void draw(GuiRenderer renderer, long nowMillis) {
        if (!visible) {
            return;
        }
        measuring = renderer;

        // One source for the appearance, so this button and anything that draws it agree. The
        // precedence -- disabled, then selected, then held, then hovered -- is documented there.
        ArmatureControlStyle.Variant variant = variant();
        boolean hovered = toldHover != null ? toldHover : isHoveredOrFocused();

        // Told where it is heading every frame, which is how the tween catches up when the pointer
        // arrives while the last transition is still running -- see Tween.retarget. Passing the state
        // rather than calling retarget only on a change means there is no "did I already know this"
        // bookkeeping here to get out of step with the widget's own idea of hover.
        hoverTween.retarget(hovered ? 1F : 0F, nowMillis);

        // Held is checked before the blend rather than inside it: a press has to be immediate, so it
        // snaps while the hover eases. See ArmatureControlStyle.fillAt for why.
        int fill = ArmatureControlStyle.fillAt(variant, active, held, hoverTween.value(nowMillis));
        int border = ArmatureControlStyle.edge(variant, active, held, hovered);

        // A control used to be a rectangle with four one-pixel edges drawn over it, which cannot be
        // rounded at all -- so every theme's corner radius reached the panels and stopped at the
        // buttons. This goes through `ArmatureTheme.panel` instead, which is the same two-shape
        // approach: the border's whole footprint, then the fill inset by one pixel over it.
        //
        // The borderless case is not the same call with a zero-width border, and that is worth stating
        // because it looks like it should be. A borderless control is a *label* that happens to be
        // clickable, so it has no ring at all: routing it through `panel` with the fill as its own
        // border would draw a ring that is invisible but still costs a second shape per frame, and it
        // would inset the fill by one pixel for no reason -- a one-pixel shift on every flat control,
        // which is the kind of thing that reads as a layout bug rather than as a rounding decision.
        if (ArmatureControlStyle.drawsBox(variant)) {
            if (borderless) {
                ArmatureTheme.fillSurface(renderer, getX(), getY(), width, height, fill,
                        ArmatureTheme.CORNERS_ALL);
            }
            else {
                ArmatureTheme.panel(renderer, getX(), getY(), width, height, fill, border);
            }
        }

        // A section's rule, drawn along the bottom edge. One pixel, the full width of the control.
        //
        // The `+1` on the right is not a rounding decision: `fill` is half-open, so a rule from `x` to
        // `right()` covers the same pixels a fill would, and a rule one short would leave a gap at the
        // corner that reads as the line being broken rather than as the control ending.
        if (variant == ArmatureControlStyle.Variant.SECTION) {
            renderer.fill(getX(), getY() + height - 1, getX() + width, getY() + height,
                    ArmatureControlStyle.edge(variant, active, held, hovered));
        }

        // The style decides the label for the variants it owns -- accent, selected and section --
        // because those are the ones whose background it also chose, and a label must be picked against
        // its own background. Everywhere else the caller's own textColour wins, since a screen may have
        // a reason the style cannot know.
        int colour;
        if (!active) {
            colour = ArmatureTheme.blocked();
        }
        else if (variant == ArmatureControlStyle.Variant.ACCENT
                || variant == ArmatureControlStyle.Variant.SELECTED
                || variant == ArmatureControlStyle.Variant.SECTION) {
            colour = ArmatureControlStyle.text(variant, true);
        }
        else {
            // Resolved now rather than at construction, so a label drawn inside a chapter's scope wears
            // that chapter's ink: see `Ink`.
            colour = textColour != null ? textColour : switch (ink) {
                case TITLE -> ArmatureTheme.title();
                case BODY -> ArmatureTheme.body();
                case BLOCKED -> ArmatureTheme.blocked();
                case DANGER -> ArmatureTheme.inProgress();
            };
        }
        String label = getMessage().getString();

        // The icon is the inner height of the control, so it is the size of the space it is in rather
        // than a fixed 16px in a 20px button with 4px of padding somewhere. The slot the text starts
        // after is derived from that same number, so a row of controls with and without icons still
        // lines its labels up. Both numbers come from the one inset -- see `iconInset` -- so a control
        // can say "two pixels in" and get two pixels on all four sides rather than three on the left.
        int iconBox = Math.max(8, height - iconInset * 2);
        int iconSlot = icon != null || texture != null ? iconBox + 4 : 0;
        if (icon != null) {
            renderer.icon(icon, getX() + iconInset, getY() + (height - iconBox) / 2, iconBox);
        }
        else if (texture != null) {
            renderer.texture(texture, getX() + iconInset, getY() + (height - iconBox) / 2,
                    iconBox, iconBox);
        }

        int textLeft = getX() + iconSlot;
        int textWidth = width - iconSlot;

        // Truncated to the space there actually is, by pixel width rather than by character count. A
        // control cannot know how long its label will be -- a chapter title comes from the data file
        // -- so without this a long label is drawn straight through the control's edge and reads as a
        // rendering fault rather than as a name that is simply too long.
        //
        // `Measure.truncate` rather than a font call, so the truncation rule is testable and lives
        // beside the wrap rule it is a counterpart of. Both answer "what fits in this width", one by
        // breaking a paragraph and one by cutting a line.
        String shown = Measure.truncate(label, Math.max(0, textWidth - 4), measure(renderer));
        // Two pixels in from the left rather than flush against it. A label touching its own edge
        // reads as text that has overflowed rather than as text that has been placed, and the same
        // two pixels is what the centred branch leaves on the narrower side. See `alignLeft` for which
        // of the two a caller wants and why a column needs the one it needs.
        int textX = alignLeft
                ? textLeft + LABEL_INSET
                : textLeft + (textWidth - renderer.textWidth(shown)) / 2;
        renderer.text(shown, textX, getY() + (height - 8) / 2, colour);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // The label is the narration. A screen reader should say what the button does, and what it
        // does is what it says -- a separate narration field would be one more thing to keep in step.
        defaultButtonNarrationText(output);
    }

    /** The renderer the measure in force is measuring with. See {@link #measure}. */
    private GuiRenderer measuring;

    /** The measure in force, built once and re-pointed at whichever renderer is drawing. */
    private Measure measure;

    /**
     * The measure this control truncates with, built once rather than per frame.
     *
     * <h2>Why a field, when the adapter is a lambda</h2>
     *
     * <p>Because the label is truncated every frame and {@code Measure.truncate} walks the string a
     * character at a time asking how wide each prefix is — so the <i>measure</i> is what has to remember,
     * and a measure built at the call site has nothing in it. This was {@code Measure.of(renderer::textWidth,
     * renderer.lineHeight())} inline, which allocated an anonymous measure per button per frame and threw
     * away every width it had already been asked for.
     *
     * <p>The measure outlives the frame and reads whichever renderer is in force, through
     * {@link #measuring} — the same shape a screen's own cached measure uses, and for the same reason: a
     * memo built per frame would have nothing in it, and the identity has to be stable across frames
     * rather than within one.
     *
     * <p>{@code Measure.cached} rather than a bare adapter, so the widths are remembered and emptied when
     * {@code TextEpoch} moves — a font reload or a text-scale change. A control cannot see either, and a
     * width measured against the face the pack was reloaded away from is text laid out to a width it no
     * longer has.
     */
    private Measure measure(GuiRenderer renderer) {
        measuring = renderer;
        if (measure == null) {
            // Both methods read whichever renderer is in force, so the line height is the one the old
            // inline adapter reported rather than a constant that happens to be right today.
            //
            // The epoch folds in the renderer's **identity**, and that is load-bearing rather than tidy:
            // `TextEpoch` describes the font and the text scale, and neither of those changes when a
            // control is redrawn through a different renderer — which happens, because a screen redraws its
            // controls through its own, a modal band redraws them through another, and a test drives them
            // through a recording one. Without the identity, the widths the first renderer gave would be
            // handed to the second, and a control would draw its label at a width its own renderer never
            // agreed to. `MeasureCacheTest.cachedOverFollowsTheRenderer` is the case that caught it.
            measure = Measure.cachedOver(text -> measuring.textWidth(text),
                    () -> measuring.lineHeight(),
                    () -> TextEpoch.now() * 31L + System.identityHashCode(measuring));
        }
        return measure;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Press and release, rather than the base class's press-only.
     *
     * <h2>What the base class actually does</h2>
     *
     * <p>Worth writing down, because I got it wrong once and the mistake was invisible. From
     * {@code AbstractWidget}, verbatim:
     *
     * <pre>
     * mouseClicked(...) {
     *     if (active &amp;&amp; visible &amp;&amp; isValidClickButton(button) &amp;&amp; clicked(mx, my)) {
     *         playDownSound(...);
     *         onClick(mx, my);       // &lt;-- fires on PRESS
     *         return true;
     *     }
     *     return false;
     * }
     * mouseReleased(...) {
     *     if (isValidClickButton(button)) { onRelease(mx, my); return true; }
     *     return false;
     * }
     * </pre>
     *
     * <p>So {@code onClick} is a <i>press</i> callback, and overriding it to report a press is what
     * vanilla does — but it means the button cannot show a held state, because the action has already
     * happened by the time the button looks pressed. For a Submit button in a book screen that is
     * wrong: it should look pressed while the pointer is down and act when it comes up, and letting go
     * somewhere else should cancel.
     *
     * <p>So {@code mouseClicked} is overridden <b>without</b> calling super, and the action is
     * reported from {@link #mouseReleased} only if the release is still inside. The click sound is
     * played here by hand, because not calling super is what skips it.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !clicked(mouseX, mouseY)) {
            return false;
        }
        held = true;
        playDownSound(Minecraft.getInstance().getSoundManager());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasHeld = held;
        held = false;

        if (wasHeld && active && visible && button == 0 && clicked(mouseX, mouseY)) {
            onPress.accept(this);
            return true;
        }
        // Swallowed even when the press is cancelled: the pointer was down on this control, so the
        // release belongs to it whether or not it did anything.
        return wasHeld;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        // Dragging off the button cancels the press, which is the visual feedback that says letting go
        // now will do nothing. The base class routes drags here from mouseDragged.
        held = clicked(mouseX, mouseY);
    }

    /** Whether the pointer is down on this button. For a caller that needs to know. */
    public boolean isHeld() {
        return held;
    }

    /** Position, by setter rather than by {@code setPosition}. */
    public ArmatureButton at(int x, int y) {
        setX(x);
        setY(y);
        return this;
    }
}

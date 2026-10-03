package dev.ellipog.armature.integration.rei;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.integration.Integrations;
import dev.ellipog.armature.integration.QuestContent;
import dev.ellipog.armature.integration.QuestPage;
import dev.ellipog.armature.integration.QuestPageLayout;
import dev.ellipog.armature.integration.QuestRef;
import dev.ellipog.armature.integration.QuestRow;
import dev.ellipog.armature.integration.Viewers;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.DisplayRenderer;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * REI: the same item-to-quest lookup as plain rows, on the same shared snapshot.
 *
 * <h2>Dynamic by generation, not by registration</h2>
 *
 * <p>A {@link DynamicDisplayGenerator} is asked at view time — when a player presses the lookup keys
 * on an item, or opens the category — so the displays it returns come from the live index and
 * nothing has to be re-registered when the tree changes. Registering displays statically would also
 * mean the item lookup showed each quest twice, because REI indexes registered displays by their
 * entries and the generator answers the same question.
 *
 * <h2>Why a category at all, with nothing registered in it</h2>
 *
 * <p>Every display needs a category to render in, so the category exists and announces itself; its
 * contents arrive through {@link QuestDisplays#generate} when the player opens it. An empty category
 * list entry would be a worse answer than a live one.
 *
 * <h2>What is deliberately thin here</h2>
 *
 * <p>The row is a title you can click, a live label and a bar. No arrows, no recipe frame — this is
 * the fallback tier, and its job is that a player without EMI is not left without the feature.
 */
@me.shedaniel.rei.forge.REIPluginClient
public final class ArmatureReiPlugin implements REIClientPlugin {

    private static final int WIDTH = 150;

    /**
     * The one identifier this plugin hands out, built lazily and kept: categories and displays must
     * share the object, and the content's id is only known once a consumer has installed it.
     */
    private CategoryIdentifier<QuestDisplay> identifier;

    private CategoryIdentifier<QuestDisplay> identifier(QuestContent content) {
        if (identifier == null) {
            identifier = CategoryIdentifier.of(content.categoryId());
        }
        return identifier;
    }

    @Override
    public void registerCategories(CategoryRegistry registry) {
        QuestContent content = eligible();
        if (content == null) {
            return;
        }
        registry.add(new QuestCategory(identifier(content), content, new QuestPageLayout(WIDTH)));
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        QuestContent content = eligible();
        if (content == null) {
            return;
        }
        registry.registerDisplayGenerator(identifier(content), new QuestDisplays(content, identifier(content)));
    }

    /** The content, only when this client's one viewer is REI and a consumer has installed quests. */
    private static QuestContent eligible() {
        if (!Viewers.mayInstall(Viewers.Viewer.REI)) {
            return null;
        }
        return Integrations.content().orElse(null);
    }

    // ------------------------------------------------------------------
    // The display
    // ------------------------------------------------------------------

    /**
     * One quest as a REI display: its item references, plus the page it draws.
     *
     * <p>{@code BasicDisplay} carries the inputs and outputs REI's own machinery reads; the page is
     * what {@link QuestCategory} renders, so the two facts travel together without either being
     * derived from the other.
     */
    private static final class QuestDisplay extends BasicDisplay {

        private final QuestPage page;
        private final CategoryIdentifier<QuestDisplay> identifier;

        QuestDisplay(QuestPage page, CategoryIdentifier<QuestDisplay> identifier, EntryStack<?> focus) {
            super(inputs(page, focus), outputs(page));
            this.page = page;
            this.identifier = identifier;
        }

        QuestPage page() {
            return page;
        }

        @Override
        public CategoryIdentifier<?> getCategoryIdentifier() {
            return identifier;
        }

        private static List<EntryIngredient> inputs(QuestPage page, EntryStack<?> focus) {
            List<EntryIngredient> in = new ArrayList<>();
            if (focus != null) {
                // The entry the player looked up leads the inputs, so the view reads as being about
                // what was searched for; the rest is the quest's own requirement list.
                in.add(EntryIngredient.of(focus));
            }
            for (QuestRow row : page.tasks()) {
                if (!row.icon().isEmpty()) {
                    in.add(EntryIngredient.of(EntryStacks.of(row.icon())));
                }
            }
            return in;
        }

        private static List<EntryIngredient> outputs(QuestPage page) {
            List<EntryIngredient> out = new ArrayList<>();
            for (QuestRow row : page.rewards()) {
                if (!row.icon().isEmpty()) {
                    out.add(EntryIngredient.of(EntryStacks.of(row.icon())));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // The category
    // ------------------------------------------------------------------

    private static final class QuestCategory implements DisplayCategory<QuestDisplay> {

        private final CategoryIdentifier<QuestDisplay> identifier;
        private final QuestContent content;
        private final QuestPageLayout layout;

        QuestCategory(CategoryIdentifier<QuestDisplay> identifier, QuestContent content,
                      QuestPageLayout layout) {
            this.identifier = identifier;
            this.content = content;
            this.layout = layout;
        }

        @Override
        public CategoryIdentifier<? extends QuestDisplay> getCategoryIdentifier() {
            return identifier;
        }

        @Override
        public Component getTitle() {
            return content.categoryTitle();
        }

        @Override
        public Renderer getIcon() {
            return EntryStacks.of(content.categoryIcon());
        }

        @Override
        public int getDisplayWidth(QuestDisplay display) {
            return WIDTH;
        }

        /** The tallest page the category can be asked to draw; see the JEI adapter for the reason. */
        @Override
        public int getDisplayHeight() {
            int tallest = QuestPageLayout.HEADER_HEIGHT;
            for (QuestPage page : content.pages()) {
                tallest = Math.max(tallest, layout.height(page));
            }
            return tallest;
        }

        /**
         * No slot area above the page.
         *
         * <p>The default renderer lays the display's entries out as slots, which would draw every
         * task item twice — once there, once in its row — so the page owns its own layout and the
         * default is replaced with nothing.
         */
        @Override
        public DisplayRenderer getDisplayRenderer(QuestDisplay display) {
            return new BlankRenderer();
        }

        @Override
        public List<Widget> setupDisplay(QuestDisplay display, Rectangle bounds) {
            List<Widget> widgets = new ArrayList<>();
            QuestPage page = display.page();

            QuestPageLayout.Box header = shift(layout.header(), bounds);
            widgets.add(Widgets.createButton(
                            new Rectangle(header.x(), header.y(), header.width(), header.height()),
                            Component.literal(page.quest().title()))
                    .onClick(button -> content.openQuest(page.quest().id()))
                    .tooltipLine(Component.translatable("armature.integration.open")));

            if (!page.tasks().isEmpty()) {
                QuestPageLayout.Box heading = shift(layout.tasksHeading(page), bounds);
                widgets.add(Widgets.createLabel(
                                new Point(heading.x() + QuestPageLayout.ICON_X, heading.y() + 1),
                                content.tasksLabel())
                        .color(ArmatureTheme.heading()).leftAligned().noShadow());
            }
            for (int i = 0; i < page.tasks().size(); i++) {
                QuestPageLayout.Box row = shift(layout.taskRow(i), bounds);
                widgets.add(new RowWidget(content, page.quest().id(), true,
                        page.tasks().get(i).sourceIndex(), layout, row));
                addSlot(widgets, page.tasks().get(i), layout, layout.taskRow(i), bounds);
            }
            if (!page.rewards().isEmpty()) {
                QuestPageLayout.Box heading = shift(layout.rewardsHeading(page), bounds);
                widgets.add(Widgets.createLabel(
                                new Point(heading.x() + QuestPageLayout.ICON_X, heading.y() + 1),
                                content.rewardsLabel())
                        .color(ArmatureTheme.heading()).leftAligned().noShadow());
            }
            for (int i = 0; i < page.rewards().size(); i++) {
                QuestPageLayout.Box row = shift(layout.rewardRow(page, i), bounds);
                widgets.add(new RowWidget(content, page.quest().id(), false,
                        page.rewards().get(i).sourceIndex(), layout, row));
                addSlot(widgets, page.rewards().get(i), layout, layout.rewardRow(page, i), bounds);
            }
            return widgets;
        }

        private static void addSlot(List<Widget> widgets, QuestRow row, QuestPageLayout layout,
                                    QuestPageLayout.Box rowBox, Rectangle bounds) {
            if (row.icon().isEmpty()) {
                // A tag row's members are not known here -- the index resolved them; drawing a
                // representative item would be picking one arbitrarily. The label carries the tag.
                return;
            }
            QuestPageLayout.Box icon = shift(layout.icon(rowBox), bounds);
            widgets.add(Widgets.createSlot(new Point(icon.x(), icon.y()))
                    .entry(EntryStacks.of(row.icon()))
                    .markInput());
        }

        private static QuestPageLayout.Box shift(QuestPageLayout.Box box, Rectangle bounds) {
            return new QuestPageLayout.Box(box.x() + bounds.x, box.y() + bounds.y,
                    box.width(), box.height());
        }
    }

    /** Draws nothing: the page's rows are the layout. */
    private static final class BlankRenderer extends DisplayRenderer {

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public void render(GuiGraphics graphics, Rectangle bounds, int mouseX, int mouseY, float delta) {
            // Nothing to draw above the widgets.
        }
    }

    /**
     * One row's label, count and bar — read live, exactly as the other two adapters do it.
     *
     * <p>A plain {@link Widget} rather than a {@code Label}: the text changes while the page is open,
     * and a label built once would show the progress from when the view was built.
     */
    private static final class RowWidget extends Widget {

        private final QuestContent content;
        private final String questId;
        private final boolean task;
        private final int index;
        private final QuestPageLayout layout;
        private final QuestPageLayout.Box row;

        RowWidget(QuestContent content, String questId, boolean task, int index,
                  QuestPageLayout layout, QuestPageLayout.Box row) {
            this.content = content;
            this.questId = questId;
            this.task = task;
            this.index = index;
            this.layout = layout;
            this.row = row;
        }

        /** No children: the row is one drawn line, and REI's event dispatch asks for them. */
        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        /**
         * {@code Renderable}'s entry point — the current one.
         *
         * <p>REI's older {@code render(GuiGraphics, Rectangle, ...)} is deprecated, and its base
         * implementation delegates here (read from the class rather than assumed). Implementing only
         * this one is therefore both current and sufficient; overriding the deprecated variant would
         * shadow that delegation with a duplicate.
         */
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
            QuestRow live = task ? content.liveTask(questId, index) : content.liveReward(questId, index);

            QuestPageLayout.Box text = layout.text(row);
            int colour = live.locked() ? ArmatureTheme.blocked()
                    : live.done() ? ArmatureTheme.complete()
                    : ArmatureTheme.body();
            String count = live.need() > 0 ? Math.min(live.have(), live.need()) + " / " + live.need() : "";
            int countWidth = count.isEmpty() ? 0 : renderer.textWidth(count) + 4;
            renderer.text(fit(renderer, live.label(), text.width() - countWidth),
                    text.x(), text.y(), colour);
            if (!count.isEmpty()) {
                renderer.text(count, text.right() - renderer.textWidth(count), text.y(),
                        ArmatureTheme.faint());
            }
            if (live.need() > 0) {
                QuestPageLayout.Box bar = layout.bar(row);
                renderer.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), ArmatureTheme.recessed());
                int filled = (int) Math.round(bar.width()
                        * Math.min(1.0, live.have() / (double) live.need()));
                if (filled > 0) {
                    renderer.fill(bar.x(), bar.y(), bar.x() + filled, bar.bottom(),
                            live.done() ? ArmatureTheme.complete() : ArmatureTheme.inProgress());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The lookup
    // ------------------------------------------------------------------

    /**
     * "Which quests want this item, and which give it", answered from the live index at view time.
     *
     * <p>The category's own contents come from {@link #generate}: opening a category is a view too,
     * and a category with nothing registered in it would otherwise open empty. Both paths return the
     * same displays, so a quest never appears twice in one view — the generator is the only source.
     */
    private static final class QuestDisplays implements DynamicDisplayGenerator<QuestDisplay> {

        private final QuestContent content;
        private final CategoryIdentifier<QuestDisplay> identifier;

        QuestDisplays(QuestContent content, CategoryIdentifier<QuestDisplay> identifier) {
            this.content = content;
            this.identifier = identifier;
        }

        @Override
        public Optional<List<QuestDisplay>> getUsageFor(EntryStack<?> entry) {
            return found(entry, true);
        }

        @Override
        public Optional<List<QuestDisplay>> getRecipeFor(EntryStack<?> entry) {
            return found(entry, false);
        }

        @Override
        public Optional<List<QuestDisplay>> generate(ViewSearchBuilder builder) {
            Set<CategoryIdentifier<?>> requested = new HashSet<>(builder.getCategories());
            requested.addAll(builder.getFilteringCategories());
            if (!requested.contains(identifier)) {
                return Optional.empty();
            }
            List<QuestDisplay> all = new ArrayList<>();
            for (QuestPage page : content.pages()) {
                all.add(new QuestDisplay(page, identifier, null));
            }
            return all.isEmpty() ? Optional.empty() : Optional.of(all);
        }

        private Optional<List<QuestDisplay>> found(EntryStack<?> entry, boolean usage) {
            ResourceLocation item = itemOf(entry);
            if (item == null) {
                return Optional.empty();
            }
            List<QuestRef> refs = usage
                    ? content.index().questsUsing(item)
                    : content.index().questsAwarding(item);
            List<QuestDisplay> out = new ArrayList<>(refs.size());
            for (QuestRef ref : refs) {
                for (QuestPage page : content.pages()) {
                    if (page.quest().id().equals(ref.id())) {
                        out.add(new QuestDisplay(page, identifier, entry));
                        break;
                    }
                }
            }
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        }

        private static ResourceLocation itemOf(EntryStack<?> entry) {
            return entry.getValue() instanceof ItemStack stack
                    ? BuiltInRegistries.ITEM.getKey(stack.getItem())
                    : null;
        }
    }

    /** The same width-aware trim the other adapters use; ASCII ellipsis for the measured font. */
    private static String fit(GuiRenderer renderer, String text, int width) {
        if (width <= 0 || renderer.textWidth(text) <= width) {
            return width <= 0 ? "" : text;
        }
        String cut = text;
        while (!cut.isEmpty() && renderer.textWidth(cut + "...") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.isEmpty() ? "" : cut + "...";
    }
}

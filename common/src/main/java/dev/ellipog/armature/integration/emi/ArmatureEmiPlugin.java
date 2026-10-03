package dev.ellipog.armature.integration.emi;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.integration.Integrations;
import dev.ellipog.armature.integration.PageArt;
import dev.ellipog.armature.integration.PagePalette;
import dev.ellipog.armature.integration.QuestContent;
import dev.ellipog.armature.integration.QuestPage;
import dev.ellipog.armature.integration.QuestRow;
import dev.ellipog.armature.integration.QuestPageLayout;
import dev.ellipog.armature.integration.Viewers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * EMI: a quest is a page, not a row.
 *
 * <h2>Why EMI is the first-class integration</h2>
 *
 * <p>Its API renders arbitrary recipes, so a quest can be a real page — tasks as inputs, rewards as
 * outputs, the player's live progress drawn on it — and EMI's own ingredient index then answers
 * "which quests use this item" without any index of ours. The other two viewers render rows through
 * a shared fallback; this one is why that tier exists at all.
 *
 * <h2>The two threads, and the rule that keeps them apart</h2>
 *
 * <p>{@link #register} runs on EMI's reload worker, so it reads only the immutable snapshots the
 * content publishes and builds recipes from them. Everything live — progress, state, claimability —
 * is read by widgets while they draw, on the client thread. An adapter that called
 * {@code liveTask} from {@code register} would be reading the game from a worker; one that captured
 * progress into the recipe would show the numbers from when the page was registered.
 *
 * <h2>The reload watch, and the internal call it needs</h2>
 *
 * <p>EMI has no public API to add recipes after a reload, and quest data arrives after the client
 * has started — so a page set registered before the tree arrives would be empty until the player
 * relogs. The one lever is {@code EmiReloadManager.reload()}, which lives in EMI's runtime package
 * rather than its API. It is used here behind three guards (a world with a recipe manager, EMI
 * finished loading, one request per revision) and pinned to the EMI version in
 * {@code gradle.properties}; a future EMI that changes it breaks this one file, loudly, at compile
 * time. That is the trade for not shipping a quest page that only appears after a relog.
 *
 * <h2>Absence, and the class-loading rule</h2>
 *
 * <p>Nothing here is touched unless EMI is installed: the entrypoint is EMIs own discovery, and
 * {@link Integrations} reaches this class through {@link EmiViewer}, a holder that names no EMI
 * type. The failure this avoids is not a compile error — it is a {@code NoClassDefFoundError} at
 * construction on a client without EMI.
 */
public final class ArmatureEmiPlugin implements dev.emi.emi.api.EmiPlugin, Integrations.ViewerAdapter {

    /** EMI's default recipe width; every page is this wide so the layout has one number. */
    private static final int WIDTH = 134;

    /**
     * The revision the last successful registration built its pages from, and the one a reload has
     * been requested for. Static, because EMI constructs its own plugin instance and the holder
     * constructs another — the two must agree about what is registered.
     */
    private static volatile long registeredRevision = -1L;
    private static volatile long requestedRevision = -1L;

    @Override
    public void register(dev.emi.emi.api.EmiRegistry registry) {
        if (!Viewers.mayInstall(Viewers.Viewer.EMI)) {
            return;
        }
        QuestContent content = Integrations.content().orElse(null);
        if (content == null) {
            // No mod on this client has quests: no category, no empty page, nothing to click.
            return;
        }

        dev.emi.emi.api.recipe.EmiRecipeCategory category = new dev.emi.emi.api.recipe.EmiRecipeCategory(
                content.categoryId(), dev.emi.emi.api.stack.EmiStack.of(content.categoryIcon()));
        registry.addCategory(category);
        for (QuestPage page : content.pages()) {
            registry.addRecipe(new QuestEmiRecipe(category, page));
        }
        registeredRevision = content.revision();
    }

    @Override
    public void tick() {
        QuestContent content = Integrations.content().orElse(null);
        if (content == null || !Viewers.mayInstall(Viewers.Viewer.EMI)) {
            return;
        }
        // The guards `EmiReloadManager.reload` needs: without a world and a recipe manager it aborts
        // and leaves its status mid-reload, which would take the viewer down with it. The recipe
        // manager lives on the client level in 1.21.1 -- `Minecraft` has no accessor of its own, read
        // from the class rather than guessed.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level.getRecipeManager() == null) {
            return;
        }
        if (!dev.emi.emi.runtime.EmiReloadManager.isLoaded()) {
            return;
        }
        long revision = content.revision();
        if (revision == registeredRevision || revision == requestedRevision) {
            return;
        }
        // One request per revision, recorded before the call: a reload that fails must not be
        // retried every tick forever, and the next tree change is the honest retry point.
        requestedRevision = revision;
        dev.emi.emi.runtime.EmiReloadManager.reload();
    }

    // ------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------

    /**
     * One quest as an EMI recipe.
     *
     * <p>Inputs and outputs are the real item references — a tag task is a tag ingredient, a text
     * task is nothing — because EMI indexes these to answer item lookups. Drawing the type icon of a
     * checkmark as an input would make a chest findable from a quest that never mentions one.
     */
    private static final class QuestEmiRecipe implements dev.emi.emi.api.recipe.EmiRecipe {

        private final dev.emi.emi.api.recipe.EmiRecipeCategory category;
        private final QuestPage page;
        private final QuestPageLayout layout = new QuestPageLayout(WIDTH);
        private final List<dev.emi.emi.api.stack.EmiIngredient> inputs;
        private final List<dev.emi.emi.api.stack.EmiStack> outputs;

        QuestEmiRecipe(dev.emi.emi.api.recipe.EmiRecipeCategory category, QuestPage page) {
            this.category = category;
            this.page = page;

            List<dev.emi.emi.api.stack.EmiIngredient> in = new ArrayList<>();
            for (QuestRow row : page.tasks()) {
                if (row.hasTag()) {
                    row.tag().ifPresent(tag -> in.add(dev.emi.emi.api.stack.EmiIngredient.of(
                            TagKey.create(Registries.ITEM, tag))));
                }
                else if (!row.icon().isEmpty()) {
                    in.add(dev.emi.emi.api.stack.EmiStack.of(row.icon()));
                }
            }
            List<dev.emi.emi.api.stack.EmiStack> out = new ArrayList<>();
            for (QuestRow row : page.rewards()) {
                if (!row.icon().isEmpty()) {
                    out.add(dev.emi.emi.api.stack.EmiStack.of(row.icon()));
                }
            }
            this.inputs = List.copyOf(in);
            this.outputs = List.copyOf(out);
        }

        @Override
        public dev.emi.emi.api.recipe.EmiRecipeCategory getCategory() {
            return category;
        }

        @Override
        public ResourceLocation getId() {
            // Synthetic, with the leading slash EMI reserves for ids that are not datapack recipes:
            // the page is generated from the client cache and has no file behind it.
            return ResourceLocation.fromNamespaceAndPath(category.getId().getNamespace(),
                    "/quest/" + page.quest().id());
        }

        @Override
        public List<dev.emi.emi.api.stack.EmiIngredient> getInputs() {
            return inputs;
        }

        @Override
        public List<dev.emi.emi.api.stack.EmiStack> getOutputs() {
            return outputs;
        }

        @Override
        public int getDisplayWidth() {
            return WIDTH;
        }

        @Override
        public int getDisplayHeight() {
            return layout.height(page);
        }

        /** Not a recipe tree: a quest has no bill of materials, and the tree button would be a lie. */
        @Override
        public boolean supportsRecipeTree() {
            return false;
        }

        /** And nothing is craftable: a reward is granted, so claiming craftability is a lie too. */
        @Override
        public boolean hideCraftable() {
            return true;
        }

        @Override
        public void addWidgets(dev.emi.emi.api.widget.WidgetHolder widgets) {
            QuestContent content = Integrations.content().orElse(null);
            if (content == null) {
                return;
            }
            widgets.add(new HeaderWidget(content, page, layout));
            if (!page.tasks().isEmpty()) {
                QuestPageLayout.Box heading = layout.tasksHeading(page);
                widgets.addText(content.tasksLabel(), heading.x() + QuestPageLayout.SLOT_X,
                        heading.y() + 1, PagePalette.MUTED, false);
            }
            for (int i = 0; i < page.tasks().size(); i++) {
                QuestPageLayout.Box box = layout.taskRow(i);
                widgets.add(new RowWidget(content, page.quest().id(), true,
                        page.tasks().get(i).sourceIndex(), layout, box));
                addSlot(widgets, page.tasks().get(i), layout, box);
            }
            if (!page.rewards().isEmpty()) {
                QuestPageLayout.Box heading = layout.rewardsHeading(page);
                widgets.addText(content.rewardsLabel(), heading.x() + QuestPageLayout.SLOT_X,
                        heading.y() + 1, PagePalette.MUTED, false);
            }
            for (int i = 0; i < page.rewards().size(); i++) {
                QuestPageLayout.Box box = layout.rewardRow(page, i);
                widgets.add(new RowWidget(content, page.quest().id(), false,
                        page.rewards().get(i).sourceIndex(), layout, box));
                addSlot(widgets, page.rewards().get(i), layout, box);
            }
        }

        private static void addSlot(dev.emi.emi.api.widget.WidgetHolder widgets, QuestRow row,
                                    QuestPageLayout layout, QuestPageLayout.Box box) {
            QuestPageLayout.Box icon = layout.icon(box);
            // EMI's own slot texture behind the widget, which is how EMI's crafting cards draw a
            // container slot; without it an item icon sits bare on the card.
            widgets.addTexture(dev.emi.emi.api.render.EmiTexture.SLOT, icon.x(), icon.y());
            if (row.hasTag()) {
                row.tag().ifPresent(tag -> widgets.addSlot(dev.emi.emi.api.stack.EmiIngredient.of(
                        TagKey.create(Registries.ITEM, tag)), icon.x(), icon.y()));
            }
            else if (!row.icon().isEmpty()) {
                // EMI's own slot: clicking it shows that item's recipes, which is exactly "a task's
                // or reward's item opens EMI's recipe screen" — nothing custom, and nothing to keep
                // in step with EMI's behaviour.
                widgets.addSlot(dev.emi.emi.api.stack.EmiStack.of(row.icon()), icon.x(), icon.y());
            }
        }
    }

    /** The clickable title strip: the one region that opens the book. */
    private static final class HeaderWidget extends dev.emi.emi.api.widget.Widget {

        private final QuestContent content;
        private final QuestPage page;
        private final QuestPageLayout layout;

        HeaderWidget(QuestContent content, QuestPage page, QuestPageLayout layout) {
            this.content = content;
            this.page = page;
            this.layout = layout;
        }

        @Override
        public dev.emi.emi.api.widget.Bounds getBounds() {
            QuestPageLayout.Box box = layout.header();
            return new dev.emi.emi.api.widget.Bounds(box.x(), box.y(), box.width(), box.height());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
            QuestPageLayout.Box box = layout.header();
            if (box.contains(mouseX, mouseY)) {
                // The whole strip is the click target, and the wash is what says so.
                renderer.fill(box.x(), box.y(), box.right(), box.bottom(), PagePalette.HOVER);
            }
            QuestPageLayout.Box iconBox = layout.headerIcon();
            ItemStack icon = page.quest().icon();
            if (!icon.isEmpty()) {
                renderer.icon(icon, iconBox.x(), iconBox.y(), iconBox.width());
            }
            else if (!page.quest().iconId().isEmpty()) {
                // A pack can name an item this build does not have; the header keeps the id rather
                // than silently losing its picture. The book draws the same fallback.
                renderer.text(PageArt.fit(renderer, page.quest().iconId(), iconBox.width()),
                        iconBox.x(), iconBox.y(), PagePalette.MUTED);
            }
            QuestPageLayout.Box title = layout.headerTitle();
            renderer.text(PageArt.fit(renderer, page.quest().title(), title.width()),
                    title.x(), title.y(), PagePalette.TEXT);
            QuestPageLayout.Box badge = layout.headerBadge();
            PageArt.pill(renderer,
                    PageArt.fit(renderer, content.stateText(page.quest().id()), badge.width()),
                    badge.x(), badge.y(), content.stateColour(page.quest().id()));
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            return List.of(ClientTooltipComponent.create(
                    Component.translatable("armature.integration.open").getVisualOrderText()));
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0) {
                return false;
            }
            content.openQuest(page.quest().id());
            return true;
        }
    }

    /**
     * One row's label, count and bar — live, from the content, every frame.
     *
     * <p>This is what makes progress move while the page is open: the recipe is built once per EMI
     * reload, but the numbers are read here on each draw. Row and slot are separate widgets because
     * EMI's slot owns its own clicks, and a row widget that swallowed them would break the item
     * lookup it exists for.
     */
    private static final class RowWidget extends dev.emi.emi.api.widget.Widget {

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

        @Override
        public dev.emi.emi.api.widget.Bounds getBounds() {
            return new dev.emi.emi.api.widget.Bounds(row.x(), row.y(), row.width(), row.height());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
            QuestRow live = task ? content.liveTask(questId, index) : content.liveReward(questId, index);

            QuestPageLayout.Box text = layout.text(row);
            if (task) {
                drawTaskRow(renderer, live, text);
            }
            else {
                drawRewardRow(renderer, live, text);
            }
        }

        private void drawTaskRow(GuiRenderer renderer, QuestRow live, QuestPageLayout.Box text) {
            int colour = live.locked() ? PagePalette.LOCKED
                    : live.done() ? PagePalette.COMPLETE
                    : PagePalette.TEXT;
            String count = live.need() > 0
                    ? Math.min(live.have(), live.need()) + " / " + live.need()
                    : "";
            int countWidth = count.isEmpty() ? 0 : renderer.textWidth(count) + 4;
            renderer.text(PageArt.fit(renderer, live.label(), text.width() - countWidth),
                    text.x(), text.y(), colour);
            if (!count.isEmpty()) {
                renderer.text(count, text.right() - renderer.textWidth(count), text.y(),
                        PagePalette.MUTED);
            }
            if (live.need() > 0) {
                QuestPageLayout.Box bar = layout.bar(row);
                renderer.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), PagePalette.BAR_TRACK);
                int filled = (int) Math.round(bar.width()
                        * Math.min(1.0, live.have() / (double) live.need()));
                if (filled > 0) {
                    renderer.fill(bar.x(), bar.y(), bar.x() + filled, bar.bottom(),
                            live.done() ? PagePalette.COMPLETE : PagePalette.PROGRESS);
                }
            }
        }

        /**
         * A reward's row: its label and a status pill, never a progress bar.
         *
         * <p>A reward drawn with progress reads as a task the player still owes — the same icon, the
         * same count on both sides — so the row says what it is: Ready, Locked or Claimed, and
         * nothing while the quest is unfinished.
         */
        private void drawRewardRow(GuiRenderer renderer, QuestRow live, QuestPageLayout.Box text) {
            int colour = live.locked() ? PagePalette.LOCKED
                    : live.done() ? PagePalette.MUTED
                    : PagePalette.TEXT;
            QuestContent.RewardStatus status = PageArt.rewardStatus(live);
            if (status == null) {
                renderer.text(PageArt.fit(renderer, live.label(), text.width()),
                        text.x(), text.y(), colour);
                return;
            }
            String word = content.rewardStatusLabel(status).getString();
            int width = PageArt.pillWidth(renderer, word);
            renderer.text(PageArt.fit(renderer, live.label(), text.width() - width - 4),
                    text.x(), text.y(), colour);
            PageArt.pill(renderer, word, text.right() - width, row.y() + 4,
                    PageArt.rewardStatusColour(status));
        }
    }
}

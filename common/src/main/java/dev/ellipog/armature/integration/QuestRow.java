package dev.ellipog.armature.integration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * One line of a quest page: a task to hand in or a reward to claim.
 *
 * <p>The record is both the static shape and the live reading. A snapshot built when the tree arrives
 * fills in the label, the icon and {@code need}, and leaves {@code have} at zero; the same type comes
 * back from {@link QuestContent#liveTask} with the numbers as they are now. That is on purpose: a
 * viewer lays a page out once from the snapshot and redraws its numbers from the live call, so a
 * progress tick never has to rebuild anything.
 *
 * <p>{@code label} is already the player's language -- the content side owns every word, because the
 * words belong to the quest file's author and its translators, not to a viewer adapter.
 *
 * <p>{@code tagId} is {@code namespace:path} when the row names an item tag, empty otherwise, and
 * {@code icon} is empty in that case. It stays a string because Armature knows nothing about item
 * registries; an adapter turns it into whatever its viewer calls a tag, once per page build rather
 * than once per frame.
 */
public record QuestRow(ItemStack icon, String label, int have, int need, boolean done, boolean locked,
                       String tagId) {

    public QuestRow {
        if (icon == null) {
            icon = ItemStack.EMPTY;
        }
        if (label == null) {
            label = "";
        }
        if (tagId == null) {
            tagId = "";
        }
    }

    /** True when this row names an item tag rather than a concrete stack. */
    public boolean hasTag() {
        return !tagId.isEmpty();
    }

    /** The tag id, parsed once per page build; empty when the row has none. */
    public Optional<ResourceLocation> tag() {
        return hasTag() ? Optional.of(ResourceLocation.parse(tagId)) : Optional.empty();
    }

    /** True when there is anything at all to show: an icon, a tag, or a label. */
    public boolean isBlank() {
        return icon.isEmpty() && !hasTag() && label.isEmpty();
    }

    /** A copy with the live numbers, for {@link QuestContent}'s live calls to build cheaply. */
    public QuestRow withProgress(int have, boolean done, boolean locked) {
        return new QuestRow(icon, label, have, need, done, locked, tagId);
    }
}

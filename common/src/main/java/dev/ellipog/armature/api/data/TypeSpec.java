package dev.ellipog.armature.api.data;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * A registered, extensible kind of something — the shape a mod's addon API takes.
 *
 * <p>Two of Tasked's own concepts are this: a quest task type and a quest reward type. So is
 * anything another mod adds. The point is that the set is <b>open at runtime</b>, which an enum
 * cannot be — and these ids end up in quest files and in player progress, so the identity of a type
 * is not something that can be quietly changed later.
 *
 * <p>{@link #fields()} exists so that a validator can tell an author which fields a type accepts,
 * and so reject the ones it does not. That matters more than it sounds: a codec silently ignores
 * fields it does not know, so {@code "counnt": 8} would otherwise produce a task that wants one
 * item and no explanation of why.
 */
public interface TypeSpec<T> {

    /** The id that appears in a file's {@code "type"} field. Namespaced by the mod that owns it. */
    ResourceLocation id();

    /**
     * Reads and writes this type.
     *
     * <p>A {@link MapCodec} rather than a {@code Codec} so that the fields sit flat in the
     * enclosing object — a task is {@code {"type": "...", "item": "..."}}, not nested.
     */
    MapCodec<T> codec();

    /**
     * The field names this type adds, <b>not including</b> {@code "type"} or anything shared by
     * every type of this kind. The validator unions these across all types to decide what is
     * allowed.
     */
    Set<String> fields();
}

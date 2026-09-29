package dev.ellipog.armature.api.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A codec that reads a {@code "type"} field and hands the rest of the object to that type's own codec.
 *
 * <p>Which is how JSON like this becomes an object:
 *
 * <pre>{@code
 * { "type": "tasked:item", "item": "minecraft:oak_log", "count": 8 }
 * { "type": "tasked:checkmark", "title": "I was here" }
 * }</pre>
 *
 * <h2>The error message is the feature</h2>
 *
 * <p>A type that cannot be found gets an error listing every type that <i>can</i>. That is worth the
 * extra code: an author who writes {@code "tasked:items"} instead of {@code "tasked:item"} has made a
 * one-character mistake, and the fix is only obvious if the valid names are right there. The plain
 * alternative — {@code Unknown type tasked:items} — leaves them hunting through a wiki.
 *
 * <p>The specs come from a {@link Supplier} rather than being passed in, because the registry is still
 * being filled at the moment this codec is constructed. Reading at decode time means registration
 * order never matters and an addon registering late still works.
 *
 * <h2>Why the unknown-type branch returns a {@link MapCodec}</h2>
 *
 * <p>Because that is what this Minecraft version's {@code Codec.dispatch} requires, and getting it
 * wrong is a compile error that reads like a generics puzzle:
 *
 * <pre>{@code
 * no instance(s) of type variable(s) T exist so that Codec<T> conforms to MapCodec<? extends E>
 * }</pre>
 *
 * <p>{@code Codec.dispatch} takes a {@code Function<A, MapCodec<? extends E>>} in DataFixerUpper 6,
 * which is what ships with Minecraft 1.21.1. DataFixerUpper 7 changed it to accept a {@code Codec},
 * and most examples online are written against that. This is not a mistake to read past: it means
 * anything reaching past the documented codec API in this Minecraft version is a gamble, because the
 * bundled library is older than the current release and its API is not what the docs describe.
 *
 * <p>The failing codec is spelled out rather than thrown because a codec that cannot decode has to
 * say so through a {@code DataResult}. Throwing would surface as a crash while loading a quest file
 * instead of a message naming the line.
 */
public final class TypeDispatch {

    private TypeDispatch() {
    }

    /**
     * @param kind      what is being dispatched, for error messages — "quest task", say
     * @param typeField the field holding the type id, normally {@code "type"}
     * @param idOf      reads the type id out of a value, for writing it back
     * @param specs     every registered type, read at decode time
     */
    public static <T> Codec<T> codec(String kind,
                                     String typeField,
                                     Function<? super T, ResourceLocation> idOf,
                                     Supplier<Collection<TypeSpec<T>>> specs) {
        return ResourceLocation.CODEC.dispatch(typeField, idOf, id -> {
            TypeSpec<T> spec = find(specs.get(), id);
            // Assigned to an explicitly typed local rather than returned per branch. Java cannot infer
            // E across a lambda with two branches whose return types come from different generic
            // methods, and reports it as "no instance(s) of type variable(s) T exist so that Codec<T>
            // conforms to MapCodec<? extends E>" — which reads like a mistake in the codecs and is
            // really just inference giving up. One typed local settles it.
            MapCodec<T> chosen = spec == null
                    ? TypeDispatch.<T>alwaysFails("unknown " + kind + " type \"" + id + "\"\n"
                            + "    known types: " + names(specs.get()))
                    // A MapCodec reads named fields straight out of the enclosing object, which is what
                    // keeps a type's fields flat: a task says "count", not {"task": {"count": 1}}.
                    : spec.codec();
            return chosen;
        });
    }

    private static <T> TypeSpec<T> find(Collection<TypeSpec<T>> available, ResourceLocation id) {
        for (TypeSpec<T> spec : available) {
            if (spec.id().equals(id)) {
                return spec;
            }
        }
        return null;
    }

    private static <T> String names(Collection<TypeSpec<T>> available) {
        if (available.isEmpty()) {
            return "(none are registered — the mod that provides them may have failed to load)";
        }
        return available.stream()
                .map(spec -> spec.id().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /**
     * A map codec that reports a fixed error whatever it is given, in both directions.
     *
     * <p>Built by transforming an existing codec rather than by implementing {@link MapCodec} by hand.
     * That was the first attempt and it does not work: {@code MapEncoder} and {@code MapDecoder} also
     * declare {@code compressor(DynamicOps)}, so an anonymous implementation has to satisfy more of the
     * library than is reasonable just to produce an error message — and reproducing a library
     * interface from memory is a good way to be wrong twice.
     *
     * <p>{@code MapCodec.unit} takes any value and reads nothing, then {@code flatXmap} fails in both
     * directions. The default value is null and never used, because nothing can decode successfully.
     */
    private static <T> MapCodec<T> alwaysFails(String message) {
        return MapCodec.<T>unit((T) null).<T>flatXmap(
                ignored -> DataResult.error(() -> message),
                ignored -> DataResult.error(() -> message));
    }
}

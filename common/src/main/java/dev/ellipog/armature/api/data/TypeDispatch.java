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
 * { "type": "example:item", "item": "minecraft:oak_log", "count": 8 }
 * { "type": "example:checkmark", "title": "I was here" }
 * }</pre>
 *
 * <h2>The error message is the feature</h2>
 *
 * <p>A type that cannot be found gets an error listing every type that <i>can</i>. That is worth the
 * extra code: an author who writes {@code "example:items"} instead of {@code "example:item"} has made
 * a one-character mistake, and the fix is only obvious if the valid names are right there. The plain
 * alternative — {@code Unknown type example:items} — leaves them hunting through a wiki.
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
 * say so through a {@code DataResult}. Throwing would surface as a crash while loading a data file
 * instead of a message naming the line.
 */
public final class TypeDispatch {

    private TypeDispatch() {
    }

    /**
     * @param kind      what is being dispatched, for error messages — "entry type", say
     * @param typeField the field holding the type id, normally {@code "type"}
     * @param idOf      reads the type id out of a value, for writing it back
     * @param specs     every registered type, read at decode time
     */
    public static <T> Codec<T> codec(String kind,
                                     String typeField,
                                     Function<? super T, ResourceLocation> idOf,
                                     Supplier<Collection<TypeSpec<T>>> specs) {
        return codec(kind, typeField, idOf, specs, null);
    }

    /**
     * The same dispatch, with a <b>placeholder</b> for a type this build has never heard of.
     *
     * <h2>What the strict form costs, and why this exists</h2>
     *
     * <p>The form above fails the decode for an unregistered type, and the failure is not local: a
     * codec error anywhere in a document refuses the whole document. So one entry naming a type the
     * reader does not have — a type from an addon that is not installed, from a newer build of the
     * consumer, or registered on the server but not on the client — costs the author <b>every entry in
     * the file</b>, not the one node. A document that adds a single third-party entry becomes
     * unloadable the moment that provider is removed, which is the exact opposite of the additive
     * compatibility the rest of a versioned format is built for.
     *
     * <p>With a placeholder the unknown node decodes to {@code unknown.apply(id)}: the type id survives,
     * the document loads, and the node is visible and reported as unknown rather than silently absent.
     * It is the same trade a lenient reader already makes for an unresolvable <i>reference</i> — the id
     * is kept, the document loads, and the row says what is missing — and for the same reason: a
     * provider being absent is usually temporary, and losing the author's document over it is not a
     * recoverable mistake.
     *
     * <p><b>The raw payload is not lost.</b> It cannot be carried by the codec — a {@link MapCodec} sees
     * a {@code MapLike}, not the underlying object — but it does not need to be: a caller that edits a
     * document as a raw tree and writes that tree back preserves every field it never decoded, so what
     * this build does not understand is still on disk afterwards. What the placeholder adds is that the
     * document is now <i>readable</i> as well.
     *
     * @param unknown what an unregistered id decodes to. Must not be null; the caller that wants the
     *                strict behaviour passes {@code null} — or calls the four-argument form, which is
     *                the same thing spelled readably.
     */
    public static <T> Codec<T> codec(String kind,
                                     String typeField,
                                     Function<? super T, ResourceLocation> idOf,
                                     Supplier<Collection<TypeSpec<T>>> specs,
                                     Function<ResourceLocation, T> unknown) {
        return ResourceLocation.CODEC.dispatch(typeField, idOf, id -> {
            TypeSpec<T> spec = find(specs.get(), id);
            // Assigned to an explicitly typed local rather than returned per branch. Java cannot infer
            // E across a lambda with two branches whose return types come from different generic
            // methods, and reports it as "no instance(s) of type variable(s) T exist so that Codec<T>
            // conforms to MapCodec<? extends E>" — which reads like a mistake in the codecs and is
            // really just inference giving up. One typed local settles it.
            MapCodec<T> chosen;
            if (spec != null) {
                // A MapCodec reads named fields straight out of the enclosing object, which is what
                // keeps a type's fields flat: a task says "count", not {"task": {"count": 1}}.
                chosen = spec.codec();
            }
            else if (unknown != null) {
                // Reads nothing and yields the placeholder: the dispatch itself has already read and
                // consumed the "type" field, so a map codec that reads no further keys decodes any
                // remaining payload successfully and writes the type back on encode. That is what makes
                // the failure local to this node.
                chosen = MapCodec.unit(unknown.apply(id));
            }
            else {
                chosen = TypeDispatch.<T>alwaysFails("unknown " + kind + " type \"" + id + "\"\n"
                        + "    known types: " + names(specs.get()));
            }
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

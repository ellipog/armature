package dev.ellipog.armature.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;

/**
 * What a canvas draws behind its content: a flat surface, a procedural pattern over it, or an image.
 *
 * <h2>A field on the theme, not a feature of a screen</h2>
 *
 * <p>The canvas's colour has always been a token, and this is the same value one level up: a theme
 * describes its surface, and anything a chapter or a pack points at that theme gets the pattern with
 * the palette. Nothing here knows what is drawn on the canvas or who draws it, which is what lets
 * one library field serve one mod's graph screen today and another's map screen later.
 *
 * <h2>Space: graph or screen</h2>
 *
 * <p>{@link Space#GRAPH} anchors the pattern to the content: it pans and zooms with the nodes, like
 * graph paper under a drawing. {@link Space#SCREEN} fixes it to the surface — a wallpaper the content
 * glides over. {@code spacing} means content units in the first and screen pixels in the second; the
 * art is the same either way. An image is the one pattern that ignores this: a texture tiled or
 * covered onto a rectangle belongs to the screen, and a wallpaper that panned with the graph would be
 * a window onto a moving picture rather than a surface.
 *
 * <h2>Patterns from arithmetic, images from a file</h2>
 *
 * <p>The procedural kinds are a closed set on purpose: each is drawn from arithmetic, with no texture
 * asset to ship or resolve. {@link Kind#IMAGE} is the one kind with an asset behind it, and its
 * {@code texture} is stored as a canonical id — {@code ns:textures/path.png}, lowercased, with the
 * namespace and the two parts a caller may have written in the wrong order normalised away (see
 * {@link #canonicalTexture}). That is the id the renderer resolves against the resource manager, and
 * writing it once here means a theme file, a control and the art all agree on the same file.
 *
 * <p>{@link Fit#TILE} repeats the texture across the visible rectangle at the {@code tile} size,
 * preserving the texture's aspect; {@link Fit#COVER} draws it once, scaled so the rectangle is fully
 * covered and centred, cropping the overflow. Both are screen-fixed; see the space note above.
 *
 * <h2>Tuning: how heavy the marks are</h2>
 *
 * <p>{@code size} is the edge of a dot or a speck and the thickness of a line or a hatch step;
 * {@code density} is how many cells of the lattice carry one speck; {@code backslash} mirror the
 * hatch diagonal. Each is clamped rather than refused, because a theme file is written by hand and a
 * value one step past legible should draw, not vanish.
 *
 * @param kind    which pattern, or {@link Kind#NONE} for the bare surface
 * @param space   what the pattern is anchored to; ignored by images. See the class note
 * @param spacing the distance between repeats, in the space's own units. Clamped to a range where
 *                the pattern stays legible rather than becoming moiré: a few pixels apart at the
 *                least, and close enough that a canvas shows a field and not one lonely dot
 * @param tuning  how heavy the procedural marks draw. Clamped to its own ranges; see the type
 * @param image   the texture an {@link Kind#IMAGE} background draws, and how it fills the rectangle
 */
public record CanvasBackground(Kind kind, Space space, int spacing, Tuning tuning, Image image) {

    /** The lowest spacing a theme may ask for: below this a pattern is a shimmer, not a texture. */
    public static final int MIN_SPACING = 6;

    /** The highest: past this a full-screen canvas would carry a handful of marks. */
    public static final int MAX_SPACING = 256;

    /** The default spacing, in the background's own units. */
    public static final int DEFAULT_SPACING = 24;

    /** No pattern. The canvas is its own colour, which is every theme until one says otherwise. */
    public static final CanvasBackground NONE =
            new CanvasBackground(Kind.NONE, Space.GRAPH, DEFAULT_SPACING);

    /** The patterns a theme may name, and the file id each is written as. */
    public enum Kind {

        /** The bare surface. */
        NONE("none"),

        /** A lattice of small dots — the graph-paper reading, at its most restrained. */
        DOTS("dot_grid"),

        /** Orthogonal lines every {@code spacing} units: engineering paper. */
        GRID_LINES("grid_lines"),

        /** Deterministic speckle, for a paper or parchment feel. Drawn from a hash, not a texture. */
        SPECKLE("speckle"),

        /** Forty-five-degree diagonal lines; the coarsest and most characterful. */
        HATCH("hatch"),

        /** A texture file, tiled or covered. The one kind whose look comes from an asset. */
        IMAGE("image");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** The name a theme file writes. */
        public String id() {
            return id;
        }

        /** The kind one file id names, or null when nothing does. */
        public static Kind byId(String id) {
            if (id == null) {
                return null;
            }
            String trimmed = id.trim();
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(trimmed)) {
                    return kind;
                }
            }
            return null;
        }

        /** Every id, for a message naming what a file could have said. */
        public static String ids() {
            StringBuilder out = new StringBuilder();
            for (Kind kind : values()) {
                if (!out.isEmpty()) {
                    out.append(", ");
                }
                out.append(kind.id);
            }
            return out.toString();
        }
    }

    /** What the pattern is anchored to. See the class note. */
    public enum Space {

        /** Content coordinates: the pattern pans and zooms with what is drawn on it. */
        GRAPH("graph"),

        /** Screen pixels: the pattern stays put while the content moves. */
        SCREEN("screen");

        private final String id;

        Space(String id) {
            this.id = id;
        }

        /** The name a theme file writes. */
        public String id() {
            return id;
        }

        /** The space one file id names, or null when nothing does. */
        public static Space byId(String id) {
            if (id == null) {
                return null;
            }
            String trimmed = id.trim();
            for (Space space : values()) {
                if (space.id.equalsIgnoreCase(trimmed)) {
                    return space;
                }
            }
            return null;
        }
    }

    /**
     * How heavy a procedural pattern draws: the size of its marks, how many cells carry a speck, and
     * which way the hatch leans.
     *
     * <h2>One tuning record rather than a field per kind</h2>
     *
     * <p>{@code size} means the same thing to every kind that has an edge — a dot, a speck, a line,
     * a hatch step — and a theme that sets one wants the whole surface to have that weight. A field
     * per kind would let a theme ask for two-pixel dots and one-pixel lines, which is a distinction
     * nobody has ever wanted and everybody would have to read the painter to learn.
     *
     * <p>The clamps are the same tolerance the rest of the record shows: a value from a file is
     * rounded to the nearest legible thing rather than refused, because a mark size of zero is an
     * invisible pattern and a size of forty is a solid rectangle, and neither is what an author
     * meant.
     *
     * @param size      the edge of a mark in pixels, clamped to 1..4
     * @param density   cells per speckle, clamped to 2..16: one in this many carries a mark
     * @param backslash whether the hatch leans the other way; false draws the slash
     */
    public record Tuning(int size, int density, boolean backslash) {

        /** The smallest mark: a single pixel, which is what every pattern drew before this existed. */
        public static final int MIN_SIZE = 1;

        /** The largest: past this a line is a stripe and a speck is a blob. */
        public static final int MAX_SIZE = 4;

        /** The densest speckle: below one in two it stops reading as speckle and reads as noise. */
        public static final int MIN_DENSITY = 2;

        /** The sparsest: past one in sixteen a field is a few lost marks. */
        public static final int MAX_DENSITY = 16;

        /** What every canvas drew before the tuning controls existed. */
        public static final Tuning DEFAULT = new Tuning(1, 5, false);

        public Tuning {
            size = clamp(size, MIN_SIZE, MAX_SIZE);
            density = clamp(density, MIN_DENSITY, MAX_DENSITY);
        }
    }

    /** How an image meets the rectangle it is drawn in. */
    public enum Fit {

        /** Repeats the texture across the rectangle at {@code tile} size, aspect preserved. */
        TILE("tile"),

        /** Draws the texture once, scaled and centred so the rectangle is fully covered. */
        COVER("cover");

        private final String id;

        Fit(String id) {
            this.id = id;
        }

        /** The name a theme file writes. */
        public String id() {
            return id;
        }

        /** The fit one file id names, or null when nothing does. */
        public static Fit byId(String id) {
            if (id == null) {
                return null;
            }
            String trimmed = id.trim();
            for (Fit fit : values()) {
                if (fit.id.equalsIgnoreCase(trimmed)) {
                    return fit;
                }
            }
            return null;
        }
    }

    /**
     * The texture an {@link Kind#IMAGE} background draws, and how it fills the rectangle.
     *
     * <p>{@code texture} is stored canonical — see {@link #canonicalTexture} — so that equality, a
     * save and a renderer lookup all compare the same file rather than the several spellings a person
     * might have written for it. The compact constructor canonicalises rather than trusting callers,
     * for the same reason the enclosing record clamps: this value is built from a file a person wrote
     * and from a text field, and a record that only sometimes stores the id it will look up is a
     * lookup that sometimes misses.
     *
     * @param texture  the canonical texture id, or empty for an image background that draws nothing
     * @param fit      how the texture fills the rectangle
     * @param tileSize the width one tile is drawn at in {@link Fit#TILE}; clamped to 4..256
     */
    public record Image(String texture, Fit fit, int tileSize) {

        /** The smallest tile: below this a texture is a smear rather than a repeat. */
        public static final int MIN_TILE = 4;

        /** The largest: past this one tile covers most of a GUI-scaled screen. */
        public static final int MAX_TILE = 256;

        /** No image: what every background but an image one carries, and what draws nothing. */
        public static final Image NONE = new Image("", Fit.TILE, 16);

        public Image {
            texture = CanvasBackground.canonicalTexture(texture);
            if (fit == null) {
                fit = Fit.TILE;
            }
            tileSize = clamp(tileSize, MIN_TILE, MAX_TILE);
        }
    }

    public CanvasBackground {
        // Tolerant construction rather than throw-on-null, because this record is built from files
        // and from controls as well as from code: a value that reached here has already been named a
        // problem by its reader, and the useful thing is a background that draws.
        if (kind == null) {
            kind = Kind.NONE;
        }
        if (space == null) {
            space = Space.GRAPH;
        }
        if (tuning == null) {
            tuning = Tuning.DEFAULT;
        }
        if (image == null) {
            image = Image.NONE;
        }
        spacing = clamp(spacing, MIN_SPACING, MAX_SPACING);
    }

    /**
     * The three-argument form every caller wrote before images and tuning existed.
     *
     * <p>Kept because that is the whole of what a theme without an image says — a pattern and where
     * it is anchored — and making every caller restate two defaulted values would mean every theme
     * carrying the tuning story in its construction. {@link #NONE} is built through this.
     */
    public CanvasBackground(Kind kind, Space space, int spacing) {
        this(kind, space, spacing, Tuning.DEFAULT, Image.NONE);
    }

    /** Whether this says "no pattern", so a painter can return before measuring anything. */
    public boolean isNone() {
        return kind == Kind.NONE;
    }

    /**
     * The canonical form of a texture id: {@code ns:textures/path.png}.
     *
     * <h2>Why it is a static on this record and not a helper on a control</h2>
     *
     * <p>Because three places have to agree on the same file — the theme file that wrote it, the
     * control that edits it, and the renderer that resolves it — and the only way for three
     * spellings to be one file is for one function to produce them all. A person types
     * {@code MyMod:GUI/Bg.PNG} or {@code textures/gui/bg}; the resource manager knows only
     * {@code mymod:textures/gui/bg.png}. Everything a person might reasonably type in between is
     * folded here: the namespace defaults to {@code minecraft}, the case is lowered, a leading
     * {@code textures/} and a trailing {@code .png} are made optional, and the result is always the
     * one spelling.
     *
     * @return the canonical id, or the empty string when nothing usable was written — a blank, or a
     *     path the resource location rules reject. Never null, so a caller can test emptiness rather
     *     than nullness
     */
    public static String canonicalTexture(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        ResourceLocation parsed = ResourceLocation.tryParse(
                lowered.indexOf(':') < 0 ? ResourceLocation.DEFAULT_NAMESPACE + ":" + lowered : lowered);
        if (parsed == null) {
            return "";
        }
        String path = parsed.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        if (path.isEmpty()) {
            return "";
        }
        return parsed.getNamespace() + ":textures/" + path + ".png";
    }

    /** Clamps a value, the one form every tolerant field here uses. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /**
     * One background from a theme file's {@code "canvasBackground"}, or null when the value says
     * nothing usable.
     *
     * <p>Tolerant in the same way {@link ThemePatch#fromJson} is, and for the same reason: a value
     * arrives from a file a person wrote, and one bad field should cost that field rather than the
     * whole pattern — so an unreadable {@code size} or {@code fit} keeps its default and is named,
     * and the object is still returned. A pattern name this build does not have is the exception — it
     * leaves the whole object unread, because guessing a different pattern would put a texture on a
     * canvas nobody asked for, and {@code null} here means "no opinion", which keeps whatever the
     * base theme had.
     *
     * @param problems a list to append human-readable messages to. Never null
     */
    public static CanvasBackground fromJson(JsonElement element, List<String> problems) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            problems.add("'canvasBackground' should be an object like"
                    + " {\"pattern\": \"dot_grid\", \"spacing\": 24}, but is " + element);
            return null;
        }
        JsonObject root = element.getAsJsonObject();

        Kind kind = Kind.NONE;
        if (root.has("pattern") && !root.get("pattern").isJsonNull()) {
            JsonElement value = root.get("pattern");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                problems.add("'pattern' should be a name like \"dot_grid\", but is " + value);
                return null;
            }
            String id = value.getAsString();
            Kind parsed = Kind.byId(id);
            if (parsed == null) {
                problems.add("'" + id + "' is not a canvas pattern this build draws. Try one of: "
                        + Kind.ids());
                return null;
            }
            kind = parsed;
        }

        Space space = Space.GRAPH;
        if (root.has("space") && !root.get("space").isJsonNull()) {
            JsonElement value = root.get("space");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                Space parsed = Space.byId(value.getAsString());
                if (parsed == null) {
                    problems.add("'" + value.getAsString() + "' is not a canvas background space. Try"
                            + " \"graph\" (moves with the content) or \"screen\" (stays put)");
                }
                else {
                    space = parsed;
                }
            }
            else {
                problems.add("'space' should be \"graph\" or \"screen\", but is " + value);
            }
        }

        int spacing = DEFAULT_SPACING;
        if (root.has("spacing") && !root.get("spacing").isJsonNull()) {
            JsonElement value = root.get("spacing");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                spacing = value.getAsInt();
                if (spacing < MIN_SPACING || spacing > MAX_SPACING) {
                    problems.add("'spacing' is clamped to " + MIN_SPACING + ".." + MAX_SPACING
                            + ": " + spacing + " was read as " + clamp(spacing, MIN_SPACING, MAX_SPACING));
                }
            }
            else {
                problems.add("'spacing' should be a whole number of units, but is " + value);
            }
        }

        int size = Tuning.DEFAULT.size();
        if (root.has("size") && !root.get("size").isJsonNull()) {
            JsonElement value = root.get("size");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                size = value.getAsInt();
                if (size < Tuning.MIN_SIZE || size > Tuning.MAX_SIZE) {
                    problems.add("'size' is clamped to " + Tuning.MIN_SIZE + ".." + Tuning.MAX_SIZE
                            + ": " + size + " was read as " + clamp(size, Tuning.MIN_SIZE, Tuning.MAX_SIZE));
                }
            }
            else {
                problems.add("'size' should be a mark size in pixels, but is " + value);
            }
        }

        int density = Tuning.DEFAULT.density();
        if (root.has("density") && !root.get("density").isJsonNull()) {
            JsonElement value = root.get("density");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                density = value.getAsInt();
                if (density < Tuning.MIN_DENSITY || density > Tuning.MAX_DENSITY) {
                    problems.add("'density' is clamped to " + Tuning.MIN_DENSITY + ".." + Tuning.MAX_DENSITY
                            + ": " + density + " was read as "
                            + clamp(density, Tuning.MIN_DENSITY, Tuning.MAX_DENSITY));
                }
            }
            else {
                problems.add("'density' should be one speck per this many cells, but is " + value);
            }
        }

        boolean backslash = Tuning.DEFAULT.backslash();
        if (root.has("direction") && !root.get("direction").isJsonNull()) {
            JsonElement value = root.get("direction");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String direction = value.getAsString().trim();
                if (direction.equalsIgnoreCase("backslash")) {
                    backslash = true;
                }
                else if (!direction.equalsIgnoreCase("slash")) {
                    problems.add("'direction' should be \"slash\" or \"backslash\", but is " + value);
                }
            }
            else {
                problems.add("'direction' should be \"slash\" or \"backslash\", but is " + value);
            }
        }

        Fit fit = Image.NONE.fit();
        if (root.has("fit") && !root.get("fit").isJsonNull()) {
            JsonElement value = root.get("fit");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                Fit parsed = Fit.byId(value.getAsString());
                if (parsed == null) {
                    problems.add("'" + value.getAsString() + "' is not a canvas image fit. Try"
                            + " \"tile\" (repeat at the tile size) or \"cover\" (scale to fill)");
                }
                else {
                    fit = parsed;
                }
            }
            else {
                problems.add("'fit' should be \"tile\" or \"cover\", but is " + value);
            }
        }

        String texture = Image.NONE.texture();
        if (root.has("texture") && !root.get("texture").isJsonNull()) {
            JsonElement value = root.get("texture");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String raw = value.getAsString();
                String canonical = canonicalTexture(raw);
                if (canonical.isEmpty() && !raw.trim().isEmpty()) {
                    problems.add("'texture' should be a texture id like"
                            + " \"minecraft:textures/gui/bg.png\", but is " + value);
                }
                texture = canonical;
            }
            else {
                problems.add("'texture' should be a texture id like"
                        + " \"minecraft:textures/gui/bg.png\", but is " + value);
            }
        }

        int tileSize = Image.NONE.tileSize();
        if (root.has("tile") && !root.get("tile").isJsonNull()) {
            JsonElement value = root.get("tile");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                tileSize = value.getAsInt();
                if (tileSize < Image.MIN_TILE || tileSize > Image.MAX_TILE) {
                    problems.add("'tile' is clamped to " + Image.MIN_TILE + ".." + Image.MAX_TILE
                            + ": " + tileSize + " was read as "
                            + clamp(tileSize, Image.MIN_TILE, Image.MAX_TILE));
                }
            }
            else {
                problems.add("'tile' should be a side in pixels, but is " + value);
            }
        }

        return new CanvasBackground(kind, space, spacing,
                new Tuning(size, density, backslash), new Image(texture, fit, tileSize));
    }

    /** This background as a file writes it. Every field stated, so a saved theme round-trips. */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("pattern", kind.id());
        root.addProperty("space", space.id());
        root.addProperty("spacing", spacing);
        root.addProperty("size", tuning.size());
        root.addProperty("density", tuning.density());
        root.addProperty("direction", tuning.backslash() ? "backslash" : "slash");
        root.addProperty("fit", image.fit().id());
        root.addProperty("texture", image.texture());
        root.addProperty("tile", image.tileSize());
        return root;
    }
}

package dev.ellipog.armature.bench;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.Look;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.RichText;
import dev.ellipog.armature.client.ui.kit.TextArea;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shapes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Headless micro-benchmarks for the perf sweep's fixes. Not a test: it asserts nothing, and it only runs
 * when {@code ARMATURE_BENCH=1} is set, so a normal build skips it.
 *
 * <p>Run with {@code ARMATURE_BENCH=1 gradlew :common:test --tests '*PerfBenchTest' --rerun}. Results go
 * to {@code ARMATURE_BENCH_OUT}, or {@code build/perf-bench.txt} under the common project.
 *
 * <p>Only public API (or reflection on a private member that both the old and the new code have) is
 * called, so the same file measures the code before and after a change. Text is measured with a
 * stand-in font: one hash lookup per character, roughly 3-10x cheaper than the game's font, so every
 * text number here understates the real cost of the slow path.
 */
@EnabledIfEnvironmentVariable(named = "ARMATURE_BENCH", matches = "1")
class PerfBenchTest {

    private static volatile long sink;

    private final List<String> lines = new ArrayList<>();

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static final long WARMUP_NANOS = 1_500_000_000L;
    private static final long SAMPLE_NANOS = 100_000_000L;
    private static final int SAMPLES = 12;

    private void bench(String name, LongSupplier op) {
        try {
            long until = System.nanoTime() + WARMUP_NANOS;
            long calls = 0;
            long acc = 0;
            while (System.nanoTime() < until) {
                acc += op.getAsLong();
                calls++;
            }
            long perSample = Math.max(1, calls * SAMPLE_NANOS / WARMUP_NANOS);
            double[] nsPerOp = new double[SAMPLES];
            for (int s = 0; s < SAMPLES; s++) {
                long start = System.nanoTime();
                for (long i = 0; i < perSample; i++) {
                    acc += op.getAsLong();
                }
                nsPerOp[s] = (System.nanoTime() - start) / (double) perSample;
            }
            sink += acc;
            Arrays.sort(nsPerOp);
            double median = (nsPerOp[SAMPLES / 2 - 1] + nsPerOp[SAMPLES / 2]) / 2.0;
            lines.add(String.format(java.util.Locale.ROOT, "%-44s %14.1f %14.1f %14.1f",
                    name, median, nsPerOp[0], nsPerOp[SAMPLES - 1]));
        }
        catch (Throwable failure) {
            lines.add(String.format(java.util.Locale.ROOT, "%-44s FAILED %s", name, failure));
        }
    }

    // ------------------------------------------------------------------
    // A stand-in font: one hashed lookup per character
    // ------------------------------------------------------------------

    private static final Map<Integer, Integer> ADVANCES = new HashMap<>();

    static {
        for (int c = 32; c < 127; c++) {
            int advance = switch (c) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
                case 'l' -> 3;
                case ' ', 't', 'I', '[', ']' -> 4;
                case 'f', 'k', '<', '>', '"', '(', ')', '{', '}' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
            ADVANCES.put(c, advance);
        }
    }

    static int fontWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            Integer advance = ADVANCES.get((int) text.charAt(i));
            width += advance == null ? 6 : advance;
        }
        return width;
    }

    private static Measure warmMemo() {
        return Measure.cached(Measure.of(PerfBenchTest::fontWidth, 9), () -> 0L);
    }

    private static final String[] WORDS = {"the", "quick", "brown", "fox", "jumps", "over", "a", "lazy",
            "dog", "craft", "an", "iron", "pickaxe", "and", "mine", "deeper", "into", "the", "caves",
            "beneath", "your", "base", "until", "you", "find", "diamonds", "redstone", "lapis"};

    private static String prose(int chars, long seed, int paragraphEvery) {
        Random random = new Random(seed);
        StringBuilder out = new StringBuilder(chars + 16);
        int sinceBreak = 0;
        while (out.length() < chars) {
            if (out.length() > 0) {
                if (paragraphEvery > 0 && sinceBreak >= paragraphEvery) {
                    out.append('\n');
                    sinceBreak = 0;
                }
                else {
                    out.append(' ');
                }
            }
            String word = WORDS[random.nextInt(WORDS.length)];
            out.append(word);
            sinceBreak += word.length() + 1;
        }
        return out.substring(0, chars);
    }

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    @Test
    void run() throws Exception {
        lines.add(String.format(java.util.Locale.ROOT, "%-44s %14s %14s %14s",
                "scenario", "median ns/op", "min", "max"));

        iconBox();
        truncate();
        sampling();
        richText();
        textArea();
        textEpoch();
        linesThatFit();
        textWrap();
        teams();
        look();

        String out = System.getenv().getOrDefault("ARMATURE_BENCH_OUT", "build/perf-bench.txt");
        Path path = Path.of(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
        System.out.println(String.join("\n", lines));
    }

    // P2 -- the icon box, once per node per frame. ns per node.
    private void iconBox() {
        Shape[] shapes = {Shapes.ROUNDED, Shapes.CIRCLE, Shapes.PENTAGON, Shapes.HEART};
        int[] sizes = {12, 24, 48};
        for (Shape shape : shapes) {
            for (int size : sizes) {
                shape.iconBox(0, 0, size);
            }
        }
        int[] counter = {0};
        bench("p2.iconBox.perNode.mixed", () -> {
            int n = counter[0]++;
            Shape shape = shapes[n & 3];
            int size = sizes[(n >>> 2) % 3];
            int[] box = shape.iconBox(n & 1023, 7, size);
            return box[0] + box[2];
        });
        bench("p2.iconBox.perNode.rounded48", () -> {
            int[] box = Shapes.ROUNDED.iconBox(5, 7, 48);
            return box[0] + box[2];
        });
    }

    // P4 -- an overflowing label through a warm memo, and one that fits.
    private void truncate() {
        Measure memo = warmMemo();
        String[] long_ = new String[20];
        for (int i = 0; i < long_.length; i++) {
            long_[i] = prose(48 + i, 100 + i, 0);
        }
        int[] counter = {0};
        bench("p4.truncate.overflowing48ch.to120px", () ->
                Measure.truncate(long_[counter[0]++ % long_.length], 120, memo).length());
        bench("p4.truncate.fits", () -> Measure.truncate("Chapter One", 120, memo).length());
    }

    // P5 -- one span table sampled at a size the shape has not got, which is what a zoom glide asks for.
    private void sampling() {
        Shape rotated = Shapes.rotated(Shapes.PENTAGON, 20);
        Object[][] shapes = {{"pentagon", Shapes.PENTAGON}, {"gear", Shapes.GEAR}, {"heart", Shapes.HEART},
                {"circle", Shapes.CIRCLE}, {"pentagonRotated20", rotated}};
        for (Object[] entry : shapes) {
            Shape shape = (Shape) entry[1];
            int[] counter = {0};
            bench("p5.sampleNewSize." + entry[0], () -> {
                int size = 30 + (counter[0]++ % 40);
                int[] row = shape.spans(size / 2, size);
                return row == null ? 0 : row[0];
            });
        }
    }

    // P6 -- one paragraph wrapped, the reader card's build. The measure is unmemoised, as styledWidth is.
    private void richText() {
        RichText.StyledWidth width = (text, bold, italic) -> fontWidth(text) + (bold ? text.length() : 0);
        for (int chars : new int[] {3000, 6000}) {
            RichText.Paragraph paragraph = RichText.parse(prose(chars, chars, 0)).get(0);
            bench("p6.richText.wrap." + chars + "ch", () -> RichText.wrap(paragraph, 300, width).size());
        }
    }

    // P7 -- the text area's wrap after an edit near the top: every substring is new to the memo.
    private void textArea() {
        for (int chars : new int[] {3000, 4096}) {
            String text = prose(chars, chars + 1, 400);
            bench("p7.textArea.wrap.coldMemo." + chars + "ch", () -> {
                Measure fresh = warmMemo();
                return TextArea.wrap(text, 300, fresh::width).size();
            });
        }
    }

    // P9 -- the epoch's per-probe font lookup, through the private method both versions keep.
    private void textEpoch() throws Exception {
        Class<?> epoch = Class.forName("dev.ellipog.armature.client.render.TextEpoch");
        Method serialOf = epoch.getDeclaredMethod("serialOf", Object.class);
        serialOf.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(serialOf)
                .asType(MethodType.methodType(long.class, Object.class));
        Object font = new Object();
        bench("p9.textEpoch.serialOf.sameFont", () -> {
            try {
                return (long) handle.invokeExact(font);
            }
            catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });
    }

    // P11 -- how many lines fit, from the top of a long block, which the text area asks every frame.
    private void linesThatFit() {
        for (int chars : new int[] {1000, 4096}) {
            List<TextArea.Span> spans = TextArea.wrap(prose(chars, 7 + chars, 120), 200, PerfBenchTest::fontWidth);
            bench("p11.linesThatFit." + spans.size() + "lines", () ->
                    TextArea.linesThatFit(spans, 0, spans.size() * 20, 9, 4));
        }
    }

    // P13 -- a multi-paragraph description wrapped through a warm memo, as a per-frame re-wrap does.
    private void textWrap() {
        Measure memo = warmMemo();
        String text = prose(3000, 13, 120);
        bench("p13.textWrap.3000ch.25para", () -> TextWrap.wrap(text, 300, memo).size());
    }

    // P14, P15 -- the stored manager's lookups, for a player in no team (the common case) and a member.
    private void teams() throws Exception {
        Class<?> storeClass = Class.forName("dev.ellipog.armature.impl.teams.TeamStore");
        Constructor<?> storeCtor = storeClass.getDeclaredConstructor();
        storeCtor.setAccessible(true);
        Method put = storeClass.getMethod("put", Team.class);
        Class<?> managerClass = Class.forName("dev.ellipog.armature.impl.teams.StoredTeamManager");
        Constructor<?> managerCtor = managerClass.getDeclaredConstructor(
                net.minecraft.server.MinecraftServer.class, storeClass);
        managerCtor.setAccessible(true);

        for (int teamCount : new int[] {50, 200}) {
            Object store = storeCtor.newInstance();
            UUID lastMember = null;
            Random random = new Random(teamCount);
            for (int t = 0; t < teamCount; t++) {
                Team team = Team.created(new UUID(random.nextLong(), random.nextLong()), "team " + t,
                        new UUID(random.nextLong(), random.nextLong()), 1L);
                for (int m = 0; m < 3; m++) {
                    lastMember = new UUID(random.nextLong(), random.nextLong());
                    team = team.withMember(lastMember, TeamRole.MEMBER);
                }
                put.invoke(store, team);
            }
            TeamManager manager = (TeamManager) managerCtor.newInstance(null, store);
            UUID solo = new UUID(1, 2);
            UUID member = lastMember;
            bench("p14.realTeamOf.solo.T" + teamCount, () -> manager.realTeamOf(solo).isPresent() ? 1 : 0);
            bench("p14.realTeamOf.member.T" + teamCount, () -> manager.realTeamOf(member).isPresent() ? 1 : 0);
            bench("p15.teamOf.id.solo.T" + teamCount, () -> manager.teamOf(solo).id().getLeastSignificantBits());

            Method teamIdOf = null;
            try {
                teamIdOf = TeamManager.class.getMethod("teamIdOf", UUID.class);
            }
            catch (NoSuchMethodException absent) {
                // Before P15: nothing to measure.
            }
            if (teamIdOf != null) {
                MethodHandle handle = MethodHandles.publicLookup().unreflect(teamIdOf)
                        .asType(MethodType.methodType(UUID.class, TeamManager.class, UUID.class));
                bench("p15.teamIdOf.solo.T" + teamCount, () -> {
                    try {
                        return ((UUID) handle.invokeExact(manager, solo)).getLeastSignificantBits();
                    }
                    catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }
        }
    }

    // P17 -- the main theme, asked for many times a frame, with and without the player's own edits.
    private void look() {
        Look plain = new Look();
        bench("p17.look.main.noEdits", () -> plain.main().cornerRadius());
        Look edited = new Look();
        edited.setCustom("panel", 0xFF26212E);
        edited.setCustom("accent", 0xFF8844CC);
        edited.setCustom("edge", 0xFF000000);
        edited.setCustom("fill", 0xFF6F6F6F);
        edited.setCustom("hover", 0xFF757575);
        edited.setRadius(3);
        bench("p17.look.main.fiveEditsAndRadius", () -> edited.main().cornerRadius());
    }
}

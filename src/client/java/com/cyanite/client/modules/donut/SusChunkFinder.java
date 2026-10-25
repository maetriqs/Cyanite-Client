package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.ChunkScanner;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Chunks somebody has kept loaded for hours. A chunk only ticks while a player is close enough
 * (within the server's simulation distance), and some things in it grow only on those ticks:
 * <ul>
 * <li>Kelp. Worldgen only puts kelp where there's water over it, so its tops never reach the
 *     surface; each time it grows it gets a block taller, and an hour of ticking takes a good few
 *     of them up to it (none of 1,100 as generated; up to a third after an hour). Oceans are up
 *     top, where the server shows what's really there. Kelp's age says it too (worldgen leaves
 *     20 to 23, ticking takes it to 25), where the server sends it: Donut sends 0 for all of it.</li>
 * <li>Amethyst. Worldgen leaves a geode's buds at all four sizes, about a quarter full clusters;
 *     after hours of ticking nearly all of them are.</li>
 * <li>Hanging mangrove propagules. Worldgen leaves them at all five ages; ticking grows every one
 *     to the last.</li>
 * <li>Dripstone. A stalactite grows only while water drips through it (a water source on top of
 *     the dripstone block it hangs from), up to seven long or till it meets the stalagmite under
 *     it. Worldgen very rarely leaves one like that (about a chunk in a thousand), never two.</li>
 * </ul>
 * Each chunk with any of that is marked at sea level. Grown kelp says more: everything within the
 * sitter's simulation distance grows and nothing past it, so a patch of grown-kelp chunks lies in
 * a square round where they sit, and the middle of the patch is the guess. Only shallow kelp
 * reaches the surface, so the patch follows the seabed and the guess can be a couple of chunks off.
 *
 * <p>You tick chunks too: where you've been a while (five minutes all told within six chunks),
 * what grew might be your doing, so it doesn't count.
 */
public class SusChunkFinder extends Module {
    /** How long a stalactite grows to, at most. */
    private static final int GROWN_LENGTH = 7;
    /** Worldgen's oldest kelp. */
    private static final int KELP_WORLDGEN_MAX = 23;
    private static final int MAX_SPOTS = 32;
    /** Your own ticking: how far round where you've been, and how long there, before what grew might be yours. */
    private static final int OWN_REACH = 6;
    private static final int OWN_SECONDS = 300;
    /** How far apart grown-kelp chunks can be and still be one sitter's patch (deep water between leaves gaps). */
    private static final int PATCH_GAP = 4;
    private static final List<Block> BUDS = List.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
        Blocks.AMETHYST_CLUSTER);

    private final BoolSetting kelp = add(new BoolSetting("Grown kelp", true));
    private final SliderSetting leastKelp = add(new SliderSetting("Least grown kelp", 2, 1, 20, 1));
    private final BoolSetting kelpAge = add(new BoolSetting("Kelp age", true));
    private final BoolSetting amethyst = add(new BoolSetting("Grown amethyst", true));
    private final SliderSetting leastBuds = add(new SliderSetting("Least buds", 8, 3, 40, 1).format(v -> (int) v + " buds"));
    private final SliderSetting grownShare = add(new SliderSetting("Fully grown share", 0.8, 0.5, 1, 0.05).percent());
    private final BoolSetting propagules = add(new BoolSetting("Grown mangrove propagules", true));
    private final BoolSetting dripstone = add(new BoolSetting("Grown dripstone", true));
    private final SliderSetting leastDrip = add(new SliderSetting("Least grown dripstone", 2, 1, 10, 1));
    private final BoolSetting guess = add(new BoolSetting("Guess where they sit", true));
    private final BoolSetting skipOwn = add(new BoolSetting("Skip where you've been", true));
    private final SliderSetting radius = add(new SliderSetting("Radius", 12, 2, 32, 1).format(v -> (int) v + " chunks"));
    private final BoolSetting markSpots = add(new BoolSetting("Mark what grew", true));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0xC77DFF));

    /**
     * What grew in a chunk: kelp tops and how many are past what worldgen leaves; amethyst buds and
     * full clusters; hanging propagules and fully grown ones; grown dripstone; and where.
     */
    private record Grown(int kelpTops, int kelpGrown, int kelpSurface, int buds, int clusters, int hanging, int hangingGrown, int dripstone,
                         List<BlockPos> spots) {
    }

    /** A chunk marked: where, why, and what grew in it. */
    private static final class Sus {
        final int cx;
        final int cz;
        String reasons;
        List<BlockPos> spots;

        Sus(int cx, int cz, String reasons, List<BlockPos> spots) {
            this.cx = cx;
            this.cz = cz;
            this.reasons = reasons;
            this.spots = spots;
        }

        Vec3 center() {
            return new Vec3(cx * 16 + 8, 0, cz * 16 + 8);
        }
    }

    /** A guess at where someone sits: the chunk, how many grown chunks it's from, and how far across they spread. */
    private record Sitter(int cx, int cz, int grown, int reach) {
        Vec3 center() {
            return new Vec3(cx * 16 + 8, 0, cz * 16 + 8);
        }
    }

    /** Marked chunks by world (server and dimension), for this session. */
    private final Map<String, Map<Long, Sus>> marked = new HashMap<>();
    /** Every chunk with kelp looked at, by world: whether its kelp has grown. */
    private final Map<String, Map<Long, Boolean>> kelpChunks = new HashMap<>();
    /** How long you've been in each chunk, by world, in seconds. */
    private final Map<String, Map<Long, Integer>> own = new HashMap<>();
    private List<Sitter> sitters = List.of();
    private boolean sittersStale;
    private int tick;

    public SusChunkFinder() {
        super(Category.DONUT, "Sus Chunk Finder", "Highlights chunks someone kept loaded for hours: grown kelp, amethyst, propagules and dripstone");
        ChunkScanner.register(new ChunkScanner.Pass<Grown>() {
            @Override
            public boolean active() {
                return isEnabled();
            }

            @Override
            public boolean wants(int cx, int cz) {
                var player = mc.player;
                if (player == null) return false;
                ChunkPos at = player.chunkPosition();
                return Math.max(Math.abs(cx - at.x), Math.abs(cz - at.z)) <= radius.get().intValue();
            }

            @Override
            public Grown scan(LevelChunk chunk) {
                return SusChunkFinder.this.scan(chunk);
            }

            @Override
            public void accept(long key, Grown grown) {
                judge(key, grown);
            }
        });
    }

    @Override
    protected void onEnable() {
        ChunkScanner.rescanAll();
    }

    @Override
    public String getInfo() {
        if (!inGame()) return null;
        return Integer.toString(marked.getOrDefault(where(), Map.of()).size());
    }

    @Override
    public void onTick() {
        // Where you are, a second at a time: your own ticking grows things too.
        if (++tick % 20 == 0) own.computeIfAbsent(where(), k -> new HashMap<>()).merge(mc.player.chunkPosition().toLong(), 1, Integer::sum);
        if (sittersStale && tick % 20 == 0) {
            sittersStale = false;
            sitters = guess.get() ? sitters(kelpChunks.getOrDefault(where(), Map.of())) : List.of();
        }
    }

    // ---- Looking at a chunk

    private Grown scan(LevelChunk chunk) {
        int kelpTops = 0, kelpGrown = 0, kelpSurface = 0, buds = 0, clusters = 0, hanging = 0, hangingGrown = 0, grownDrip = 0;
        List<BlockPos> spots = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(SusChunkFinder::grows)) continue;
            int baseY = chunk.getMinY() + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!grows(state)) continue;
                        Block block = state.getBlock();
                        boolean grown = false;
                        if (block == Blocks.KELP) {
                            // Counted, not marked one by one: there's kelp everywhere, the chunk says enough.
                            kelpTops++;
                            if (state.getValue(GrowingPlantHeadBlock.AGE) > KELP_WORLDGEN_MAX) kelpGrown++;
                            // Grown right up to the surface: no water over it.
                            if (chunk.getFluidState(p.set(x0 + x, baseY + y + 1, z0 + z)).isEmpty()) kelpSurface++;
                        } else if (BUDS.contains(block)) {
                            buds++;
                            grown = block == Blocks.AMETHYST_CLUSTER;
                            if (grown) clusters++;
                        } else if (block == Blocks.MANGROVE_PROPAGULE) {
                            if (!state.getValue(MangrovePropaguleBlock.HANGING)) continue;
                            hanging++;
                            grown = state.getValue(MangrovePropaguleBlock.AGE) == MangrovePropaguleBlock.MAX_AGE;
                            if (grown) hangingGrown++;
                        } else if (grownStalactite(chunk, p, x0 + x, baseY + y, z0 + z)) {
                            grownDrip++;
                            grown = true;
                        }
                        if (grown && spots.size() < MAX_SPOTS) spots.add(new BlockPos(x0 + x, baseY + y, z0 + z));
                    }
                }
            }
        }
        return new Grown(kelpTops, kelpGrown, kelpSurface, buds, clusters, hanging, hangingGrown, grownDrip, spots);
    }

    private static boolean grows(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.KELP || block == Blocks.POINTED_DRIPSTONE || block == Blocks.MANGROVE_PROPAGULE || BUDS.contains(block);
    }

    /**
     * Whether (x, y, z) is the top of a stalactite that's done growing: hanging from a dripstone
     * block with a water source on it (the only way one grows), and seven long or met by the
     * stalagmite under it.
     */
    private static boolean grownStalactite(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState top = chunk.getBlockState(p.set(x, y, z));
        if (!hanging(top) || hanging(chunk.getBlockState(p.set(x, y + 1, z)))) return false;
        if (!chunk.getBlockState(p.set(x, y + 1, z)).is(Blocks.DRIPSTONE_BLOCK)) return false;
        FluidState water = chunk.getFluidState(p.set(x, y + 2, z));
        if (!water.is(Fluids.WATER) || !water.isSource()) return false;
        int length = 0;
        BlockState tip = top;
        for (int at = y; at > y - 16; at--) {
            BlockState s = chunk.getBlockState(p.set(x, at, z));
            if (!hanging(s)) break;
            tip = s;
            length++;
        }
        return length >= GROWN_LENGTH || tip.getValue(PointedDripstoneBlock.THICKNESS) == DripstoneThickness.TIP_MERGE;
    }

    private static boolean hanging(BlockState state) {
        return state.is(Blocks.POINTED_DRIPSTONE) && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN;
    }

    // ---- Marking

    private void judge(long key, Grown g) {
        if (!inGame()) return;
        boolean byAge = kelpAge.get() && g.kelpGrown() >= leastKelp.get() && g.kelpGrown() * 2 >= g.kelpTops();
        boolean bySurface = g.kelpSurface() >= leastKelp.get();
        boolean kelpGrown = byAge || bySurface;
        if (g.kelpTops() > 0) {
            // For the guess at where they sit: the grown ones, left out where it could be your doing.
            // (Kelp left just under the surface proves nothing: it stops growing at 25, often a block short.)
            Map<Long, Boolean> seen = kelpChunks.computeIfAbsent(where(), k -> new HashMap<>());
            Boolean now = kelpGrown && !yours(key) ? Boolean.TRUE : null;
            Boolean was = now == null ? seen.remove(key) : seen.put(key, now);
            if (!Objects.equals(was, now)) sittersStale = true;
        }
        // What grew where you've been a while may be your own doing.
        if (skipOwn.get() && yours(key)) return;
        List<String> why = new ArrayList<>();
        if (kelp.get() && bySurface) why.add(g.kelpSurface() + " kelp grown up to the surface");
        else if (kelp.get() && byAge) why.add(g.kelpGrown() + " of " + g.kelpTops() + " kelp grown");
        if (amethyst.get() && g.buds() >= leastBuds.get() && g.clusters() >= g.buds() * grownShare.get()) {
            why.add(g.clusters() + " of " + g.buds() + " amethyst buds fully grown");
        }
        if (propagules.get() && g.hanging() >= leastBuds.get() && g.hangingGrown() >= g.hanging() * grownShare.get()) {
            why.add(g.hangingGrown() + " of " + g.hanging() + " mangrove propagules fully grown");
        }
        if (dripstone.get() && g.dripstone() >= leastDrip.get()) {
            why.add(g.dripstone() == 1 ? "a fully grown dripstone" : g.dripstone() + " fully grown dripstones");
        }
        if (why.isEmpty()) return;
        String reasons = String.join(", ", why);
        Map<Long, Sus> here = marked.computeIfAbsent(where(), k -> new HashMap<>());
        Sus s = here.get(key);
        if (s != null) {
            s.reasons = reasons;
            s.spots = g.spots();
            return;
        }
        s = new Sus(ChunkPos.getX(key), ChunkPos.getZ(key), reasons, g.spots());
        here.put(key, s);
        if (notify.get()) {
            long dist = Math.round(Math.hypot(s.cx * 16 + 8 - mc.player.getX(), s.cz * 16 + 8 - mc.player.getZ()));
            Notify.send("Sus chunk", (s.cx * 16 + 8) + ", " + (s.cz * 16 + 8) + " (" + dist + "m): " + reasons, true, true, false);
        }
    }

    /** Whether you've spent long enough near this chunk for what grew in it to be your own doing. */
    private boolean yours(long key) {
        if (!skipOwn.get()) return false;
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        int seconds = 0;
        for (Map.Entry<Long, Integer> e : own.getOrDefault(where(), Map.of()).entrySet()) {
            if (Math.max(Math.abs(ChunkPos.getX(e.getKey()) - cx), Math.abs(ChunkPos.getZ(e.getKey()) - cz)) <= OWN_REACH) seconds += e.getValue();
            if (seconds >= OWN_SECONDS) return true;
        }
        return false;
    }

    // ---- Where they sit

    /** Each patch of grown-kelp chunks, and its middle: about where whoever kept it ticking sits. */
    private static List<Sitter> sitters(Map<Long, Boolean> kelp) {
        List<long[]> grown = new ArrayList<>();
        for (Map.Entry<Long, Boolean> e : kelp.entrySet()) {
            if (e.getValue()) grown.add(new long[] {ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey())});
        }
        List<Sitter> out = new ArrayList<>();
        Set<Integer> done = new HashSet<>();
        for (int i = 0; i < grown.size(); i++) {
            if (!done.add(i)) continue;
            // One patch: grown chunks joined by gaps of a few chunks at most.
            List<long[]> patch = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            while (!queue.isEmpty()) {
                long[] c = grown.get(queue.poll());
                patch.add(c);
                for (int j = 0; j < grown.size(); j++) {
                    if (!done.contains(j) && far(c, grown.get(j)) <= PATCH_GAP) {
                        done.add(j);
                        queue.add(j);
                    }
                }
            }
            long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE, minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
            for (long[] c : patch) {
                minX = Math.min(minX, c[0]);
                maxX = Math.max(maxX, c[0]);
                minZ = Math.min(minZ, c[1]);
                maxZ = Math.max(maxZ, c[1]);
            }
            int spread = (int) Math.max(maxX - minX, maxZ - minZ) + 1;
            out.add(new Sitter((int) Math.floorDiv(minX + maxX, 2), (int) Math.floorDiv(minZ + maxZ, 2), patch.size(), spread));
        }
        return out;
    }

    private static long far(long[] a, long[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        Map<Long, Sus> here = marked.get(where());
        int rgb = 0xFF000000 | color.get();
        double sea = mc.level.getSeaLevel();
        if (here != null) {
            for (Sus s : here.values()) {
                Vec3 c = s.center();
                if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
                // The chunk, flat at sea level.
                render.box(new AABB(s.cx * 16, sea, s.cz * 16, s.cx * 16 + 16, sea + 0.2, s.cz * 16 + 16),
                    Theme.alpha(rgb, 46), Theme.alpha(rgb, 220), 2f, true);
                if (markSpots.get()) {
                    for (BlockPos spot : s.spots) render.box(new AABB(spot).inflate(0.02), Theme.alpha(rgb, 40), Theme.alpha(rgb, 200), 1.2f, false);
                }
            }
        }
        // Where they probably sit: a beam up from sea level.
        for (Sitter s : sitters) {
            Vec3 c = s.center();
            if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
            render.box(new AABB(s.cx() * 16 + 5, sea, s.cz() * 16 + 5, s.cx() * 16 + 11, sea + 0.3, s.cz() * 16 + 11),
                Theme.alpha(0xFFFFD43B, 70), Theme.alpha(0xFFFFD43B, 230), 2f, true);
            for (int i = 0; i < 16; i++) {
                float fade = 1 - i / 16f;
                Vec3 from = c.add(0, sea + i * 6, 0);
                render.line(from, from.add(0, 6, 0), Theme.alpha(0xFFFFD43B, 235 * fade * fade), 2.6f, true);
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double focus = 90 * window.getHeight() / 1080.0;
        double sea = mc.level.getSeaLevel();
        Object focused = null;
        double focusedOff = focus;
        List<Object[]> shown = new ArrayList<>();
        List<Object> all = new ArrayList<>(marked.getOrDefault(where(), Map.of()).values());
        all.addAll(sitters);
        for (Object o : all) {
            Vec3 c = o instanceof Sus s ? s.center() : ((Sitter) o).center();
            double[] screen = Projection.toScreen(c.add(0, sea + (o instanceof Sitter ? 4 : 1), 0));
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < focusedOff) {
                focused = o;
                focusedOff = off;
            }
            shown.add(new Object[] {o, screen});
        }
        for (Object[] o : shown) {
            if (o[0] instanceof Sus s) {
                // In a sitter's square the one label says it all; a chunk's own shows when you look at it.
                if (o[0] != focused && insideSitter(s)) continue;
                badge(g, "Sus chunk", s.center(), s.reasons, 0xFF000000 | color.get(), (double[]) o[1], o[0] == focused);
            } else {
                Sitter s = (Sitter) o[0];
                String why = s.grown() + (s.grown() == 1 ? " chunk" : " chunks") + " of grown kelp, over " + s.reach() + " chunks across";
                badge(g, "Someone sits about here", s.center(), why, 0xFFFFD43B, (double[]) o[1], o[0] == focused);
            }
        }
    }

    private boolean insideSitter(Sus s) {
        for (Sitter t : sitters) if (Math.max(Math.abs(s.cx - t.cx()), Math.abs(s.cz - t.cz())) <= Math.max(t.reach() / 2 + 1, PATCH_GAP)) return true;
        return false;
    }

    /** "Sus chunk · 312m", and when looked at, where and why. */
    private void badge(GuiGraphics g, String title, Vec3 c, String reasons, int rgb, double[] screen, boolean focused) {
        int scale = Render2D.scale();
        String dist = Math.round(Math.hypot(c.x - mc.player.getX(), c.z - mc.player.getZ())) + "m";
        List<String> lines = new ArrayList<>();
        if (focused) {
            lines.add(StreamerMode.mask(Math.round(c.x) + ", " + Math.round(c.z)));
            lines.add(reasons);
        }
        int pad = 4;
        int line = Text.CAP + 4;
        int w = Text.width(title) + 6 + Text.width(dist);
        for (String l : lines) w = Math.max(w, Text.width(l));
        w += pad * 2;
        int h = pad * 2 + Text.CAP + 1 + lines.size() * line;
        int x = (int) Math.round(screen[0] / scale) - w / 2;
        int y = (int) Math.round(screen[1] / scale) - h - 6;
        float a = focused ? 1 : 0.85f;
        Render2D.pixels(g);
        Render2D.glow(g, x * scale, y * scale, w * scale, h * scale, 3 * scale, 6 * scale, Theme.alpha(rgb, (focused ? 90 : 50) * Theme.glow()));
        Render2D.rect(g, x * scale, y * scale, w * scale, h * scale, 3 * scale, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, x * scale + 1, y * scale + 1, w * scale - 2, h * scale - 2, 3 * scale - 1, Theme.fade(0xE6101217, a));
        Render2D.rect(g, x * scale + 3 * scale, y * scale + 1, w * scale - 6 * scale, Math.max(1, scale / 2), 0, rgb);
        Render2D.end(g);
        Text.draw(g, title, x + pad, y + pad + 1, Theme.fade(rgb, a));
        Text.draw(g, dist, x + pad + Text.width(title) + 6, y + pad + 1, Theme.TEXT_FAINT);
        int ly = y + pad + 1 + line;
        for (String l : lines) {
            Text.draw(g, l, x + pad, ly, Theme.TEXT_DIM);
            ly += line;
        }
    }

    /** The server and the dimension: marks from one world don't show in another. */
    private String where() {
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) server = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else server = "unknown";
        return server + "|" + (mc.level == null ? "" : mc.level.dimension().identifier().toString());
    }
}

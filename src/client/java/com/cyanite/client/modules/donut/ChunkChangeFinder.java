package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.render.Badge;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Simulation;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.CreakingHeartBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FrogspawnBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SculkBlock;
import net.minecraft.world.level.block.SculkCatalystBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SnifferEggBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What changed while you weren't being told. When you go down past a set Y it has the server let
 * go of the chunks round you for half a second (it says your view distance is 2, then says it
 * again as it really is; your own render distance is never touched), so it sends them all again.
 * On Donut, chunks under Y 16 that come in while you're down there come in real.
 *
 * <p>Each chunk the client already had is compared with what comes back. What it had is copied
 * the moment the server takes it away (or sends it again over the top), so it's everything the
 * server told you, block changes and all: whatever's different now changed without a word to
 * you. Under Y 32 Donut can send made-up rock while you're up top, so down there a section is
 * only compared where it came in real both times, and what came in real is kept when made-up
 * rock replaces it, for the next trip down: what someone's dug or built down there in between
 * shows then.
 *
 * <p>Things that change on their own don't count: water and lava, fire, snow and ice, leaves,
 * plants and crops, grass and dirt turning to each other, copper weathering, sculk, mobs on
 * pressure plates and doors. Nor do ores (servers hiding ores swap them for rock and back), a
 * section where deepslate's swapped for tuff and gravel (made-up rock going in or out), blocks
 * only changing state (unless asked), or anything near where you've been yourself.
 *
 * <p>Each changed chunk flags the square round it; where several squares overlap, the overlap.
 * The forced resend and the comparison follow the Anubis client (GPL-3.0, its "Render Distance
 * Toggle" and "Quantum"); the code is Cyanite's own.
 */
public class ChunkChangeFinder extends Module {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Under this Donut sends made-up rock while you're up top. */
    private static final int ABOVE_MADE_UP = 32;
    /** The view distance the server's told while it lets go. */
    private static final int LOW = 2;
    /** How far back up over the line before it resends again. */
    private static final int REARM = 2;
    private static final int MAX_SPOTS = 48;
    private static final int MAX_HITS = 256;
    private static final int MAX_KNOWN = 3000;
    /** Deepslate swapped for tuff, gravel and the like this often in a section: made-up rock going in or out. */
    private static final int SWAP_LEAST = 16;

    /** Hidden by servers that hide ores: swapped for rock and back from one send to the next. */
    private static final Set<Block> ORES = Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS,
        Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_GOLD_BLOCK);
    /** Ground that turns into each other by itself: grass spreading and dying back, farmland drying out, nylium. */
    private static final Set<Block> GROUND = Set.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL,
        Blocks.MYCELIUM, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM);
    /** What real rock down there has that made-up rock (all deepslate) doesn't. */
    private static final Set<Block> ROCK = Set.of(Blocks.STONE, Blocks.TUFF, Blocks.GRAVEL, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
        Blocks.CALCITE, Blocks.DIRT, Blocks.CLAY, Blocks.SMOOTH_BASALT);

    /** One thread for the comparing: a resend brings hundreds of chunks at once. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Cyanite chunk compare");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final BoolSetting resend = add(new BoolSetting("Resend going down", true));
    private final SliderSetting below = add(new SliderSetting("Resend below Y", -1, -64, 320, 1).format(v -> "Y " + (int) v));
    private final SliderSetting hold = add(new SliderSetting("Hold for", 0.5, 0.25, 3, 0.25)
        .format(v -> String.format(Locale.ROOT, "%.2fs", v)));
    private final SliderSetting range = add(new SliderSetting("Range", 12, 4, 32, 1).format(v -> (int) v + " chunks"));
    private final BoolSetting deep = add(new BoolSetting("Under Y 32 too", true));
    private final BoolSetting states = add(new BoolSetting("State changes", false));
    private final SliderSetting skipNear = add(new SliderSetting("Skip near you", 1, 0, 4, 1)
        .format(v -> v < 1 ? "Off" : (int) v + ((int) v == 1 ? " chunk" : " chunks")));
    private final SliderSetting least = add(new SliderSetting("Least changed blocks", 1, 1, 32, 1));
    private final SliderSetting area = add(new SliderSetting("Area radius", 9, 0, 16, 1)
        .format(v -> (int) v + ((int) v == 1 ? " chunk" : " chunks")));
    private final BoolSetting markSpots = add(new BoolSetting("Mark changed blocks", true));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0x38D9A9));

    /**
     * A chunk to compare, on the worker: what was known of it and what's come in, section by
     * section from the bottom of the world (copies, so nothing else touches them), which sections
     * are near where you've been (bits), and whether state changes count.
     */
    private record Job(long key, int gen, int minSection, int firstUpper, PalettedContainer<BlockState>[] was,
                       PalettedContainer<BlockState>[] now, long near, boolean states) {
    }

    /** One block that changed. */
    private record Change(BlockPos pos, BlockState was, BlockState now) {
    }

    /** What turned into what. */
    private record Kind(Block was, Block now) {
    }

    /**
     * What the worker found: which sections under Y 32 came in real (bits), how many blocks
     * changed, some of them, and what kinds.
     */
    private record Diff(long key, int gen, long real, boolean compared, int blocks, List<Change> changes, Map<Kind, Integer> kinds) {
    }

    /** A chunk that changed: where, what, and when last. */
    private static final class Hit {
        final int cx;
        final int cz;
        long time;
        int blocks;
        String what;
        final List<BlockPos> spots = new ArrayList<>();
        AABB bounds;

        Hit(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        void add(Diff d, String what) {
            time = System.currentTimeMillis();
            blocks += d.blocks();
            this.what = what;
            for (Change c : d.changes()) {
                if (spots.size() >= MAX_SPOTS) break;
                if (!spots.contains(c.pos())) spots.add(c.pos());
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : spots) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
            }
            bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }

        Vec3 center() {
            return bounds.getCenter();
        }

        String where() {
            int lo = (int) bounds.minY, hi = (int) bounds.maxY - 1;
            return lo == hi ? "at Y " + lo : "Y " + lo + " to " + hi;
        }
    }

    /**
     * What was known of chunks the client let go of or had sent again, by chunk: section copies,
     * null where nothing's known (or what came in was made up).
     */
    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>[]> known = new Long2ObjectOpenHashMap<>();
    /** Loaded chunks: which of their sections under Y 32 came in real (bits), as the server sent them. */
    private final Long2LongOpenHashMap realMask = new Long2LongOpenHashMap();
    /** Where you've been: chunk columns, with the sections you were in (bits). */
    private final Long2LongOpenHashMap visited = new Long2LongOpenHashMap();
    private final Queue<Diff> results = new ConcurrentLinkedQueue<>();
    /** Changed chunks by world (server and dimension). */
    private final Map<String, Long2ObjectOpenHashMap<Hit>> hits = new HashMap<>();
    private final List<Hit> fresh = new ArrayList<>();
    private List<Simulation.Area> areas = List.of();
    private int areasFor;
    private boolean areasStale;
    /** Bumped on every new world and toggle: what the worker's still busy with from before is dropped. */
    private volatile int gen;
    private Object level;
    /** Chunks compared, and resends made, since it was switched on. */
    private int compared;
    private int resends;
    private int tick;

    // The resend
    private boolean armed;
    /** Ticks left until the server's told your real view distance again; 0 when it already has it. */
    private int lowered;
    private ClientPacketListener loweredOn;
    private Object loweredIn;

    public ChunkChangeFinder() {
        super(Category.DONUT, "Chunk Change Finder", "Has the server resend the chunks round you when you go down, and flags what changed since it last sent them");
        area.onChange(() -> areasStale = true);
    }

    @Override
    protected void onEnable() {
        level = null;
        hits.clear();
        fresh.clear();
        areas = List.of();
        compared = 0;
        resends = 0;
        armed = true;
        lowered = 0;
        loweredOn = null;
        loweredIn = null;
        if (inGame()) sync();
    }

    @Override
    protected void onDisable() {
        restore();
        gen++;
        known.clear();
        realMask.clear();
        visited.clear();
        results.clear();
    }

    @Override
    public String getInfo() {
        if (lowered > 0) return "resending";
        if (!inGame()) return null;
        Long2ObjectOpenHashMap<Hit> here = hits.get(where());
        return here == null || here.isEmpty() ? null : Integer.toString(here.size());
    }

    private static ChunkChangeFinder on() {
        ChunkChangeFinder f = Modules.get(ChunkChangeFinder.class);
        return f != null && f.isEnabled() && inGame() ? f : null;
    }

    /** A new world: what was known of the last one means nothing here. */
    private void sync() {
        if (mc.level == level) return;
        level = mc.level;
        gen++;
        known.clear();
        realMask.clear();
        visited.clear();
        results.clear();
        areasStale = true;
        // Changed worlds mid-resend (same server): it's told your real view distance now.
        if (lowered > 0) restore();
    }

    /** A packet hook went wrong: off, like a module whose tick throws, rather than kicking you off the server. */
    private void fail(String what, RuntimeException e) {
        LOGGER.error("{} threw {}, disabling it", name, what, e);
        setEnabled(false);
    }

    // ---- What the server sends (game thread)

    /** A chunk's about to come in: what the client has of it now, before it's written over. */
    public static void onChunkComing(int x, int z) {
        ChunkChangeFinder f = on();
        if (f == null) return;
        try {
            f.sync();
            LevelChunk chunk = loaded(x, z);
            if (chunk != null && f.inRange(ChunkPos.asLong(x, z))) f.remember(chunk);
        } catch (RuntimeException e) {
            f.fail("reading a chunk before it was sent again", e);
        }
    }

    /** A chunk's come in: compare it with what was known, off the game thread. */
    public static void onChunkCame(int x, int z) {
        ChunkChangeFinder f = on();
        if (f == null) return;
        try {
            f.sync();
            f.arrived(x, z);
        } catch (RuntimeException e) {
            f.fail("reading a chunk that came in", e);
        }
    }

    /** The server's taking a chunk away: what the client had of it, before it's gone. */
    public static void onChunkForgetting(ChunkPos pos) {
        ChunkChangeFinder f = on();
        if (f == null) return;
        try {
            f.sync();
            long key = pos.toLong();
            LevelChunk chunk = loaded(pos.x, pos.z);
            if (chunk != null && f.inRange(key)) f.remember(chunk);
            f.realMask.remove(key);
        } catch (RuntimeException e) {
            f.fail("reading a chunk the server let go of", e);
        }
    }

    private static LevelChunk loaded(int x, int z) {
        return mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    private boolean inRange(long key) {
        return Simulation.apart(key, mc.player.chunkPosition().toLong()) <= range.get().intValue();
    }

    /** The first section that's all at Y 32 or over. */
    private static int firstUpper(int sections) {
        return Math.max(0, Math.min(sections, SectionPos.blockToSectionCoord(ABOVE_MADE_UP) - mc.level.getMinSectionY()));
    }

    /**
     * Copies what the client has of a chunk into what's known of it: everything from Y 32 up, and
     * under that the sections that came in real (made-up rock leaves what was known from before).
     */
    private void remember(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        LevelChunkSection[] live = chunk.getSections();
        int firstUpper = firstUpper(live.length);
        long real = deep.get() ? realMask.get(key) : 0;
        PalettedContainer<BlockState>[] keep = known.get(key);
        if (keep == null || keep.length != live.length) keep = newArray(live.length);
        boolean any = false;
        for (int i = 0; i < live.length; i++) {
            if (i >= firstUpper || i < 64 && (real >>> i & 1) != 0) keep[i] = live[i].getStates().copy();
            any |= keep[i] != null;
        }
        if (any) known.put(key, keep);
    }

    private void arrived(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        LevelChunk chunk = loaded(x, z);
        PalettedContainer<BlockState>[] was = known.get(key);
        if (chunk == null) return;
        LevelChunkSection[] live = chunk.getSections();
        int firstUpper = firstUpper(live.length);
        if (was != null && was.length != live.length) {
            known.remove(key);
            was = null;
        }
        PalettedContainer<BlockState>[] before = was == null ? null : was.clone();
        // From Y 32 up the client has it all now: the copies of before can go (under that, made-up rock may have come in over them).
        if (was != null) {
            for (int i = firstUpper; i < was.length; i++) was[i] = null;
            if (empty(was)) known.remove(key);
        }
        if (!inRange(key)) {
            // Not looked at: whether it came in real isn't known.
            realMask.remove(key);
            return;
        }
        // What's come in: from Y 32 up where there's something to compare it with; under that,
        // always, to see whether it came in real.
        PalettedContainer<BlockState>[] now = newArray(live.length);
        boolean any = false;
        for (int i = 0; i < live.length; i++) {
            boolean upper = i >= firstUpper;
            if (upper ? before != null && before[i] != null : deep.get() && i < 64) {
                now[i] = live[i].getStates().copy();
                any = true;
            }
        }
        if (!any) return;
        Job job = new Job(key, gen, chunk.getMinSectionY(), firstUpper, before, now, nearMask(x, z), states.get());
        WORKER.execute(() -> work(job));
    }

    /** The sections of a chunk that are near where you've been (bits): within the setting's chunks and a section up or down. */
    private long nearMask(int cx, int cz) {
        int r = skipNear.get().intValue();
        if (r < 1) return 0;
        long mask = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) mask |= visited.get(ChunkPos.asLong(cx + dx, cz + dz));
        }
        return mask | mask << 1 | mask >>> 1;
    }

    private static boolean empty(PalettedContainer<BlockState>[] sections) {
        for (PalettedContainer<BlockState> s : sections) if (s != null) return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] newArray(int n) {
        return (PalettedContainer<BlockState>[]) new PalettedContainer[n];
    }

    // ---- Comparing (the worker's thread: only the copies in the job)

    private void work(Job job) {
        if (job.gen() != gen) return;
        try {
            results.add(compare(job));
        } catch (RuntimeException e) {
            LOGGER.error("{} threw comparing chunk {}", name, new ChunkPos(job.key()), e);
        }
    }

    private static Diff compare(Job job) {
        long real = 0;
        int blocks = 0;
        boolean compared = false;
        List<Change> changes = new ArrayList<>();
        Map<Kind, Integer> kinds = new HashMap<>();
        int x0 = ChunkPos.getX(job.key()) << 4;
        int z0 = ChunkPos.getZ(job.key()) << 4;
        PalettedContainer<BlockState>[] now = job.now();
        for (int i = 0; i < now.length; i++) {
            PalettedContainer<BlockState> after = now[i];
            if (after == null) continue;
            if (i < job.firstUpper()) {
                if (!real(after)) continue;
                real |= 1L << i;
            }
            PalettedContainer<BlockState> before = job.was() == null || i >= job.was().length ? null : job.was()[i];
            if (before == null) continue;
            compared = true;
            if ((job.near() >>> Math.min(i, 63) & 1) != 0) continue;
            int baseY = (job.minSection() + i) << 4;
            int swaps = 0, found = 0;
            List<Change> here = new ArrayList<>();
            Map<Kind, Integer> hereKinds = new HashMap<>();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState a = before.get(x, y, z);
                        BlockState b = after.get(x, y, z);
                        if (a == b) continue;
                        if (swap(a, b)) {
                            swaps++;
                            continue;
                        }
                        if (!counts(a, b, job.states())) continue;
                        found++;
                        hereKinds.merge(new Kind(a.getBlock(), b.getBlock()), 1, Integer::sum);
                        if (here.size() < MAX_SPOTS) here.add(new Change(new BlockPos(x0 + x, baseY + y, z0 + z), a, b));
                    }
                }
            }
            // Made-up rock going in or out (it came in looking real but wasn't, one of the times): not anyone's doing.
            if (swaps >= SWAP_LEAST || found == 0) continue;
            blocks += found;
            for (Change c : here) if (changes.size() < MAX_SPOTS) changes.add(c);
            hereKinds.forEach((k, n) -> kinds.merge(k, n, Integer::sum));
        }
        return new Diff(job.key(), job.gen(), real, compared, blocks, changes, kinds);
    }

    /** Whether a section came in real: not all deepslate (and the bedrock floor), the way made-up rock is. Counted, not read off the palette. */
    private static boolean real(PalettedContainer<BlockState> section) {
        if (!section.maybeHas(s -> !madeUp(s))) return false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) if (!madeUp(section.get(x, y, z))) return true;
            }
        }
        return false;
    }

    private static boolean madeUp(BlockState s) {
        return s.is(Blocks.DEEPSLATE) || s.is(Blocks.BEDROCK);
    }

    /** Deepslate one time, rock that real ground has the other: made-up rock, not a change. */
    private static boolean swap(BlockState a, BlockState b) {
        return a.is(Blocks.DEEPSLATE) && ROCK.contains(b.getBlock()) || b.is(Blocks.DEEPSLATE) && ROCK.contains(a.getBlock());
    }

    /** Whether a block going from {@code a} to {@code b} is someone's doing (as far as one block can say). */
    private static boolean counts(BlockState a, BlockState b, boolean states) {
        if (a.isAir() && b.isAir()) return false;
        Block was = a.getBlock(), now = b.getBlock();
        if (was == now) return states && !natural(was);
        if (natural(was) || natural(now)) return false;
        if (ORES.contains(was) || ORES.contains(now)) return false;
        return !(GROUND.contains(was) && GROUND.contains(now));
    }

    /** Blocks that come, go or change by themselves: growing, spreading, melting, burning, weathering, or mobs and the weather setting them off. */
    private static boolean natural(Block b) {
        return b instanceof LiquidBlock || b instanceof BubbleColumnBlock || b instanceof BaseFireBlock || b instanceof LeavesBlock
            || b instanceof VegetationBlock || b instanceof GrowingPlantBlock || b instanceof MultifaceBlock || b instanceof VineBlock
            || b instanceof SnowLayerBlock || b instanceof PowderSnowBlock || b instanceof IceBlock
            || b instanceof SugarCaneBlock || b instanceof CactusBlock || b instanceof BambooStalkBlock || b instanceof BambooSaplingBlock
            || b instanceof CocoaBlock || b instanceof ChorusPlantBlock || b instanceof ChorusFlowerBlock || b == Blocks.PUMPKIN || b == Blocks.MELON
            || b instanceof PointedDripstoneBlock || b instanceof AmethystClusterBlock || b instanceof BigDripleafBlock || b instanceof BigDripleafStemBlock
            || b instanceof HangingMossBlock || b instanceof MossyCarpetBlock || b instanceof CoralBlock || b instanceof BaseCoralPlantTypeBlock
            || b instanceof TurtleEggBlock || b instanceof SnifferEggBlock || b instanceof FrogspawnBlock
            || b instanceof SculkBlock || b instanceof SculkSensorBlock || b instanceof SculkCatalystBlock || b instanceof SculkShriekerBlock
            || b instanceof AbstractCauldronBlock || b instanceof WeatheringCopper || b instanceof InfestedBlock || b instanceof CreakingHeartBlock
            || b instanceof BeehiveBlock || b instanceof ComposterBlock || b instanceof DoorBlock || b instanceof BellBlock
            || b instanceof BasePressurePlateBlock || b instanceof TripWireBlock || b instanceof TripWireHookBlock
            || b instanceof DaylightDetectorBlock || b instanceof LightningRodBlock || b instanceof TrialSpawnerBlock || b instanceof VaultBlock;
    }

    // ---- Marking (game thread)

    @Override
    public void onTick() {
        sync();
        tick++;
        // Where you are: what changes near there may be your own doing.
        BlockPos at = mc.player.blockPosition();
        int section = Math.max(0, Math.min(63, mc.level.getSectionIndex(at.getY())));
        long column = ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4);
        visited.put(column, visited.get(column) | 1L << section);

        for (Diff d; (d = results.poll()) != null; ) accept(d);
        if (!fresh.isEmpty()) {
            if (notify.get()) tell(fresh);
            fresh.clear();
        }
        resendTick();
        if (tick % 20 == 0) prune();
        if (areasStale) {
            areasStale = false;
            areas = areas();
        }
    }

    private void accept(Diff d) {
        if (d.gen() != gen) return;
        long key = d.key();
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        if (d.compared()) compared++;
        if (loaded(cx, cz) != null) {
            if (d.real() != 0) realMask.put(key, d.real());
            else realMask.remove(key);
            // What came in real is in the client now: what was known from before under it can go.
            PalettedContainer<BlockState>[] was = known.get(key);
            if (was != null && d.real() != 0) {
                for (int i = 0; i < Math.min(64, was.length); i++) if ((d.real() >>> i & 1) != 0) was[i] = null;
                if (empty(was)) known.remove(key);
            }
        }
        if (d.changes().isEmpty() || d.blocks() < least.get()) return;
        Long2ObjectOpenHashMap<Hit> here = hits.computeIfAbsent(where(), k -> new Long2ObjectOpenHashMap<>());
        Hit h = here.get(key);
        if (h == null) {
            if (here.size() >= MAX_HITS) {
                long oldest = 0;
                long when = Long.MAX_VALUE;
                for (Long2ObjectOpenHashMap.Entry<Hit> e : here.long2ObjectEntrySet()) {
                    if (e.getValue().time < when) {
                        when = e.getValue().time;
                        oldest = e.getLongKey();
                    }
                }
                here.remove(oldest);
            }
            h = new Hit(cx, cz);
            here.put(key, h);
            fresh.add(h);
            areasStale = true;
        }
        h.add(d, describe(d.kinds()));
    }

    /** The commonest kinds of change, in words: "deepslate broken (40), chest placed". */
    private static String describe(Map<Kind, Integer> kinds) {
        List<String> parts = new ArrayList<>();
        kinds.entrySet().stream().sorted(Map.Entry.<Kind, Integer>comparingByValue().reversed()).limit(3).forEach(e -> {
            Kind k = e.getKey();
            String what;
            if (k.was().defaultBlockState().isAir()) what = name(k.now()) + " placed";
            else if (k.now().defaultBlockState().isAir()) what = name(k.was()) + " broken";
            else if (k.was() == k.now()) what = name(k.was()) + " changed";
            else what = name(k.was()) + " turned to " + name(k.now());
            parts.add(e.getValue() > 1 ? what + " (" + e.getValue() + ")" : what);
        });
        if (kinds.size() > 3) parts.add("and more");
        return String.join(", ", parts);
    }

    private static String name(Block b) {
        return b.getName().getString().toLowerCase(Locale.ROOT);
    }

    private void tell(List<Hit> found) {
        Hit nearest = null;
        double best = Double.MAX_VALUE;
        for (Hit h : found) {
            double d = Math.hypot(h.center().x - mc.player.getX(), h.center().z - mc.player.getZ());
            if (d < best) {
                best = d;
                nearest = h;
            }
        }
        if (nearest == null) return;
        Vec3 c = nearest.center();
        String at = (int) Math.floor(c.x) + ", " + (int) Math.floor(c.z) + " (" + Math.round(best) + "m)";
        String body = found.size() == 1
            ? at + ": " + nearest.what + ", " + nearest.where()
            : found.size() + " chunks changed since they were last sent, nearest " + at + ": " + nearest.what + ", " + nearest.where();
        Notify.send("Chunk changed", body, true, true, false);
    }

    /** Every changed chunk flags the square round it; where squares overlap, the overlap (see {@link Simulation#hottest}). */
    private List<Simulation.Area> areas() {
        areasFor = area.get().intValue();
        Long2ObjectOpenHashMap<Hit> here = hits.get(where());
        // No square: each changed chunk speaks for itself.
        if (here == null || here.isEmpty() || areasFor < 1) return List.of();
        Long2IntOpenHashMap heat = new Long2IntOpenHashMap();
        for (long k : here.keySet()) Simulation.spread(heat, k, areasFor, 1);
        return Simulation.hottest(heat, new LongOpenHashSet(), 1);
    }

    /** What's known of chunks far off goes: you're not getting them back soon, and they take room. */
    private void prune() {
        long here = mc.player.chunkPosition().toLong();
        int keep = range.get().intValue() + 16;
        known.long2ObjectEntrySet().removeIf(e -> Simulation.apart(e.getLongKey(), here) > keep);
        if (known.size() > MAX_KNOWN) {
            // The farthest first.
            List<Long> keys = new ArrayList<>(known.keySet());
            keys.sort(Comparator.comparingInt((Long k) -> Simulation.apart(k, here)).reversed());
            for (int i = 0; i < keys.size() && known.size() > MAX_KNOWN; i++) known.remove((long) keys.get(i));
        }
        if (visited.size() > 65536) visited.clear();
    }

    /** The chunks flagged in this world, for the tests. */
    private List<Long> hitChunks() {
        Long2ObjectOpenHashMap<Hit> here = hits.get(where());
        return here == null ? List.of() : new ArrayList<>(here.keySet());
    }

    // ---- The resend

    private void resendTick() {
        ClientPacketListener connection = mc.getConnection();
        if (lowered > 0) {
            // A new server, a new world, or switched off: it's told your real view distance now.
            if (connection != loweredOn || mc.level != loweredIn || !resend.get() || --lowered <= 0) restore();
            return;
        }
        int y = mc.player.getBlockY();
        int line = below.get().intValue();
        if (y > line + REARM) armed = true;
        if (armed && resend.get() && y <= line) {
            armed = false;
            lower(connection);
        }
    }

    /**
     * Tells the server your view distance is 2, so it lets go of every chunk past that; telling it
     * the real one again ({@link #restore}) has it send them all back. Only the server's told:
     * your render distance setting stays as it is.
     */
    private void lower(ClientPacketListener connection) {
        if (connection == null) return;
        ClientInformation mine = mc.options.buildPlayerInformation();
        if (mine.viewDistance() <= LOW) return;
        connection.broadcastClientInformation(new ClientInformation(mine.language(), LOW, mine.chatVisibility(), mine.chatColors(),
            mine.modelCustomisation(), mine.mainHand(), mine.textFilteringEnabled(), mine.allowsListing(), mine.particleStatus()));
        loweredOn = connection;
        loweredIn = mc.level;
        lowered = Math.max(1, (int) Math.round(hold.get() * 20));
        resends++;
    }

    /** Tells the server your real view distance again, if it was told otherwise (a server you've left needs nothing). */
    private void restore() {
        ClientPacketListener told = loweredOn;
        lowered = 0;
        loweredOn = null;
        loweredIn = null;
        if (told != null && mc.player != null && mc.player.connection == told) mc.options.broadcastOptions();
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        int rgb = 0xFF000000 | color.get();
        Long2ObjectOpenHashMap<Hit> here = hits.get(where());
        if (here != null) {
            for (Hit h : here.values()) {
                Vec3 c = h.center();
                if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
                render.box(h.bounds.inflate(0.5), Theme.alpha(rgb, 28), Theme.alpha(rgb, 210), 1.8f, true);
                if (markSpots.get()) {
                    for (BlockPos spot : h.spots) render.box(new AABB(spot).inflate(0.02), Theme.alpha(rgb, 50), Theme.alpha(rgb, 230), 1.2f, false);
                }
            }
        }
        // The area someone's in: outlined at your height, a beam up its middle.
        double y = mc.player.getY();
        for (Simulation.Area a : areas) {
            Vec3 c = new Vec3(a.cx() * 16 + 8, 0, a.cz() * 16 + 8);
            if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
            if (a.spread() > 1) {
                render.box(new AABB(a.minX() * 16, y - 0.5, a.minZ() * 16, (a.maxX() + 1) * 16, y - 0.1, (a.maxZ() + 1) * 16),
                    0, Theme.alpha(rgb, 160), 1.6f, true);
            }
            for (int i = 0; i < 16; i++) {
                float fade = 1 - i / 16f;
                Vec3 from = new Vec3(c.x, y - 40 + i * 6, c.z);
                render.line(from, from.add(0, 6, 0), Theme.alpha(rgb, 235 * fade * fade), 2.6f, true);
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
        double y = mc.player.getY();
        Object focused = null;
        double focusedOff = focus;
        List<Object[]> shown = new ArrayList<>();
        List<Object> all = new ArrayList<>(areas);
        Long2ObjectOpenHashMap<Hit> here = hits.get(where());
        if (here != null) all.addAll(here.values());
        for (Object o : all) {
            Vec3 c = o instanceof Hit h ? new Vec3(h.center().x, h.bounds.maxY + 0.6, h.center().z)
                : new Vec3(((Simulation.Area) o).cx() * 16 + 8, y + 4, ((Simulation.Area) o).cz() * 16 + 8);
            double[] screen = Projection.toScreen(c);
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < focusedOff) {
                focused = o;
                focusedOff = off;
            }
            shown.add(new Object[] {o, screen, c});
        }
        int rgb = 0xFF000000 | color.get();
        for (Object[] o : shown) {
            if (o[0] instanceof Hit h) {
                // In an area its one label says it all; a chunk's own shows when you look at it.
                if (o[0] != focused && insideArea(h)) continue;
                Badge.draw(g, "Changed chunk", (Vec3) o[2], h.blocks + (h.blocks == 1 ? " block" : " blocks") + " changed since it was last sent: "
                    + h.what + ", " + h.where(), rgb, (double[]) o[1], o[0] == focused);
            } else {
                Simulation.Area a = (Simulation.Area) o[0];
                String why = a.heat() + (a.heat() == 1 ? " changed chunk" : " changed chunks") + " within " + areasFor
                    + (areasFor == 1 ? " chunk" : " chunks") + (a.spread() > 1 ? "; somewhere in the " + (a.maxX() - a.minX() + 1) + " by "
                    + (a.maxZ() - a.minZ() + 1) + " outlined" : "");
                Badge.draw(g, "Activity about here", (Vec3) o[2], why, rgb, (double[]) o[1], o[0] == focused);
            }
        }
    }

    private boolean insideArea(Hit h) {
        for (Simulation.Area a : areas) {
            if (h.cx >= a.minX() && h.cx <= a.maxX() && h.cz >= a.minZ() && h.cz <= a.maxZ()) return true;
        }
        return false;
    }

    /** The server and the dimension: marks from one world don't show in another. */
    private String where() {
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        // By its save folder: two worlds can share a name, never a folder.
        else if (mc.getSingleplayerServer() != null) {
            server = "singleplayer:" + mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        }
        else server = "unknown";
        return server + "|" + (mc.level == null ? "" : mc.level.dimension().identifier().toString());
    }
}

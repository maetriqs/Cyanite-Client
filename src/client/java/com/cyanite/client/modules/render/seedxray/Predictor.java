package com.cyanite.client.modules.render.seedxray;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.LevelStem;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Works out, on its own thread, which blocks a seed puts in the chunks around you. The world is cut
 * into tiles of 4 x 4 chunks; a tile is generated as a whole (terrain two chunks past its edge,
 * decoration one chunk past, since features spill over), so what it predicts doesn't depend on the
 * way you came. Nearest tiles first. Terrain is cached, so neighbouring tiles share it.
 */
public final class Predictor {
    public static final int TILE = 4;
    /** Terrain kept around for the next tiles, about 75 KB a chunk. */
    private static final int TERRAIN_CACHE = 320;
    /** Results are dropped this many chunks past the range. */
    private static final int KEEP_MARGIN = 6;
    private static final long IDLE = TimeUnit.MILLISECONDS.toNanos(250);

    /** What the game wants predicted. Swapped whole, so the thread always sees one consistent request. */
    public record Request(long seed, ResourceKey<LevelStem> dimension, Set<Block> blocks, int centerX, int centerZ, int radius) {
        boolean sameWorld(@Nullable Request other) {
            return other != null && other.seed == seed && other.dimension.equals(dimension);
        }

        boolean sameTarget(@Nullable Request other) {
            return sameWorld(other) && other.blocks.equals(blocks);
        }
    }

    /** One chunk's predicted blocks: packed positions (BlockPos.asLong) and what's at each. */
    public record Found(long[] positions, Block[] blocks) {
    }

    private final Map<Long, Found> found = new ConcurrentHashMap<>();
    private volatile @Nullable Request request;
    /** The thread doing the work; an old one that's been replaced finishes up and stops. */
    private volatile @Nullable Thread thread;
    private volatile @Nullable Throwable failure;
    private volatile String status = "";
    private volatile int pendingTiles;
    private volatile long tileMillis;
    private volatile int failedFeatures;
    private volatile String failures = "";

    // ---- Game thread

    /** Starts the thread (or keeps it going) with this request; null stops it and forgets everything. */
    public synchronized void want(@Nullable Request next) {
        request = next;
        Thread t = thread;
        if (next == null) {
            thread = null;
            found.clear();
            status = "";
            if (t != null) LockSupport.unpark(t);
            return;
        }
        if (t == null) {
            t = new Thread(this::run, "Cyanite seed xray");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            thread = t;
            t.start();
        } else {
            LockSupport.unpark(t);
        }
    }

    /** A chunk's predicted blocks, or null until it's done. */
    public @Nullable Found found(int chunkX, int chunkZ) {
        return found.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** What went wrong on the thread, once (so the game thread can throw it where the module sees it). */
    public @Nullable Throwable takeFailure() {
        Throwable t = failure;
        failure = null;
        return t;
    }

    /** "loading", "generating", or empty when there's nothing left to do. */
    public String status() {
        return status;
    }

    /** Tiles in range not done yet. */
    public int pendingTiles() {
        return pendingTiles;
    }

    /** How long the last tile took. */
    public long tileMillis() {
        return tileMillis;
    }

    /** Features that couldn't be placed without a server, so far in this world. */
    public int failedFeatures() {
        return failedFeatures;
    }

    /** Which ones, how often and why (see SeedWorld.failures). */
    public String failures() {
        return failures;
    }

    // ---- The prediction thread

    private boolean current() {
        return thread == Thread.currentThread();
    }

    private void run() {
        Map<Long, LevelChunkSection[]> terrain = new LinkedHashMap<>(64, 0.75f, true);
        SeedWorld world = null;
        Request made = null;
        try {
            while (current()) {
                Request now = request;
                if (now == null) break;
                if (!now.sameWorld(made)) {
                    terrain.clear();
                    found.clear();
                    status = "loading";
                    world = SeedWorld.create(now.seed(), now.dimension());
                    failedFeatures = 0;
                    failures = "";
                } else if (!now.sameTarget(made)) {
                    found.clear();
                }
                made = now;
                if (world == null) {
                    status = "no vanilla generator here";
                    LockSupport.parkNanos(IDLE);
                    continue;
                }

                forgetFar(now);
                int[] tile = nextTile(now);
                if (tile == null) {
                    status = "";
                    LockSupport.parkNanos(IDLE);
                    continue;
                }
                status = "generating";
                long start = System.nanoTime();
                generateTile(world, terrain, now, tile[0], tile[1]);
                tileMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                failedFeatures = world.failedFeatures();
                failures = world.failures();
            }
        } catch (Throwable t) {
            if (current()) failure = t;
        }
    }

    /** Drops whole tiles that are well out of range, so a tile is never half there. */
    private void forgetFar(Request r) {
        int keep = r.radius() + KEEP_MARGIN;
        found.keySet().removeIf(k -> tileDistance(Math.floorDiv(ChunkPos.getX(k), TILE), Math.floorDiv(ChunkPos.getZ(k), TILE), r) > keep);
    }

    /** Chunks from the player's chunk to the nearest chunk of a tile. */
    private static int tileDistance(int tileX, int tileZ, Request r) {
        int dx = Math.max(0, Math.max(tileX * TILE - r.centerX(), r.centerX() - (tileX * TILE + TILE - 1)));
        int dz = Math.max(0, Math.max(tileZ * TILE - r.centerZ(), r.centerZ() - (tileZ * TILE + TILE - 1)));
        return Math.max(dx, dz);
    }

    /** The nearest tile with a chunk in range that isn't done, as {tileX, tileZ}; null when they all are. */
    private int @Nullable [] nextTile(Request r) {
        int minTx = Math.floorDiv(r.centerX() - r.radius(), TILE);
        int maxTx = Math.floorDiv(r.centerX() + r.radius(), TILE);
        int minTz = Math.floorDiv(r.centerZ() - r.radius(), TILE);
        int maxTz = Math.floorDiv(r.centerZ() + r.radius(), TILE);
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        int pending = 0;
        for (int tx = minTx; tx <= maxTx; tx++) {
            for (int tz = minTz; tz <= maxTz; tz++) {
                if (found.containsKey(ChunkPos.asLong(tx * TILE, tz * TILE))) continue;
                pending++;
                double dx = tx * TILE + (TILE - 1) / 2.0 - r.centerX();
                double dz = tz * TILE + (TILE - 1) / 2.0 - r.centerZ();
                double d = dx * dx + dz * dz;
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new int[]{tx, tz};
                }
            }
        }
        pendingTiles = pending;
        return best;
    }

    private void generateTile(SeedWorld world, Map<Long, LevelChunkSection[]> terrain, Request r, int tileX, int tileZ) {
        int x0 = tileX * TILE;
        int z0 = tileZ * TILE;
        GenLevel level = new GenLevel(world);
        for (int x = x0 - 2; x < x0 + TILE + 2; x++) {
            for (int z = z0 - 2; z < z0 + TILE + 2; z++) {
                // Another seed, world or block list since this began: drop it. Moving about doesn't matter.
                if (!current() || !r.sameTarget(request)) return;
                level.put(world.chunk(x, z, terrain(world, terrain, x, z)));
            }
        }
        for (int x = x0 - 1; x < x0 + TILE + 1; x++) {
            for (int z = z0 - 1; z < z0 + TILE + 1; z++) world.decorate(level, Objects.requireNonNull(level.chunk(x, z)));
        }
        if (!current() || !r.sameTarget(request)) return;
        // The tile's first chunk goes in last: nextTile() takes it as the sign the whole tile is done.
        for (int i = TILE * TILE - 1; i >= 0; i--) {
            int x = x0 + i % TILE;
            int z = z0 + i / TILE;
            found.put(ChunkPos.asLong(x, z), collect(Objects.requireNonNull(level.chunk(x, z)), r.blocks()));
        }
    }

    private static LevelChunkSection[] terrain(SeedWorld world, Map<Long, LevelChunkSection[]> cache, int x, int z) {
        long key = ChunkPos.asLong(x, z);
        LevelChunkSection[] sections = cache.get(key);
        if (sections == null) {
            sections = world.terrain(x, z);
            cache.put(key, sections);
            if (cache.size() > TERRAIN_CACHE) {
                Iterator<LevelChunkSection[]> oldest = cache.values().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        return sections;
    }

    private static Found collect(ProtoChunk chunk, Set<Block> blocks) {
        List<Long> positions = new ArrayList<>();
        List<Block> kinds = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            // The palette check skips most sections without touching a block.
            if (section.hasOnlyAir() || !section.maybeHas(state -> blocks.contains(state.getBlock()))) continue;
            int baseY = chunk.getMinY() + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!blocks.contains(state.getBlock())) continue;
                        positions.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                        kinds.add(state.getBlock());
                    }
                }
            }
        }
        long[] packed = new long[positions.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = positions.get(i);
        return new Found(packed, kinds.toArray(new Block[0]));
    }
}

package com.cyanite.client.system;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * One scan of the loaded chunks that every module shares, on its own thread so the game never
 * waits on it. A chunk is looked at once when it arrives, again when blocks in it change (a couple
 * of seconds after, so a busy build doesn't keep it busy), and again after a minute for what only
 * light or growth shows. Nearest chunks first.
 *
 * <p>A module adds a {@link Pass}: what to work out from a chunk, on the scanner's thread, and
 * what to do with it, back on the game's thread at the next tick. A pass only reads.
 */
public final class ChunkScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");
    private static final long CHANGED_DELAY = TimeUnit.SECONDS.toNanos(2);
    /** A chunk's light comes in a moment after its blocks: the first look waits for it. */
    private static final long LOAD_DELAY = TimeUnit.MILLISECONDS.toNanos(1500);
    private static final long RESCAN = TimeUnit.SECONDS.toNanos(60);
    private static final long RETRY = TimeUnit.SECONDS.toNanos(1);

    /** What a module works out from each chunk. */
    public interface Pass<R> {
        /** Whether it wants chunks at all right now (its module's on). Asked on the scanner's thread. */
        boolean active();

        /** Whether it wants this one (in range, say). Asked on the scanner's thread. */
        boolean wants(int chunkX, int chunkZ);

        /** Works it out. On the scanner's thread: read only, and nothing that touches the screen or chat. */
        R scan(LevelChunk chunk);

        /** Takes the result, on the game's thread. */
        void accept(long chunkKey, R result);
    }

    private static final List<Pass<?>> PASSES = new CopyOnWriteArrayList<>();
    /** Chunks waiting for a look, by when they're due (System.nanoTime). */
    private static final Map<Long, Long> due = new ConcurrentHashMap<>();
    /** Loaded chunks, by when they were last looked at. */
    private static final Map<Long, Long> loaded = new ConcurrentHashMap<>();
    private static final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private static volatile ClientLevel level;
    private static volatile int centerX;
    private static volatile int centerZ;
    private static Thread worker;
    private static long lastSweep;

    private ChunkScanner() {
    }

    public static void register(Pass<?> pass) {
        PASSES.add(pass);
        start();
    }

    /** Everything loaded gets looked at again: a module was just switched on, or its settings changed. */
    public static void rescanAll() {
        long now = System.nanoTime();
        for (Long key : loaded.keySet()) due.put(key, now);
        wake();
    }

    // ---- Told by the game (game thread)

    public static void chunkLoaded(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        loaded.put(key, 0L);
        due.put(key, System.nanoTime() + LOAD_DELAY);
        wake();
    }

    public static void chunkUnloaded(ChunkPos pos) {
        loaded.remove(pos.toLong());
        due.remove(pos.toLong());
    }

    /** A pass wants this chunk looked at (it's come into range, say), if it's loaded and not already waiting. */
    public static void request(long key) {
        if (!loaded.containsKey(key)) return;
        if (due.putIfAbsent(key, System.nanoTime()) == null) wake();
    }

    /** A block changed: its chunk gets another look shortly. */
    public static void blockChanged(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        if (!loaded.containsKey(key)) return;
        long when = System.nanoTime() + CHANGED_DELAY;
        due.merge(key, when, Math::min);
    }

    /** Once a tick: hands back results, follows the player, and queues the minute-old chunks. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            due.clear();
            loaded.clear();
            results.clear();
        }
        if (mc.player != null) {
            centerX = mc.player.chunkPosition().x;
            centerZ = mc.player.chunkPosition().z;
        }
        for (Runnable r; (r = results.poll()) != null; ) r.run();
        long now = System.nanoTime();
        if (now - lastSweep > TimeUnit.SECONDS.toNanos(1)) {
            lastSweep = now;
            for (Map.Entry<Long, Long> e : loaded.entrySet()) {
                if (e.getValue() != 0 && now - e.getValue() > RESCAN) due.putIfAbsent(e.getKey(), now);
            }
        }
    }

    // ---- The scanner's thread

    private static synchronized void start() {
        if (worker != null) return;
        worker = new Thread(ChunkScanner::run, "Cyanite chunk scanner");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private static void wake() {
        Thread t = worker;
        if (t != null) LockSupport.unpark(t);
    }

    private static void run() {
        while (true) {
            long key = next();
            if (key == Long.MIN_VALUE) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                continue;
            }
            ClientLevel in = level;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            LevelChunk chunk = in == null ? null : in.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            loaded.computeIfPresent(key, (k, v) -> System.nanoTime());
            for (Pass<?> pass : PASSES) look(pass, chunk, key, in);
        }
    }

    private static <R> void look(Pass<R> pass, LevelChunk chunk, long key, ClientLevel in) {
        if (!pass.active() || !pass.wants(ChunkPos.getX(key), ChunkPos.getZ(key))) return;
        try {
            R result = pass.scan(chunk);
            results.add(() -> {
                if (level == in) pass.accept(key, result);
            });
        } catch (RuntimeException e) {
            // The game changed the chunk under us mid-look: try again in a moment.
            LOGGER.debug("Chunk scan of {} failed, retrying", new ChunkPos(key), e);
            due.merge(key, System.nanoTime() + RETRY, Math::min);
        }
    }

    /** The nearest chunk that's due, taken off the list; Long.MIN_VALUE for none. */
    private static long next() {
        long now = System.nanoTime();
        long best = Long.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        int x = centerX;
        int z = centerZ;
        for (Map.Entry<Long, Long> e : due.entrySet()) {
            if (e.getValue() > now) continue;
            long k = e.getKey();
            int d = Math.max(Math.abs(ChunkPos.getX(k) - x), Math.abs(ChunkPos.getZ(k) - z));
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        if (best != Long.MIN_VALUE) due.remove(best);
        return best;
    }
}

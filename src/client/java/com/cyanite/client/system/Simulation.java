package com.cyanite.client.system;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's simulation distance, and what follows from it. A player keeps every chunk within
 * that many chunks of theirs ticking (a square round their chunk), and nothing past it. So
 * whatever ticked says someone was within that distance of it, and whatever stood still says
 * nobody was: where someone is comes from adding the one up and crossing the other off. The
 * method is the Anubis client's (GPL-3.0); this is Cyanite's own take on it.
 */
public final class Simulation {
    /** When the server hasn't said. */
    private static final int DEFAULT_DISTANCE = 10;

    private Simulation() {
    }

    /** How far out from a player chunks tick: {@code override} when it's set, or what the server says. */
    public static int distance(int override) {
        if (override > 0) return override;
        var level = Minecraft.getInstance().level;
        int server = level == null ? 0 : level.getServerSimulationDistance();
        return server > 0 ? server : DEFAULT_DISTANCE;
    }

    /** Chessboard distance between two chunks. */
    public static int apart(long a, long b) {
        return Math.max(Math.abs(ChunkPos.getX(a) - ChunkPos.getX(b)), Math.abs(ChunkPos.getZ(a) - ChunkPos.getZ(b)));
    }

    /** Adds {@code amount} to every chunk within {@code reach} of {@code chunk}. */
    public static void spread(Long2IntOpenHashMap heat, long chunk, int reach, int amount) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) heat.addTo(ChunkPos.asLong(cx + dx, cz + dz), amount);
        }
    }

    /** Every chunk within {@code reach} of {@code chunk}. */
    public static void spread(LongOpenHashSet into, long chunk, int reach) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) into.add(ChunkPos.asLong(cx + dx, cz + dz));
        }
    }

    /**
     * A guess: the chunk it points at, how hot it is, and the corners (in chunks) of the hottest
     * spots round it, which it could be any of.
     */
    public record Area(int cx, int cz, int heat, int minX, int minZ, int maxX, int maxZ) {
        /** How many chunks across the spots it could be are. */
        public int spread() {
            return Math.max(maxX - minX, maxZ - minZ) + 1;
        }
    }

    /**
     * Each patch of chunks at least {@code least} hot and not ruled out, joined side to side or
     * corner to corner: its hottest chunks, and the one of them nearest their middle (the middle
     * itself can be ruled out, round a corner of them).
     */
    public static List<Area> hottest(Long2IntOpenHashMap heat, LongOpenHashSet ruledOut, int least) {
        LongOpenHashSet open = new LongOpenHashSet();
        for (Long2IntOpenHashMap.Entry e : heat.long2IntEntrySet()) {
            if (e.getIntValue() >= least && !ruledOut.contains(e.getLongKey())) open.add(e.getLongKey());
        }
        List<Area> out = new ArrayList<>();
        LongOpenHashSet done = new LongOpenHashSet();
        for (long start : open) {
            if (!done.add(start)) continue;
            LongArrayList patch = new LongArrayList();
            LongArrayList queue = new LongArrayList();
            queue.add(start);
            int best = 0;
            while (!queue.isEmpty()) {
                long c = queue.removeLong(queue.size() - 1);
                patch.add(c);
                best = Math.max(best, heat.get(c));
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long n = ChunkPos.asLong(cx + dx, cz + dz);
                        if (open.contains(n) && done.add(n)) queue.add(n);
                    }
                }
            }
            LongArrayList peak = new LongArrayList();
            long sumX = 0, sumZ = 0;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < patch.size(); i++) {
                long c = patch.getLong(i);
                if (heat.get(c) != best) continue;
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                peak.add(c);
                sumX += cx;
                sumZ += cz;
                minX = Math.min(minX, cx);
                maxX = Math.max(maxX, cx);
                minZ = Math.min(minZ, cz);
                maxZ = Math.max(maxZ, cz);
            }
            double midX = (double) sumX / peak.size(), midZ = (double) sumZ / peak.size();
            int mx = 0, mz = 0;
            double nearest = Double.MAX_VALUE;
            for (int i = 0; i < peak.size(); i++) {
                long c = peak.getLong(i);
                double d = Math.hypot(ChunkPos.getX(c) - midX, ChunkPos.getZ(c) - midZ);
                if (d < nearest) {
                    nearest = d;
                    mx = ChunkPos.getX(c);
                    mz = ChunkPos.getZ(c);
                }
            }
            out.add(new Area(mx, mz, best, minX, minZ, maxX, maxZ));
        }
        return out;
    }
}

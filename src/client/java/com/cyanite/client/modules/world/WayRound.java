package com.cyanite.client.modules.world;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Auto Mine's way round lava, a hole or a block that won't break: the shortest run of straight
 * stretches, on the layer you walk on, from where you stand to somewhere past it. Every step gets
 * the checks the tunnel gets while it's dug, so a way found here is never turned back from: floor
 * under the middle, nothing in the way that won't break, and no lava or water within a block of
 * anything it opens up, nor just past the end of a stretch where the next one turns off. Turns cost extra, so
 * it keeps to a few long stretches, the way you'd dig it yourself. Only loaded chunks count.
 */
final class WayRound {
    /** One straight stretch of the way. */
    record Leg(Direction dir, int length) {
    }

    /** What a turn costs, in blocks dug: enough that it never zigzags for nothing. */
    private static final int TURN = 4;
    private static final int MOST_STEPS = 150_000;

    private final Level level;
    private final int y;
    private final int half;
    private final int top;
    private final int fluidBottom;
    private final int fluidTop;
    private final Set<BlockPos> avoid;
    // What each column holds, worked out once each: 1 yes, 2 no.
    private final Long2ByteOpenHashMap lava = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap hard = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap floor = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    /** How long the last way found is, in blocks. */
    int blocks;

    /**
     * @param y the layer you walk on
     * @param half how far the tunnel goes to each side of its centre line
     * @param top the highest row the tunnel digs, above the floor you walk on
     * @param fluidBottom the lowest row lava counts in (lava never flows up)
     * @param fluidTop the highest row lava counts in
     * @param avoid blocks that wouldn't break when tried
     */
    WayRound(Level level, int y, int half, int top, int fluidBottom, int fluidTop, Set<BlockPos> avoid) {
        this.level = level;
        this.y = y;
        this.half = half;
        this.top = top;
        this.fluidBottom = fluidBottom;
        this.fluidTop = fluidTop;
        this.avoid = avoid;
    }

    /**
     * @param from where you stand, on the tunnel's centre line
     * @param facing the way the stretch you're on goes
     * @param heading the way to get on along
     * @param past how far along {@code heading} from {@code from} the way has to end up
     * @param apart how far to one side of {@code from} it has to end up (going back, alongside the old tunnel)
     * @param run how many blocks on along {@code heading} from the end have to be clear as well
     * @param wide how far it may go to either side, and back
     * @return the stretches to dig, in order (none if you're there already), or null if there's no way within {@code wide}
     */
    List<Leg> find(BlockPos from, Direction facing, Direction heading, int past, int apart, int run, int wide) {
        Direction right = heading.getClockWise();
        int uMin = -wide;
        int uMax = Math.max(past, 0) + wide;
        int vs = 2 * wide + 1;
        int states = (uMax - uMin + 1) * vs * 4;
        int[] cost = new int[states];
        int[] parent = new int[states];
        boolean[] done = new boolean[states];
        Arrays.fill(cost, Integer.MAX_VALUE);

        int start = ((-uMin * vs + wide) << 2) | facing.get2DDataValue();
        cost[start] = 0;
        parent[start] = -1;
        // Cheapest first: what it cost to get there plus the least it can still cost.
        PriorityQueue<Long> queue = new PriorityQueue<>();
        queue.add(((long) (Math.max(0, past) + apart) << 32) | start);
        int steps = 0;
        while (!queue.isEmpty() && steps++ < MOST_STEPS) {
            int state = (int) (long) queue.poll();
            if (done[state]) continue;
            done[state] = true;
            int cell = state >> 2;
            int u = cell / vs + uMin;
            int v = cell % vs - wide;
            BlockPos at = from.relative(heading, u).relative(right, v);
            if (u >= past && Math.abs(v) >= apart && clearOn(at, heading, run)) return legs(state, parent);

            Direction was = Direction.from2DDataValue(state & 3);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int nu = u + along(dir, heading);
                int nv = v + along(dir, right);
                if (nu < uMin || nu > uMax || Math.abs(nv) > wide) continue;
                int next = (((nu - uMin) * vs + nv + wide) << 2) | dir.get2DDataValue();
                if (done[next]) continue;
                int c = cost[state] + 1 + (dir == was ? 0 : dir == was.getOpposite() ? 2 * TURN : TURN);
                if (c >= cost[next] || !open(at.relative(dir), dir)) continue;
                cost[next] = c;
                parent[next] = state;
                int least = Math.max(0, past - nu) + Math.max(0, apart - Math.abs(nv));
                queue.add(((long) (c + least) << 32) | next);
            }
        }
        return null;
    }

    private List<Leg> legs(int end, int[] parent) {
        List<Direction> moves = new ArrayList<>();
        for (int state = end; parent[state] >= 0; state = parent[state]) moves.add(Direction.from2DDataValue(state & 3));
        Collections.reverse(moves);
        blocks = moves.size();
        List<Leg> legs = new ArrayList<>();
        for (Direction move : moves) {
            if (!legs.isEmpty() && legs.getLast().dir() == move) legs.set(legs.size() - 1, new Leg(move, legs.getLast().length() + 1));
            else legs.add(new Leg(move, 1));
        }
        return legs;
    }

    private static int along(Direction dir, Direction axis) {
        return dir.getStepX() * axis.getStepX() + dir.getStepZ() * axis.getStepZ();
    }

    /** Whether the tunnel can be dug into {@code c} going {@code d}. */
    private boolean open(BlockPos c, Direction d) {
        return floorAt(c.getX(), c.getZ()) && diggable(c, d) && lavaFree(c, d);
    }

    /** Whether it can carry on along {@code d} from {@code c}, the way the lava check will see it once it's there. */
    private boolean clearOn(BlockPos c, Direction d, int run) {
        if (!lavaFree(c, d)) return false;
        for (int k = 1; k <= run; k++) {
            if (!open(c.relative(d, k), d)) return false;
        }
        return true;
    }

    /** Nothing that won't break across the tunnel at {@code c}. */
    private boolean diggable(BlockPos c, Direction d) {
        Direction side = d.getClockWise();
        for (int l = -half; l <= half; l++) {
            if (hardAt(c.getX() + side.getStepX() * l, c.getZ() + side.getStepZ() * l)) return false;
        }
        return true;
    }

    /**
     * No lava or water a block either side of the tunnel at {@code c}, nor in the next block or so on from it:
     * where a stretch ends, the drill still cuts that far into the corner.
     */
    private boolean lavaFree(BlockPos c, Direction d) {
        Direction side = d.getClockWise();
        int reach = half + 1;
        for (int k = 0; k <= reach; k++) {
            for (int l = -reach; l <= reach; l++) {
                if (lavaAt(c.getX() + d.getStepX() * k + side.getStepX() * l, c.getZ() + d.getStepZ() * k + side.getStepZ() * l)) return false;
            }
        }
        return true;
    }

    private boolean lavaAt(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        byte known = lava.get(key);
        if (known == 0) {
            // Not loaded: nothing's known about it, so it's no place to dig.
            boolean any = !loaded(x, z);
            for (int dy = fluidBottom; !any && dy <= fluidTop; dy++) {
                FluidState fluid = level.getFluidState(probe.set(x, y + dy, z));
                any = fluid.is(FluidTags.LAVA) || fluid.is(FluidTags.WATER);
            }
            known = any ? (byte) 1 : 2;
            lava.put(key, known);
        }
        return known == 1;
    }

    private boolean hardAt(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        byte known = hard.get(key);
        if (known == 0) {
            boolean any = !loaded(x, z);
            for (int dy = 0; !any && dy <= top; dy++) {
                probe.set(x, y + dy, z);
                any = avoid.contains(probe) || level.getBlockState(probe).getDestroySpeed(level, probe) < 0;
            }
            known = any ? (byte) 1 : 2;
            hard.put(key, known);
        }
        return known == 1;
    }

    private boolean floorAt(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        byte known = floor.get(key);
        if (known == 0) {
            boolean solid = false;
            if (loaded(x, z)) {
                BlockState state = level.getBlockState(probe.set(x, y - 1, z));
                solid = !state.isAir() && !state.getCollisionShape(level, probe).isEmpty();
            }
            known = solid ? (byte) 1 : 2;
            floor.put(key, known);
        }
        return known == 1;
    }

    private boolean loaded(int x, int z) {
        return level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }
}

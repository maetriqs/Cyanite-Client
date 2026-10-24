package com.cyanite.client.modules.world.area;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Walking distances from where you stand to every spot you could stand on nearby, the way a
 * player walks: flat steps, one-block jumps up, and drops of up to three blocks (which do no
 * fall damage). Spots next to lava, and dangerous blocks, are never used. Staying inside the dug
 * area is cheaper than wandering outside it, so routes keep out of caves when they can.
 */
public final class Paths {
    private static final int MAX_DROP = 3;

    private final Level level;
    private final Region bounds;
    private final AreaTarget target;
    private final Long2IntOpenHashMap dist = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    private final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
    public final BlockPos start;

    private Paths(Level level, Region bounds, AreaTarget target, BlockPos start) {
        this.level = level;
        this.bounds = bounds;
        this.target = target;
        this.start = start;
        dist.defaultReturnValue(-1);
    }

    public static Paths search(Level level, BlockPos feet, Region bounds, AreaTarget target, int budget) {
        BlockPos start = feet;
        if (!passable(level, start) && passable(level, start.above())) start = start.above();
        Paths paths = new Paths(level, bounds, target, start);
        paths.run(budget);
        return paths;
    }

    private void run(int budget) {
        long s = start.asLong();
        dist.put(s, 0);
        queue.add(new long[] {0, s});
        int expanded = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();

        while (!queue.isEmpty() && expanded < budget) {
            long[] head = queue.poll();
            int d = (int) head[0];
            long key = head[1];
            if (d > dist.get(key)) continue;
            expanded++;
            BlockPos p = BlockPos.of(key);

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                n.set(p.getX() + dir.getStepX(), p.getY(), p.getZ() + dir.getStepZ());
                if (!bounds.contains(n)) continue;
                if (standable(n)) {
                    relax(key, n.immutable(), d + step(n));
                } else if (passable(level, n) && passable(level, n.above())) {
                    // Walk off the edge and land within three blocks.
                    for (int k = 1; k <= MAX_DROP; k++) {
                        BlockPos m = n.below(k);
                        if (!passable(level, m)) break;
                        if (standable(m)) {
                            relax(key, m, d + step(m) + k);
                            break;
                        }
                    }
                } else if (passable(level, p.above(2)) && standable(n.above())) {
                    relax(key, n.above(), d + step(n.above()) + 2);
                }
            }
        }
    }

    private void relax(long from, BlockPos to, int d) {
        long key = to.asLong();
        int old = dist.get(key);
        if (old >= 0 && old <= d) return;
        dist.put(key, d);
        parent.put(key, from);
        queue.add(new long[] {d, key});
    }

    /** Inside the area costs 1 per block, outside 2, and wading through water more. */
    private int step(BlockPos pos) {
        int cost = target == null || target.contains(pos) || target.contains(pos.above()) ? 1 : 2;
        if (!level.getFluidState(pos).isEmpty()) cost += 2;
        return cost;
    }

    /** Somewhere a player can stand: room for feet and head, a solid floor, and nothing nasty around. */
    public boolean standable(BlockPos pos) {
        return standable(level, pos);
    }

    public static boolean standable(Level level, BlockPos pos) {
        if (!passable(level, pos) || !passable(level, pos.above())) return false;
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (floor.getCollisionShape(level, below).isEmpty()) return false;
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)) return false;
        return !nearLava(level, pos) && !nearLava(level, pos.above());
    }

    /** Nothing in the way of a body, and nothing that hurts or traps one. */
    public static boolean passable(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) return false;
        if (state.getFluidState().is(FluidTags.LAVA)) return false;
        return !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.COBWEB)
            && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.POWDER_SNOW) && !state.is(Blocks.WITHER_ROSE);
    }

    private static boolean nearLava(Level level, BlockPos pos) {
        if (lava(level, pos)) return true;
        for (Direction d : Direction.values()) {
            if (lava(level, pos.relative(d))) return true;
        }
        return false;
    }

    private static boolean lava(Level level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.LAVA);
    }

    /** Walking cost to stand at {@code pos}, or -1 if it can't be reached. */
    public int distance(BlockPos pos) {
        return dist.get(pos.asLong());
    }

    public int distance(int x, int y, int z) {
        return dist.get(BlockPos.asLong(x, y, z));
    }

    /** Every spot from here to {@code goal}, both ends included. */
    public List<BlockPos> pathTo(BlockPos goal) {
        long key = goal.asLong();
        if (dist.get(key) < 0) return null;
        List<BlockPos> out = new ArrayList<>();
        long s = start.asLong();
        out.add(goal);
        while (key != s) {
            key = parent.get(key);
            out.add(BlockPos.of(key));
            if (out.size() > 4096) return null;
        }
        Collections.reverse(out);
        return out;
    }

    public int reached() {
        return dist.size();
    }
}

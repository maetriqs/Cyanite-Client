package com.cyanite.client.modules.world.build;

import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.modules.world.area.Region;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Ways round a build for a player who puts blocks down as well as walking, cheapest first: flat
 * steps (corner to corner too, where nothing's in the way), one-block steps up, drops of up to
 * three, and the moves that make their own floor: a throwaway block put out beside the one you
 * stand on, to walk onto (bridging), or one a step lower to drop onto (stairs down a wall); one
 * put under you as you jump (pillaring); and one of yours mined out from under you, to come down
 * a block. So once it's up on what it's built it stays up
 * there, walking along the top, rather than climbing down and building a new pillar for each spot.
 *
 * <p>Costs are in tenths of a step. A block put down costs a good deal more than walking: a moment
 * to put in, then at the end, going back for it and taking it out (on a build that wants a lot of
 * them, that was longer than the building). So it goes the long way round on foot, or back over
 * blocks it's already put down, before it puts down new ones.
 */
public final class Routes {
    /** How a spot on a route is got to from the one before it. */
    public enum Move { START, WALK, BRIDGE, PILLAR, DIG }

    /** What the build allows. */
    public interface Rules {
        /** Whether you may stand with your feet here (and your head in the block above). */
        boolean mayStand(BlockPos feet);

        /** Whether a throwaway block may go in this cell. */
        boolean mayFill(BlockPos cell);

        /** Whether this is a throwaway block of yours that may be mined out from under you. */
        boolean mayDig(BlockPos cell);

        /** Whether a walk from one spot to the next may be tried (one that got stuck lately mayn't). */
        default boolean mayStep(BlockPos from, BlockPos to) {
            return true;
        }

        /** What a throwaway block in this cell costs over the usual: one that's better kept out of if there's another way. */
        default int fillCost(BlockPos cell) {
            return 0;
        }
    }

    public static final int STEP = 10;
    private static final int DIAGONAL = 14;
    private static final int UP = 20;
    private static final int WADE = 20;
    private static final int DROP = 5;
    /** What a throwaway block costs on top of putting it in: going back for it at the end and taking it out. */
    public static final int JUNK = 150;
    public static final int BRIDGE = 60 + JUNK;
    public static final int PILLAR = 45 + JUNK;
    public static final int DIG = 30;
    private static final int MAX_DROP = 3;
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final Move[] MOVES = Move.values();

    private final Level level;
    private final Region bounds;
    private final Region building;
    private final boolean bridges;
    private final boolean pillars;
    private final Rules rules;
    /** Blocks to plan as if they'd been taken out already: nothing to stand on there. */
    private final Set<BlockPos> gone;
    /** How far it drops off an edge, and whether it digs down through junk blocks. */
    private final int maxDrop;
    private final boolean digs;
    private final Long2IntOpenHashMap dist = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    private final Long2ByteOpenHashMap how = new Long2ByteOpenHashMap();
    private final LongArrayList order = new LongArrayList();
    private final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
    public final BlockPos start;

    private Routes(Level level, Region bounds, Region building, boolean bridges, boolean pillars, Rules rules, BlockPos start, Set<BlockPos> gone,
                   int maxDrop, boolean digs) {
        this.level = level;
        this.bounds = bounds;
        this.building = building;
        this.bridges = bridges;
        this.pillars = pillars;
        this.rules = rules;
        this.start = start;
        this.gone = gone;
        this.maxDrop = maxDrop;
        this.digs = digs;
        dist.defaultReturnValue(-1);
    }

    /**
     * Every way from {@code feet}, within {@code bounds}, up to {@code budget} spots. Blocks are
     * only put down inside {@code building}, and only for {@code bridges} and {@code pillars};
     * with it null, it only walks (and digs).
     */
    public static Routes search(Level level, BlockPos feet, Region bounds, Region building, boolean bridges, boolean pillars, Rules rules, int budget) {
        return search(level, feet, bounds, building, bridges, pillars, rules, budget, Set.of());
    }

    /** As {@link #search(Level, BlockPos, Region, Region, boolean, boolean, Rules, int)}, as if the blocks {@code gone} had been taken out. */
    public static Routes search(Level level, BlockPos feet, Region bounds, Region building, boolean bridges, boolean pillars, Rules rules, int budget,
                                Set<BlockPos> gone) {
        BlockPos start = feet;
        if (!Paths.passable(level, start) && Paths.passable(level, start.above())) start = start.above();
        Routes routes = new Routes(level, bounds, building, bridges, pillars, rules, start, gone, MAX_DROP, true);
        routes.run(budget);
        return routes;
    }

    /**
     * Every way from {@code feet} on foot that it could walk back the other way, as if the blocks
     * {@code gone} had been taken out: steps, one up and one down, but no drop further than that
     * and no digging down. Where this gets to, it can get back from (a stair down a wall it's
     * dropped two steps down with the step between taken out, it can't).
     */
    public static Routes searchTwoWay(Level level, BlockPos feet, Region bounds, Rules rules, int budget, Set<BlockPos> gone) {
        BlockPos start = feet;
        if (!Paths.passable(level, start) && Paths.passable(level, start.above())) start = start.above();
        Routes routes = new Routes(level, bounds, null, false, false, rules, start, gone, 1, false);
        routes.run(budget);
        return routes;
    }

    private void run(int budget) {
        long s = start.asLong();
        dist.put(s, 0);
        how.put(s, (byte) Move.START.ordinal());
        queue.add(new long[] {0, s});
        while (!queue.isEmpty() && order.size() < budget) {
            long[] head = queue.poll();
            int d = (int) head[0];
            long key = head[1];
            if (d > dist.get(key)) continue;
            order.add(key);
            BlockPos p = BlockPos.of(key);
            boolean build = building != null && building.contains(p);

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(dir);
                if (!bounds.contains(n)) continue;
                if (standable(n)) {
                    if (rules.mayStep(p, n)) relax(key, n, d + step(n), Move.WALK);
                } else if (body(n)) {
                    // Walk off the edge and land within three blocks...
                    for (int k = 1; k <= maxDrop; k++) {
                        BlockPos m = n.below(k);
                        if (!Paths.passable(level, m)) break;
                        if (standable(m)) {
                            if (rules.mayStep(p, m)) relax(key, m, d + step(m) + DROP * k, Move.WALK);
                            break;
                        }
                    }
                    // ...or put a block out to walk onto...
                    if (build && bridges && building.contains(n) && clear(n) && rules.mayFill(n.below())) {
                        relax(key, n, d + BRIDGE + rules.fillCost(n.below()), Move.BRIDGE);
                    }
                    // ...or one a step lower, to drop onto: stairs down the side of a wall, the way off
                    // the top of one (against the side of the block under the one you stand on), and on
                    // down beside it (against the wall), to get low enough to look up at something.
                    BlockPos low = n.below();
                    if (build && bridges && building.contains(low) && !standable(low) && clear(low) && rules.mayFill(low.below())
                        && placeable(low.below())) {
                        relax(key, low, d + BRIDGE + STEP + rules.fillCost(low.below()), Move.BRIDGE);
                    }
                } else if (Paths.passable(level, p.above(2)) && standable(n.above()) && rules.mayStep(p, n.above())) {
                    relax(key, n.above(), d + UP + step(n.above()) - STEP, Move.WALK);
                }
            }
            // Corner to corner, where neither side's in the way.
            for (int[] dd : DIAGONALS) {
                BlockPos n = p.offset(dd[0], 0, dd[1]);
                if (!bounds.contains(n) || !body(p.offset(dd[0], 0, 0)) || !body(p.offset(0, 0, dd[1])) || !standable(n) || !rules.mayStep(p, n)) continue;
                relax(key, n, d + DIAGONAL + step(n) - STEP, Move.WALK);
            }
            // Up a block, putting one under you as you jump.
            BlockPos up = p.above();
            if (build && pillars && building.contains(up) && Paths.passable(level, up.above()) && clear(up) && rules.mayFill(p)) {
                relax(key, up, d + PILLAR + rules.fillCost(p), Move.PILLAR);
            }
            // Down a block, mining out the throwaway one you stand on.
            BlockPos under = p.below();
            if (digs && !gone.contains(under) && rules.mayDig(under) && bounds.contains(under) && solidFloor(under.below()) && Paths.clearAir(level, p)
                && !lavaAt(under) && rules.mayStand(under)) {
                relax(key, under, d + DIG, Move.DIG);
            }
        }
    }

    private void relax(long from, BlockPos to, int d, Move move) {
        long key = to.asLong();
        int old = dist.get(key);
        if (old >= 0 && old <= d) return;
        dist.put(key, d);
        parent.put(key, from);
        how.put(key, (byte) move.ordinal());
        queue.add(new long[] {d, key});
    }

    private int step(BlockPos pos) {
        return level.getFluidState(pos).isEmpty() ? STEP : STEP + WADE;
    }

    /**
     * Somewhere to stand as it is: room, a floor, nothing nasty near, and allowed. The floor about
     * a block high, half a block at the least (a slab): on a repeater or a carpet your feet are
     * most of a block lower than the spot says, on a fence half a block higher, and what's
     * planned from there is planned from the wrong place.
     */
    private boolean standable(BlockPos pos) {
        if (gone.contains(pos.below()) || !Paths.standable(level, pos) || !rules.mayStand(pos)) return false;
        BlockPos below = pos.below();
        double top = level.getBlockState(below).getCollisionShape(level, below).max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    /** Room for a body: feet and head. */
    private boolean body(BlockPos pos) {
        return Paths.passable(level, pos) && Paths.passable(level, pos.above());
    }

    /** Room for a body that's not by lava, and allowed: where you'll stand once a block's put under you. */
    private boolean clear(BlockPos pos) {
        return Paths.clearAir(level, pos) && Paths.clearAir(level, pos.above()) && rules.mayStand(pos);
    }

    /** Whether a block can go in {@code cell}: something solid beside it or under it to put it against. */
    private boolean placeable(BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos next = cell.relative(d);
            if (d != Direction.UP && !level.getBlockState(next).getCollisionShape(level, next).isEmpty()) return true;
        }
        return false;
    }

    private boolean solidFloor(BlockPos pos) {
        BlockState floor = level.getBlockState(pos);
        return !floor.getCollisionShape(level, pos).isEmpty() && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CAMPFIRE)
            && !floor.is(Blocks.SOUL_CAMPFIRE);
    }

    private boolean lavaAt(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (level.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Cost to stand at {@code pos}, or -1 if there's no way there. */
    public int distance(BlockPos pos) {
        return dist.get(pos.asLong());
    }

    /** Every spot it found a way to, cheapest first, as packed positions. */
    public LongArrayList order() {
        return order;
    }

    /** How {@code pos} is got to on its route. */
    public Move move(BlockPos pos) {
        return MOVES[how.get(pos.asLong())];
    }

    /** Every spot from here to {@code goal}, both ends included; null if there's no way. */
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

    /** How each spot of a path is got to, START for the first. */
    public List<Move> moves(List<BlockPos> path) {
        List<Move> out = new ArrayList<>(path.size());
        for (BlockPos p : path) out.add(move(p));
        return out;
    }

    /** How many throwaway blocks a path puts down. */
    public static int blocks(List<Move> moves) {
        int n = 0;
        for (Move m : moves) if (m == Move.BRIDGE || m == Move.PILLAR) n++;
        return n;
    }
}

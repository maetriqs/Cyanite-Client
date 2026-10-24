package com.cyanite.client.modules.world.area;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * How an area gets dug with a drill that cuts a square: as layers the drill's height, taken top
 * to bottom, each cut into lanes the drill's width, run back and forth like mowing a lawn. Every
 * spot along a lane is one "cell": the square one hit straight down the lane clears.
 *
 * <p>Cells are numbered in the order they should be dug (their rank), starting from the corner
 * nearest you. The last lane and layer are pulled back inside the area rather than hanging out
 * of it, so no square ever needs to reach past the edge. Each cell keeps a count of the blocks
 * it still has, which is what progress is measured from.
 */
public final class AreaPlan {
    public final AreaTarget target;
    public final int size;
    public final int half;
    public final int cells;

    private final List<Lattice> lattices = new ArrayList<>();
    private final short[] cellLattice;
    private final int[] cellA;
    private final short[] cellLane;
    private final short[] cellLayer;
    /** Which way along the lane the route runs at this cell: +1 or -1. */
    private final byte[] cellDir;

    /** Blocks the cell still has; -1 while its chunk isn't loaded. */
    private final short[] count;
    /** Cells with something left, or not looked at yet. */
    private final BitSet pending = new BitSet();
    /** Cells left alone for good: lava, or nothing could safely reach them. */
    private final BitSet givenUp = new BitSet();
    private int sweepCursor;
    private boolean counted;
    private long left;
    private long mined;
    private long skipped;
    private long unknown;

    /**
     * One box of the area. {@code along} is the way lanes run, {@code across} the way they're
     * stacked side by side; the drill's square spans across and up.
     */
    public static final class Lattice {
        public final int index;
        public final Region region;
        public final Direction.Axis along;
        public final Direction.Axis across;
        public final int aMin;
        public final int aMax;
        final int[] lanes;
        final int[] laneLo;
        final int[] laneHi;
        final int[] bases;
        final int[] layerLo;
        final int[] layerHi;
        /** The rank of this box's first cell; its cells follow on from there, layer by layer. */
        int firstRank;

        Lattice(int index, Region region, int size) {
            this.index = index;
            this.region = region;
            int half = (size - 1) / 2;
            int sx = region.sizeX();
            int sz = region.sizeZ();
            // Lanes run along the longer side, unless the shorter one is too narrow to fit a lane.
            Direction.Axis longer = sx >= sz ? Direction.Axis.X : Direction.Axis.Z;
            Direction.Axis shorter = longer == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
            boolean fits = (shorter == Direction.Axis.X ? sx : sz) >= size;
            boolean longFits = (longer == Direction.Axis.X ? sx : sz) >= size;
            along = fits || !longFits ? longer : shorter;
            across = along == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;

            aMin = along == Direction.Axis.X ? region.minX() : region.minZ();
            aMax = along == Direction.Axis.X ? region.maxX() : region.maxZ();
            int lMin = across == Direction.Axis.X ? region.minX() : region.minZ();
            int lMax = across == Direction.Axis.X ? region.maxX() : region.maxZ();

            lanes = centers(lMin, lMax, size, half);
            laneLo = new int[lanes.length];
            laneHi = new int[lanes.length];
            for (int i = 0; i < lanes.length; i++) {
                laneLo[i] = i == 0 ? lMin : laneHi[i - 1] + 1;
                laneHi[i] = Math.min(lanes[i] + half, lMax);
            }

            bases = bases(region.minY(), region.maxY(), size);
            layerLo = new int[bases.length];
            layerHi = new int[bases.length];
            for (int j = 0; j < bases.length; j++) {
                layerHi[j] = j == 0 ? region.maxY() : layerLo[j - 1] - 1;
                layerLo[j] = Math.max(bases[j], region.minY());
            }
        }

        /** Lane centres from one side to the other, the last pulled back so it ends at the edge. */
        private static int[] centers(int lo, int hi, int size, int half) {
            if (hi - lo + 1 <= size) return new int[] {lo + half};
            List<Integer> out = new ArrayList<>();
            int c = lo + half;
            while (c + half <= hi) {
                out.add(c);
                c += size;
            }
            if (out.getLast() + half < hi) out.add(hi - half);
            return out.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Floors of each layer, top first, the last pulled up so it ends at the bottom. */
        private static int[] bases(int lo, int hi, int size) {
            // Too thin for a full layer: start at the bottom, where the open air above makes the
            // square's overhang harmless more often than solid ground below would.
            if (hi - lo + 1 <= size) return new int[] {lo};
            List<Integer> out = new ArrayList<>();
            int b = hi - size + 1;
            while (b >= lo) {
                out.add(b);
                b -= size;
            }
            if (out.getLast() > lo) out.add(lo);
            return out.stream().mapToInt(Integer::intValue).toArray();
        }

        public BlockPos pos(int a, int across, int y) {
            return along == Direction.Axis.X ? new BlockPos(a, y, across) : new BlockPos(across, y, a);
        }

        int layers() {
            return bases.length;
        }
    }

    public AreaPlan(AreaTarget target, int size, double px, double py, double pz) {
        this.target = target;
        this.size = size;
        this.half = (size - 1) / 2;

        // Nearest box first.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < target.regions.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> target.regions.get(i).distanceTo(px, py, pz)));

        int total = 0;
        for (int i : order) {
            Lattice l = new Lattice(i, target.regions.get(i), size);
            lattices.add(l);
            total += (l.aMax - l.aMin + 1) * l.lanes.length * l.layers();
        }
        cells = total;
        cellLattice = new short[cells];
        cellA = new int[cells];
        cellLane = new short[cells];
        cellLayer = new short[cells];
        cellDir = new byte[cells];
        count = new short[cells];

        int rank = 0;
        for (int li = 0; li < lattices.size(); li++) {
            Lattice l = lattices.get(li);
            l.firstRank = rank;
            Region r = l.region;
            double pa = l.along == Direction.Axis.X ? px : pz;
            double pl = l.across == Direction.Axis.X ? px : pz;
            int lMin = l.across == Direction.Axis.X ? r.minX() : r.minZ();
            int lMax = l.across == Direction.Axis.X ? r.maxX() : r.maxZ();
            // Start in the corner nearest you, so the first lane begins where you're standing.
            boolean lanesBackward = Math.abs(pl - lMax) < Math.abs(pl - lMin);
            int dir = Math.abs(pa - l.aMax) < Math.abs(pa - l.aMin) ? -1 : 1;
            for (int j = 0; j < l.layers(); j++) {
                // Each layer runs its lanes the opposite way, so it starts where the last one ended.
                boolean backward = lanesBackward ^ (j % 2 == 1);
                for (int n = 0; n < l.lanes.length; n++) {
                    int lane = backward ? l.lanes.length - 1 - n : n;
                    int a = dir > 0 ? l.aMin : l.aMax;
                    for (int k = 0; k <= l.aMax - l.aMin; k++, a += dir) {
                        cellLattice[rank] = (short) li;
                        cellA[rank] = a;
                        cellLane[rank] = (short) lane;
                        cellLayer[rank] = (short) j;
                        cellDir[rank] = (byte) dir;
                        count[rank] = -1;
                        rank++;
                    }
                    dir = -dir;
                }
            }
        }
        pending.set(0, cells);
        unknown = cells;
    }

    // ---- Cell geometry

    public Lattice lattice(int rank) {
        return lattices.get(cellLattice[rank]);
    }

    public int along(int rank) {
        return cellA[rank];
    }

    public int lane(int rank) {
        return lattice(rank).lanes[cellLane[rank]];
    }

    /** The floor of the cell's layer: the square it's dug with runs from here up. */
    public int base(int rank) {
        return lattice(rank).bases[cellLayer[rank]];
    }

    public int layer(int rank) {
        return cellLayer[rank];
    }

    public int layers(int rank) {
        return lattice(rank).layers();
    }

    /** Which way the route runs down the lane at this cell, along the lattice's {@code along} axis: +1 or -1. */
    public int travel(int rank) {
        return cellDir[rank];
    }

    /** Cells in one lane. */
    public int laneLength(int rank) {
        Lattice l = lattice(rank);
        return l.aMax - l.aMin + 1;
    }

    /** How many lanes each layer of this cell's box has. */
    public int lanes(int rank) {
        return lattice(rank).lanes.length;
    }

    /** The rank of the first cell in this cell's layer. */
    public int layerStart(int rank) {
        Lattice l = lattice(rank);
        return l.firstRank + cellLayer[rank] * l.lanes.length * laneLength(rank);
    }

    /** Which lane of its layer, in the order the route takes them (0 is the first one dug). */
    public int laneOrder(int rank) {
        return (rank - layerStart(rank)) / laneLength(rank);
    }

    /** The blocks this cell is responsible for: one slice across its lane, its layer high. */
    public List<BlockPos> owned(int rank) {
        Lattice l = lattice(rank);
        int lane = cellLane[rank];
        int layer = cellLayer[rank];
        List<BlockPos> out = new ArrayList<>(size * size);
        for (int y = l.layerLo[layer]; y <= l.layerHi[layer]; y++) {
            for (int c = l.laneLo[lane]; c <= l.laneHi[lane]; c++) {
                BlockPos pos = l.pos(cellA[rank], c, y);
                if (target.owns(l.index, pos.getX(), pos.getY(), pos.getZ())) out.add(pos);
            }
        }
        return out;
    }

    public AABB box(int rank) {
        Lattice l = lattice(rank);
        int lane = cellLane[rank];
        int layer = cellLayer[rank];
        BlockPos a = l.pos(cellA[rank], l.laneLo[lane], l.layerLo[layer]);
        BlockPos b = l.pos(cellA[rank], l.laneHi[lane], l.layerHi[layer]);
        return new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, b.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    // ---- What's left

    /** Something that's still there to dig: a real, breakable block with a body, not air or liquid. */
    public static boolean diggable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || level.isOutsideBuildHeight(pos.getY())) return false;
        if (state.getCollisionShape(level, pos).isEmpty()) return false;
        return state.getDestroySpeed(level, pos) >= 0;
    }

    /** Counts what the cell has left and files it as pending or done. @return the count, -1 if unloaded. */
    public int recount(Level level, int rank) {
        int n = 0;
        for (BlockPos pos : owned(rank)) {
            if (!level.hasChunkAt(pos)) {
                n = -1;
                break;
            }
            if (diggable(level, pos, level.getBlockState(pos))) n++;
        }
        set(rank, n);
        return n;
    }

    private void set(int rank, int n) {
        int old = count[rank];
        if (givenUp.get(rank)) {
            count[rank] = (short) n;
            return;
        }
        if (old >= 0) left -= old;
        else unknown--;
        if (n >= 0) left += n;
        else unknown++;
        if (old > 0 && n >= 0 && n < old) mined += old - n;
        count[rank] = (short) n;
        pending.set(rank, n != 0);
    }

    public int count(int rank) {
        return count[rank];
    }

    /** Leaves a cell alone from now on, counting what it had left as skipped. */
    public void giveUp(int rank) {
        if (givenUp.get(rank)) return;
        int n = count[rank];
        if (n > 0) {
            left -= n;
            skipped += n;
        } else if (n < 0) {
            unknown--;
        }
        givenUp.set(rank);
        pending.clear(rank);
    }

    public boolean isGivenUp(int rank) {
        return givenUp.get(rank);
    }

    public int nextPending(int from) {
        return from >= cells ? -1 : pending.nextSetBit(from);
    }

    public int nextGivenUp(int from) {
        return from >= cells ? -1 : givenUp.nextSetBit(from);
    }

    /** Recounts cells round-robin for a little while each tick, so progress stays current. */
    public void sweep(Level level, long budgetNanos) {
        long until = System.nanoTime() + budgetNanos;
        int done = 0;
        while (done < cells) {
            if (!givenUp.get(sweepCursor)) recount(level, sweepCursor);
            done++;
            if (++sweepCursor >= cells) {
                sweepCursor = 0;
                counted = true;
            }
            if ((done & 31) == 0 && System.nanoTime() > until) break;
        }
        if (done >= cells) counted = true;
    }

    /** Whether every cell has been counted at least once. */
    public boolean counted() {
        return counted;
    }

    public long left() {
        return left;
    }

    public long mined() {
        return mined;
    }

    public long skipped() {
        return skipped;
    }

    /** Cells still waiting for their chunk to load. */
    public long unknown() {
        return unknown;
    }

    public double progress() {
        long total = mined + left;
        return total <= 0 ? (counted ? 1 : 0) : (double) mined / total;
    }
}

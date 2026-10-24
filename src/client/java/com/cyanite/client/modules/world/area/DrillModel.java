package com.cyanite.client.modules.world.area;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * What the drill really breaks around the block you hit, learned by watching it. Drills aren't
 * all alike: one cuts a square flat across the face you hit, another cuts 2x3 when you mine
 * down. The server decides, so the only way to know is to look at what went after each hit.
 *
 * <p>Positions are kept relative to the hit and the way you were facing: {@code f} forward,
 * {@code r} to your right, {@code u} up. Hits on a side face are one kind, hits on top and on
 * the bottom of a block two more, since drills often treat those differently.
 *
 * <p>Two answers come out of it. What the drill <em>may</em> break decides whether a hit is
 * safe, so it starts wide (every shape a drill like this plausibly cuts) and a spot only drops
 * out after it has been seen staying put twice and never breaking. What the drill <em>will</em>
 * break is the best guess, for choosing hits and knowing when one is finished. It starts as
 * the Donut SMP drill (the Shard Pickaxe), as Drill Tester measured it: a 3x3 square standing
 * upright across the way you face, centred on the block you broke, whatever face you hit and
 * however far up or down you look. Into a wall head on that's the square flat on the face; into
 * a floor it's the block, the one under it and the one above (where you'd be standing, so it
 * usually looks like six). A drill that does something else is learned from its hits.
 */
public final class DrillModel {
    public enum Kind { SIDE, UP, DOWN }

    private final int half;
    private final int radius;
    private final int side;
    private final int[][] removed;
    private final int[][] kept;
    private final int[] samples = new int[3];

    public DrillModel(int size) {
        half = (size - 1) / 2;
        radius = half + 1;
        side = radius * 2 + 1;
        removed = new int[3][side * side * side];
        kept = new int[3][side * side * side];
    }

    public static Kind kind(Direction face) {
        return face == Direction.UP ? Kind.UP : face == Direction.DOWN ? Kind.DOWN : Kind.SIDE;
    }

    /** Which way "forward" is for a hit: into a side face, or the way you face for top and bottom. */
    public static Direction forward(Direction face, Direction facing) {
        return face.getAxis().isHorizontal() ? face.getOpposite() : facing;
    }

    public int radius() {
        return radius;
    }

    private int index(int f, int r, int u) {
        return ((f + radius) * side + (r + radius)) * side + (u + radius);
    }

    /** Flat across the way you face: the square for a side face, and one guess for top and bottom. */
    private boolean upright(int f, int r, int u) {
        return f == 0 && Math.abs(r) <= half && Math.abs(u) <= half;
    }

    /** Flat on the ground: the square a top or bottom hit cuts if it's cut across the face. */
    private boolean flat(int f, int r, int u) {
        return u == 0 && Math.abs(f) <= half && Math.abs(r) <= half;
    }

    private boolean priorMay(Kind kind, int f, int r, int u) {
        return kind == Kind.SIDE ? upright(f, r, u) : flat(f, r, u) || upright(f, r, u);
    }

    private boolean priorWill(Kind kind, int f, int r, int u) {
        return upright(f, r, u);
    }

    /** Whether the drill might break this spot. Wide until proven otherwise; see the class notes. */
    public boolean mayBreak(Kind kind, int f, int r, int u) {
        if (f == 0 && r == 0 && u == 0) return true;
        int i = index(f, r, u);
        if (removed[kind.ordinal()][i] > 0) return true;
        if (kept[kind.ordinal()][i] >= 2) return false;
        return priorMay(kind, f, r, u);
    }

    /** The best guess at whether the drill breaks this spot. */
    public boolean willBreak(Kind kind, int f, int r, int u) {
        if (f == 0 && r == 0 && u == 0) return true;
        int i = index(f, r, u);
        int a = removed[kind.ordinal()][i];
        int b = kept[kind.ordinal()][i];
        if (a != b) return a > b;
        return priorWill(kind, f, r, u);
    }

    /** {@code (f, r, u)} relative to the hit, in the world. */
    public static BlockPos at(BlockPos center, Direction forward, int f, int r, int u) {
        Direction right = forward.getClockWise();
        return center.offset(forward.getStepX() * f + right.getStepX() * r, u, forward.getStepZ() * f + right.getStepZ() * r);
    }

    /** Index into a snapshot of the cube around a hit, by world offset. */
    public int cell(int dx, int dy, int dz) {
        return ((dx + radius) * side + (dy + radius)) * side + (dz + radius);
    }

    public int cubeSize() {
        return side * side * side;
    }

    /**
     * Learns from one hit: {@code before} and {@code after} say, for the cube around the hit
     * (see {@link #cell}), where there was something to dig. Ignored unless the drill clearly
     * fired, so a lagging server or a plain pickaxe can't teach it the wrong shape.
     * @return how many blocks went, the hit block included.
     */
    public int observe(Kind kind, Direction forward, boolean[] before, boolean[] after) {
        int fired = 0;
        for (int i = 0; i < before.length; i++) {
            if (before[i] && !after[i]) fired++;
        }
        // The hit block itself always goes; anything more means the drill did its thing.
        if (fired < 2) return fired;
        samples[kind.ordinal()]++;
        Direction right = forward.getClockWise();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int c = cell(dx, dy, dz);
                    if (!before[c]) continue;
                    int f = dx * forward.getStepX() + dz * forward.getStepZ();
                    int r = dx * right.getStepX() + dz * right.getStepZ();
                    if (f == 0 && r == 0 && dy == 0) continue;
                    int i = index(f, r, dy);
                    if (after[c]) kept[kind.ordinal()][i]++;
                    else removed[kind.ordinal()][i]++;
                }
            }
        }
        return fired;
    }

    public int samples(Kind kind) {
        return samples[kind.ordinal()];
    }

    /** Short description of what it's learned, like "3×3" or "2×3", or null before it has seen a hit. */
    public String describe(Kind kind) {
        if (samples[kind.ordinal()] == 0) return null;
        int fMin = 0, fMax = 0, rMin = 0, rMax = 0, uMin = 0, uMax = 0;
        for (int f = -radius; f <= radius; f++) {
            for (int r = -radius; r <= radius; r++) {
                for (int u = -radius; u <= radius; u++) {
                    int i = index(f, r, u);
                    if (removed[kind.ordinal()][i] <= kept[kind.ordinal()][i]) continue;
                    fMin = Math.min(fMin, f);
                    fMax = Math.max(fMax, f);
                    rMin = Math.min(rMin, r);
                    rMax = Math.max(rMax, r);
                    uMin = Math.min(uMin, u);
                    uMax = Math.max(uMax, u);
                }
            }
        }
        int[] extents = {fMax - fMin + 1, rMax - rMin + 1, uMax - uMin + 1};
        java.util.Arrays.sort(extents);
        return extents[0] == 1 ? extents[1] + "×" + extents[2] : extents[0] + "×" + extents[1] + "×" + extents[2];
    }
}

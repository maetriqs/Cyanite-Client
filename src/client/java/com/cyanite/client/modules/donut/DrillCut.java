package com.cyanite.client.modules.donut;

import com.cyanite.client.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one drill hit took, worked out from the blocks around it before and after. Shared by
 * Drill Logger and Drill Tester, which also remember the name of every tool seen cutting more
 * than one block, so nothing else mistakes a drill for a plain pickaxe.
 */
public final class DrillCut {
    /** How far around the block it looks: a 7x7x7 cube. */
    public static final int R = 3;
    private static final int SIDE = 2 * R + 1;

    /** One block that went: world offset from the hit, and the same relative to you (forward, right, up). */
    public record Gone(int dx, int dy, int dz, int f, int r, int u) {
    }

    public final List<Gone> gone = new ArrayList<>();
    public final Direction forward;
    public final boolean[] before;
    public final boolean[] after;
    public int fMin, fMax, rMin, rMax, uMin, uMax;

    /**
     * {@code forward} is into the face for a wall, and the way you face for a floor or ceiling.
     * The block you hit always counts as gone.
     */
    public DrillCut(Direction forward, boolean[] before, boolean[] after) {
        this.forward = forward;
        this.before = before;
        this.after = after;
        Direction right = forward.getClockWise();
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    int i = index(dx, dy, dz);
                    boolean hitBlock = dx == 0 && dy == 0 && dz == 0;
                    if (!hitBlock && !(before[i] && !after[i])) continue;
                    int f = dx * forward.getStepX() + dz * forward.getStepZ();
                    int r = dx * right.getStepX() + dz * right.getStepZ();
                    gone.add(new Gone(dx, dy, dz, f, r, dy));
                    fMin = Math.min(fMin, f);
                    fMax = Math.max(fMax, f);
                    rMin = Math.min(rMin, r);
                    rMax = Math.max(rMax, r);
                    uMin = Math.min(uMin, dy);
                    uMax = Math.max(uMax, dy);
                }
            }
        }
    }

    public static Direction forward(Direction face, Direction facing) {
        return face.getAxis().isHorizontal() ? face.getOpposite() : facing;
    }

    /** Which spots in the cube around {@code center} have a block in them. */
    public static boolean[] snapshot(Level level, BlockPos center) {
        boolean[] out = new boolean[SIDE * SIDE * SIDE];
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    q.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(q);
                    out[index(dx, dy, dz)] = !state.isAir() && state.getFluidState().isEmpty();
                }
            }
        }
        return out;
    }

    public static int index(int dx, int dy, int dz) {
        return ((dx + R) * SIDE + (dy + R)) * SIDE + (dz + R);
    }

    public static int count(boolean[] cube) {
        int n = 0;
        for (boolean b : cube) if (b) n++;
        return n;
    }

    public int size() {
        return gone.size();
    }

    /** Like "3 across, 2 tall, 1 deep", across and deep being relative to you. */
    public String shape() {
        return (rMax - rMin + 1) + " across, " + (uMax - uMin + 1) + " tall, " + (fMax - fMin + 1) + " deep";
    }

    /** Which world axis the cut runs along across you: X, Z, or both/neither as "-". */
    public String acrossAxis() {
        int xs = 0, zs = 0;
        int xMin = 0, xMax = 0, zMin = 0, zMax = 0;
        for (Gone g : gone) {
            xMin = Math.min(xMin, g.dx);
            xMax = Math.max(xMax, g.dx);
            zMin = Math.min(zMin, g.dz);
            zMax = Math.max(zMax, g.dz);
        }
        xs = xMax - xMin + 1;
        zs = zMax - zMin + 1;
        if (xs > 1 && zs == 1) return "X";
        if (zs > 1 && xs == 1) return "Z";
        return xs > 1 ? "X and Z" : "-";
    }

    /**
     * Pictures of the cut, one per slice going away from you: rows top to bottom, columns left
     * to right as you see them. X is the block you hit, # broke, . stayed, blank was already empty.
     */
    public String picture(String indent) {
        StringBuilder sb = new StringBuilder();
        Direction right = forward.getClockWise();
        for (int f = fMin; f <= fMax; f++) {
            sb.append(indent).append("slice ").append(f == 0 ? "of the block you hit" : f > 0 ? f + " further away" : -f + " nearer").append(":\n");
            for (int u = Math.min(R, uMax + 1); u >= Math.max(-R, uMin - 1); u--) {
                sb.append(indent).append("  ");
                for (int r = Math.max(-R, rMin - 1); r <= Math.min(R, rMax + 1); r++) {
                    int dx = forward.getStepX() * f + right.getStepX() * r;
                    int dz = forward.getStepZ() * f + right.getStepZ() * r;
                    int i = index(dx, u, dz);
                    char c;
                    if (dx == 0 && u == 0 && dz == 0) c = 'X';
                    else if (before[i] && !after[i]) c = '#';
                    else if (after[i]) c = '.';
                    else c = ' ';
                    sb.append(c);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public String offsets() {
        StringBuilder sb = new StringBuilder();
        for (Gone g : gone) sb.append(String.format(Locale.ROOT, " [%d %d %d]", g.dx, g.dy, g.dz));
        return sb.toString().trim();
    }

    // ---- The log file, and drills seen so far

    public static Path logFile() {
        return Config.dir().resolve("drill-log.txt");
    }

    private static Path drillsFile() {
        return Config.dir().resolve("drills.txt");
    }

    public static void log(String text) throws IOException {
        Path file = logFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Set<String> drills;

    /** Names of the tools seen cutting more than one block, lower case. */
    public static synchronized Set<String> drills() {
        if (drills == null) {
            drills = new LinkedHashSet<>();
            try {
                if (Files.exists(drillsFile())) {
                    for (String line : Files.readAllLines(drillsFile(), StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) drills.add(line.strip().toLowerCase(Locale.ROOT));
                    }
                }
            } catch (IOException ignored) {
                // Nothing remembered yet; the name checks still work.
            }
        }
        return drills;
    }

    public static synchronized void rememberDrill(String name) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || !drills().add(key)) return;
        try {
            Files.createDirectories(drillsFile().getParent());
            Files.write(drillsFile(), drills, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Remembered for this session anyway.
        }
    }

    /** Whether a tool by this name is a drill: one seen cutting more than a block, or named like Donut's. */
    public static boolean isDrill(String name) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        return key.contains("drill") || key.contains("shard") || key.contains("amethyst") || drills().contains(key);
    }
}

package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes down exactly what your drill breaks, so its shape is known instead of guessed. Every
 * block you break while this is on gets an entry in {@code cyanite/drill-log.txt}: the face you
 * hit, which way you were looking, what you held, and a picture of which blocks around it went.
 * Turning it off adds a summary. For a guided run through every angle, see {@link DrillTester}.
 */
public class DrillLogger extends Module {
    /** The rest of a cut shows up a moment after the block you hit; this long with no change means it's all in. */
    static final long QUIET = 400;
    static final long LONGEST = 2500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final BoolSetting chat = add(new BoolSetting("Chat each hit", true));

    /** What the crosshair is on, and everything around it, as of one tick. */
    private record Look(BlockPos pos, Direction face, Vec3 hitAt, float yaw, float pitch, Direction facing, String item, boolean[] solid) {
    }

    private Look last;
    private Look watching;
    private long brokeAt;
    private long lastChange;
    private int lastCount;
    private int logged;
    private final Map<String, Map<String, Integer>> tally = new TreeMap<>();

    public DrillLogger() {
        super(Category.DONUT, "Drill Logger", "Logs exactly which blocks your drill breaks, to work out its shape");
    }

    @Override
    public String getInfo() {
        return logged == 0 ? null : Integer.toString(logged);
    }

    public static Path file() {
        return DrillCut.logFile();
    }

    @Override
    protected void onEnable() {
        last = null;
        watching = null;
        logged = 0;
        tally.clear();
        if (!inGame()) return;
        String where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        write("\n==== Drill Logger started " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " on " + where + " ====\n");
        Notify.send("Drill Logger", "Break blocks one at a time: into walls facing each way, straight down, straight up", false, false, false);
    }

    @Override
    protected void onDisable() {
        if (watching != null && inGame()) finish(watching, DrillCut.snapshot(mc.level, watching.pos));
        watching = null;
        last = null;
        if (logged == 0) return;
        StringBuilder sb = new StringBuilder("---- Summary: " + logged + " hits ----\n");
        tally.forEach((kind, shapes) -> {
            sb.append(kind).append(":\n");
            shapes.forEach((shape, n) -> sb.append("    ").append(shape).append("  x").append(n).append('\n'));
        });
        write(sb.toString());
        if (inGame()) Notify.send("Drill Logger", logged + " hits saved to cyanite/drill-log.txt", false, false, false);
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        long now = System.currentTimeMillis();

        if (watching != null) {
            boolean[] after = DrillCut.snapshot(mc.level, watching.pos);
            int count = DrillCut.count(after);
            if (count != lastCount) {
                lastCount = count;
                lastChange = now;
            }
            if (now - lastChange > QUIET || now - brokeAt > LONGEST) {
                finish(watching, after);
                watching = null;
            }
        }
        // The block under the crosshair last tick is gone: that's a hit. Its cube from last tick
        // is the "before"; the rest of the cut gets a moment to arrive.
        if (watching == null && last != null && mc.level.getBlockState(last.pos).isAir()) {
            watching = last;
            brokeAt = now;
            lastChange = now;
            lastCount = DrillCut.count(DrillCut.snapshot(mc.level, last.pos));
        }

        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos().immutable();
            last = new Look(pos, hit.getDirection(), hit.getLocation(), player.getYRot(), player.getXRot(), player.getDirection(),
                player.getMainHandItem().getHoverName().getString(), DrillCut.snapshot(mc.level, pos));
        } else {
            last = null;
        }
    }

    private void finish(Look hit, boolean[] after) {
        DrillCut cut = new DrillCut(DrillCut.forward(hit.face, hit.facing), hit.solid, after);
        if (cut.size() > 1) DrillCut.rememberDrill(hit.item);
        logged++;
        String kind = describe(hit.face);
        String summary = cut.size() + " blocks: " + cut.shape();
        tally.computeIfAbsent(kind, k -> new TreeMap<>()).merge(summary + " (facing " + hit.facing.getName() + ")", 1, Integer::sum);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "#%d %s  holding \"%s\"%n", logged, LocalDateTime.now().format(TIME), hit.item));
        sb.append(String.format(Locale.ROOT, "  %s at %d %d %d, facing %s (yaw %.1f, pitch %.1f), crosshair at %.2f %.2f %.2f%n",
            kind, hit.pos.getX(), hit.pos.getY(), hit.pos.getZ(), hit.facing.getName(), hit.yaw, hit.pitch,
            hit.hitAt.x - hit.pos.getX(), hit.hitAt.y - hit.pos.getY(), hit.hitAt.z - hit.pos.getZ()));
        sb.append("  broke ").append(summary).append('\n');
        sb.append(cut.picture("  "));
        sb.append("  world offsets (dx dy dz): ").append(cut.offsets()).append('\n');
        write(sb.toString());
        if (chat.get()) {
            Notify.send("Drill Logger", "#" + logged + " " + kind.toLowerCase(Locale.ROOT) + ", facing " + hit.facing.getName() + ": " + summary,
                false, false, false);
        }
    }

    static String describe(Direction face) {
        return switch (face) {
            case UP -> "Mining down (top face)";
            case DOWN -> "Mining up (bottom face)";
            default -> "Into a wall (" + face.getName() + " face)";
        };
    }

    private static void write(String text) {
        try {
            DrillCut.log(text);
        } catch (IOException e) {
            Notify.send("Drill Logger", "Couldn't write the log: " + e.getMessage(), false, false, false);
        }
    }
}

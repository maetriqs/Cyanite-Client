package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.modules.render.EspStyle;
import com.cyanite.client.render.Badge;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.BlockEntityLeaks;
import com.cyanite.client.system.BlockEntityLeaks.Found;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Spawners, chests and the rest that the server names but doesn't show. A chunk comes with a list of
 * its block entities, and a server hiding what's underground has to strip that list as well as fake the
 * blocks; where it doesn't, the spawner under the made-up deepslate is still in the list, with its mob.
 * Block entity updates and the events a block sends out (a chest opening, a spawner spawning, a
 * dispenser firing) give them away the same way. {@link BlockEntityLeaks} keeps what the packets name
 * before the client drops it; this boxes the ones the client doesn't have, labels them with what they
 * are, the mob and the depth, and tells you about spawners and storage.
 *
 * <p>Keeping them shown takes nothing but remembering them: they're drawn from that memory, not from
 * the world, so the client's world stays exactly what the server said. Anubis also cancels the block
 * changes that turn those spots back into air or rock; that isn't needed here, and it would leave blocks
 * the server doesn't know about to walk into and mine at.
 *
 * <p>On Donut: a 75 s look at its packets from the surface (2026-10-24) found no block entities sent
 * below Y 0, so expect leaks from what's going on down there and near where you are below, not from
 * every chunk.
 *
 * <p>The method is the Anubis client's (GPL-3.0, its "Block Entity Debug"); the code is Cyanite's own.
 */
public class LeakFinder extends Module {
    private static final int REBUILD = 10;
    private static final int MAX_LABELS = 40;
    /** Chat lines one batch of finds gets, past the popup. */
    private static final int MAX_LINES = 5;

    private final BoolSetting spawners = add(new BoolSetting("Spawners", true));
    private final BoolSetting chests = add(new BoolSetting("Chests", true));
    private final BoolSetting barrels = add(new BoolSetting("Barrels", true));
    private final BoolSetting shulkers = add(new BoolSetting("Shulkers", true));
    private final BoolSetting enderChests = add(new BoolSetting("Ender chests", true));
    private final BoolSetting storage = add(new BoolSetting("Other storage", true));
    private final BoolSetting other = add(new BoolSetting("Other blocks", true));
    private final BoolSetting natural = add(new BoolSetting("Natural blocks", false));
    private final ModeSetting depth = add(new ModeSetting("Depth", "Below Y 0", List.of("All", "Below Y 0", "Below -20", "Below -30")));
    private final BoolSetting normal = add(new BoolSetting("Show normal", false));
    private final SliderSetting range = add(new SliderSetting("Range", 512, 32, 1024, 16).format(v -> (int) v + "m"));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final EspStyle style = new EspStyle(true);

    /** What a block entity is, for its colour and which toggle it's under. */
    private enum Kind {
        SPAWNER(0xFF5050, true),
        CHEST(0xF5C542, true),
        BARREL(0xA8743F, true),
        SHULKER(0xFF6BD3, true),
        ENDER(0xA35CFF, true),
        STORAGE(0x9AA3AF, true),
        OTHER(0x5ED0E6, false),
        /** Trial chambers, the deep dark, ruins, portals: worldgen's own. */
        NATURAL(0x8FD18F, false);

        final int rgb;
        /** Worth a notification. */
        final boolean alerts;

        Kind(int rgb, boolean alerts) {
            this.rgb = rgb;
            this.alerts = alerts;
        }

        static Kind of(String id) {
            return switch (id) {
                case "mob_spawner" -> SPAWNER;
                case "chest", "trapped_chest" -> CHEST;
                case "barrel" -> BARREL;
                case "shulker_box" -> SHULKER;
                case "ender_chest" -> ENDER;
                case "hopper", "dropper", "dispenser", "furnace", "blast_furnace", "smoker", "crafter", "brewing_stand",
                     "chiseled_bookshelf", "shelf" -> STORAGE;
                case "trial_spawner", "vault", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "sculk_catalyst",
                     "brushable_block", "decorated_pot", "end_portal", "end_gateway", "creaking_heart" -> NATURAL;
                default -> OTHER;
            };
        }
    }

    private record Shown(Found found, Kind kind, String name, double distSq) {
    }

    private final LongOpenHashSet announced = new LongOpenHashSet();
    private List<Shown> shown = List.of();
    private int leaks;
    private int generation = -1;
    private int ticks;

    public LeakFinder() {
        super(Category.DONUT, "Leak Finder", "Shows spawners, chests and other block entities the server names but hides under made-up rock");
        style.settings().forEach(this::add);
    }

    @Override
    public String getInfo() {
        return Integer.toString(leaks);
    }

    @Override
    protected void onEnable() {
        ticks = REBUILD;
    }

    @Override
    protected void onDisable() {
        shown = List.of();
        leaks = 0;
        announced.clear();
    }

    @Override
    public void onTick() {
        // A new world: what was told about in the last one means nothing here.
        if (BlockEntityLeaks.generation() != generation) {
            generation = BlockEntityLeaks.generation();
            announced.clear();
            shown = List.of();
            leaks = 0;
        }
        if (++ticks < REBUILD) return;
        ticks = 0;
        rebuild();
    }

    private void rebuild() {
        List<Shown> out = new ArrayList<>();
        List<Shown> fresh = new ArrayList<>();
        Vec3 at = mc.player.position();
        BlockEntityLeaks.forEach(f -> {
            Kind kind = Kind.of(f.id());
            if (!wanted(kind) || !deepEnough(f.pos().getY())) return;
            // The client's own only while it still has them: one gone without a packet saying so is gone.
            if (!f.hidden() && (!normal.get() || !f.type().isValid(mc.level.getBlockState(f.pos())))) return;
            Shown s = new Shown(f, kind, name(f), f.pos().distToCenterSqr(at));
            out.add(s);
            if (f.hidden() && kind.alerts && announced.add(f.pos().asLong())) fresh.add(s);
        });
        out.sort(Comparator.comparingDouble(Shown::distSq));
        shown = out;
        leaks = (int) out.stream().filter(s -> s.found.hidden()).count();
        if (!fresh.isEmpty() && notify.get()) announce(fresh);
    }

    private boolean wanted(Kind kind) {
        return switch (kind) {
            case SPAWNER -> spawners.get();
            case CHEST -> chests.get();
            case BARREL -> barrels.get();
            case SHULKER -> shulkers.get();
            case ENDER -> enderChests.get();
            case STORAGE -> storage.get();
            case OTHER -> other.get();
            case NATURAL -> natural.get();
        };
    }

    private boolean deepEnough(int y) {
        return switch (depth.get()) {
            case "Below Y 0" -> y < 0;
            case "Below -20" -> y < -20;
            case "Below -30" -> y < -30;
            default -> true;
        };
    }

    private String name(Found f) {
        if (f.type() == BlockEntityType.MOB_SPAWNER) {
            EntityType<?> mob = BlockEntityLeaks.mob(f);
            return mob == null ? "Spawner" : mob.getDescription().getString() + " spawner";
        }
        // The client's own block names itself ("Red shulker box"); a hidden one has only its type to go by.
        if (!f.hidden()) return mc.level.getBlockState(f.pos()).getBlock().getName().getString();
        if (f.type() == BlockEntityType.DISPENSER && f.source() == BlockEntityLeaks.Source.EVENT) return "Dispenser or dropper";
        String id = switch (f.id()) {
            case "skull" -> "head";
            case "brushable_block" -> "suspicious block";
            default -> f.id().replace('_', ' ');
        };
        return id.isEmpty() ? "Block entity" : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private void announce(List<Shown> fresh) {
        Shown nearest = fresh.getFirst();
        for (Shown s : fresh) if (s.distSq < nearest.distSq) nearest = s;
        fresh.remove(nearest);
        String title = fresh.isEmpty() ? nearest.name + " leaked" : (fresh.size() + 1) + " leaks found";
        Notify.send(title, describe(nearest), true, sound.get(), sound.get());
        // The rest go in chat, so none get lost behind the one popup; a flood stops at a few.
        for (int i = 0; i < Math.min(fresh.size(), MAX_LINES); i++) {
            Notify.send(fresh.get(i).name + " leaked", describe(fresh.get(i)), false, false, false);
        }
        if (fresh.size() > MAX_LINES) Notify.send("Leak Finder", "and " + (fresh.size() - MAX_LINES) + " more", false, false, false);
    }

    private String describe(Shown s) {
        BlockPos p = s.found.pos();
        return p.getX() + ", " + p.getY() + ", " + p.getZ() + " (" + Math.round(Math.sqrt(p.distToCenterSqr(mc.player.position()))) + "m)";
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        double maxSq = range.get() * range.get();
        for (Shown s : shown) {
            BlockPos p = s.found.pos();
            if (p.distToCenterSqr(render.camera()) > maxSq) continue;
            AABB box = new AABB(p);
            if (s.found.hidden()) style.draw(render, box, s.kind.rgb);
            else render.box(box.deflate(0.04), Theme.alpha(s.kind.rgb, 22), Theme.alpha(s.kind.rgb, 120), 1.2f, false);
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get() || shown.isEmpty()) return;
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double maxSq = range.get() * range.get();
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Shown focused = null;
        double focusedOff = 90 * window.getHeight() / 1080.0;
        List<Shown> onScreen = new ArrayList<>();
        List<double[]> where = new ArrayList<>();
        int leaksLabelled = 0;
        // Nearest first, so the cap keeps the near ones.
        for (Shown s : shown) {
            if (s.found.hidden() && leaksLabelled >= MAX_LABELS) continue;
            Vec3 top = Vec3.atCenterOf(s.found.pos()).add(0, 0.5, 0);
            if (top.distanceToSqr(eye) > maxSq) continue;
            double[] screen = Projection.toScreen(top);
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < focusedOff) {
                focused = s;
                focusedOff = off;
            }
            if (s.found.hidden()) leaksLabelled++;
            onScreen.add(s);
            where.add(screen);
        }
        // Far ones first, so nearer labels sit on top; the client's own only say what they are when you look at them.
        for (int i = onScreen.size() - 1; i >= 0; i--) {
            Shown s = onScreen.get(i);
            if (!s.found.hidden() && s != focused) continue;
            String title = s.name + " at Y " + s.found.pos().getY();
            Badge.draw(g, title, Vec3.atCenterOf(s.found.pos()), why(s.found), 0xFF000000 | s.kind.rgb, where.get(i), s == focused);
        }
    }

    private String why(Found f) {
        if (!f.hidden()) return "Not hidden: the client has it";
        String as = mc.level.hasChunkAt(f.pos())
            ? ", hidden as " + mc.level.getBlockState(f.pos()).getBlock().getName().getString().toLowerCase(Locale.ROOT)
            : ", hidden";
        return switch (f.source()) {
            case CHUNK -> "Named in the chunk" + as;
            case UPDATE -> "Named in a block entity update" + as;
            case EVENT -> "It " + f.event() + as;
        };
    }
}

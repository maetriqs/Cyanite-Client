package com.cyanite.client.modules.world.build;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Spots the Schematic Builder couldn't put the right block in (it cost too much on /ah, or
 * wasn't to be had), each marked with what belongs there: a stand-in block it put there instead,
 * or nothing, for blocks a full block would spoil. They glow in the world until the right block's
 * in, whether the builder's on or not, and looking at one says what it needs. Kept per server and
 * dimension in cyanite/stand-ins.json, so they're still there next time.
 */
public final class StandIns {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final int STAND_IN = 0xFFFFA94D;
    private static final int MISSING = 0xFFFF5D62;
    private static final double SHOW_WITHIN = 96;
    private static final double FOCUS = 70;

    /** One spot: what belongs there, and the stand-in in it (null when it was left empty). */
    private record Mark(String where, BlockPos pos, Block wanted, Block standIn) {
    }

    private static final List<Mark> MARKS = new ArrayList<>();
    private static boolean loaded;
    private static boolean shown = true;

    private StandIns() {
    }

    /** Whether marks are drawn (the builder's Show stand-ins setting). */
    public static void setShown(boolean show) {
        shown = show;
    }

    /** Marks {@code pos} as needing {@code wanted}, with {@code standIn} in it for now (or null, left empty). */
    public static void mark(BlockPos pos, Block wanted, Block standIn) {
        load();
        String where = where();
        MARKS.removeIf(m -> m.where().equals(where) && m.pos().equals(pos));
        MARKS.add(new Mark(where, pos.immutable(), wanted, standIn));
        save();
    }

    /** How many spots here are marked. */
    public static int count() {
        load();
        String where = where();
        return (int) MARKS.stream().filter(m -> m.where().equals(where)).count();
    }

    /** Forgets every mark on this server and dimension. */
    public static void clearHere() {
        load();
        String where = where();
        if (MARKS.removeIf(m -> m.where().equals(where))) save();
    }

    // ---- Drawing

    /** The glowing boxes, from Render3D every frame. Marks whose right block is in now go away. */
    public static void render(Render3D render) {
        if (mc.level == null || mc.player == null) return;
        load();
        String where = where();
        boolean changed = false;
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 400.0);
        for (Iterator<Mark> it = MARKS.iterator(); it.hasNext(); ) {
            Mark m = it.next();
            if (!m.where().equals(where) || !mc.level.hasChunkAt(m.pos())) continue;
            // The right block's in: done with.
            if (mc.level.getBlockState(m.pos()).is(m.wanted())) {
                it.remove();
                changed = true;
                continue;
            }
            if (!shown || Vec3.atCenterOf(m.pos()).distanceTo(render.camera()) > SHOW_WITHIN) continue;
            int color = m.standIn() != null ? STAND_IN : MISSING;
            render.box(new AABB(m.pos()).inflate(0.02), Theme.alpha(color, 30 + 40 * pulse), Theme.alpha(color, 170 + 85 * pulse), 2f, true);
        }
        if (changed) save();
    }

    /** "Needs Glass" over the mark nearest the crosshair. */
    public static void render2D(GuiGraphics g) {
        if (!shown || mc.level == null || mc.player == null) return;
        load();
        String where = where();
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double focus = FOCUS * window.getHeight() / 1080.0;
        Mark best = null;
        double[] at = null;
        double bestOff = focus;
        for (Mark m : MARKS) {
            if (!m.where().equals(where) || Vec3.atCenterOf(m.pos()).distanceTo(mc.player.getEyePosition()) > SHOW_WITHIN) continue;
            double[] screen = Projection.toScreen(Vec3.atCenterOf(m.pos()));
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < bestOff) {
                best = m;
                at = screen;
                bestOff = off;
            }
        }
        if (best == null) return;
        int unit = Render2D.scale();
        String text = "Needs " + best.wanted().getName().getString();
        String sub = best.standIn() != null ? best.standIn().getName().getString() + " standing in" : "Left empty";
        int pad = 3;
        int line = Text.CAP + 4;
        int w = Math.max(Text.width(text), Text.width(sub)) + pad * 2;
        int h = pad * 2 + line + Text.CAP + 1;
        int x = (int) Math.round(at[0] / unit) - w / 2;
        int y = (int) Math.round(at[1] / unit) - h - 8;
        int color = best.standIn() != null ? STAND_IN : MISSING;
        int s = unit;
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, 3 * s, 5 * s, Theme.alpha(color, 50 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, 3 * s - 1, 0xE6101217);
        Render2D.end(g);
        Text.draw(g, text, x + pad, y + pad + 1, color);
        Text.draw(g, sub, x + pad, y + pad + 1 + line, Theme.TEXT_DIM);
    }

    // ---- Saving

    private static String where() {
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) server = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else server = "unknown";
        return server + "|" + (mc.level == null ? "" : mc.level.dimension().identifier().toString());
    }

    private static Path file() {
        return Config.dir().resolve("stand-ins.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            JsonArray array = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                Block wanted = BuiltInRegistries.BLOCK.getValue(Identifier.parse(o.get("wanted").getAsString()));
                Block standIn = o.has("standIn") ? BuiltInRegistries.BLOCK.getValue(Identifier.parse(o.get("standIn").getAsString())) : null;
                if (wanted == Blocks.AIR) continue;
                MARKS.add(new Mark(o.get("where").getAsString(), new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
                    wanted, standIn));
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken file: start with none rather than lose the game.
        }
    }

    private static void save() {
        JsonArray array = new JsonArray();
        for (Mark m : MARKS) {
            JsonObject o = new JsonObject();
            o.addProperty("where", m.where());
            o.addProperty("x", m.pos().getX());
            o.addProperty("y", m.pos().getY());
            o.addProperty("z", m.pos().getZ());
            o.addProperty("wanted", BuiltInRegistries.BLOCK.getKey(m.wanted()).toString());
            if (m.standIn() != null) o.addProperty("standIn", BuiltInRegistries.BLOCK.getKey(m.standIn()).toString());
            array.add(o);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), array.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Still marked for this session.
        }
    }
}

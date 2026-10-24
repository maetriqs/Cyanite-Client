package com.cyanite.client.modules.render;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.WaypointScreen;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Waypoints: marks you drop anywhere and find again. In the world each is a beam of light in its
 * colour, fading up into the sky, over a softly breathing square on the ground. On screen it's a
 * round badge with its initial, under it its name and how far off it is, and when you look
 * straight at it the coordinates too. Ones out of view get a little arrow of their colour round
 * the crosshair, pointing the way to turn.
 *
 * <p>B drops one where you stand; N opens the list to name, recolour, hide or delete them. Kept
 * per server in cyanite/waypoints.json. Overworld ones show in the Nether at an eighth of the
 * distance, and Nether ones in the overworld at eight times, so a portal trip lines up.
 */
public class Waypoints extends Module {
    /** The colours waypoints come in, a new one taking the next in turn. */
    public static final int[] PALETTE = {0x4DD4FF, 0xFF5D62, 0xFFA94D, 0xFFD43B, 0x69DB7C, 0x38D9A9, 0x748FFC, 0xB197FC, 0xF783AC};
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final int BEAM_SEGMENTS = 16;
    private static final double BEAM_STEP = 6;
    /** How near the crosshair (in pixels at 1080p) a badge has to be to open up. */
    private static final double FOCUS = 80;
    /** The ring of arrows round the crosshair, in HUD units. */
    private static final double RING = 58;
    private static final int BADGE = 7;
    private static final int PAD = 3;

    private final KeybindSetting addKey = add(new KeybindSetting("Add key", GLFW.GLFW_KEY_B));
    private final KeybindSetting menuKey = add(new KeybindSetting("Menu key", GLFW.GLFW_KEY_N));
    private final BoolSetting beams = add(new BoolSetting("Beams", true));
    private final BoolSetting arrows = add(new BoolSetting("Arrows", true));
    private final BoolSetting coordinates = add(new BoolSetting("Coordinates", true));
    private final BoolSetting otherDimension = add(new BoolSetting("Other dimension", true));
    private final SliderSetting maxDistance = add(new SliderSetting("Max distance", 0, 0, 20000, 250)
        .format(v -> v == 0 ? "Any" : (int) v + "m"));
    private final SliderSetting size = add(new SliderSetting("Size", 1, 0.75, 1.5, 0.05).format(v -> Math.round(v * 100) + "%"));

    /** Every server's waypoints, by server. */
    private final Map<String, List<Waypoint>> servers = new HashMap<>();
    private boolean loaded;

    public Waypoints() {
        super(Category.RENDER, "Waypoints", "Marks you drop and find again: beams, badges and arrows");
    }

    /** One waypoint: where (in which dimension), what it's called, its colour, and whether it's hidden. */
    public static final class Waypoint {
        public String name;
        public final int x;
        public final int y;
        public final int z;
        public final String dimension;
        public int color;
        public boolean hidden;

        Waypoint(String name, int x, int y, int z, String dimension, int color, boolean hidden) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.color = color & 0xFFFFFF;
            this.hidden = hidden;
        }

        public String coords() {
            return x + ", " + y + ", " + z;
        }
    }

    private record Shown(Waypoint wp, Vec3 at, double[] screen, double distance, boolean onScreen, double offCentre) {
    }

    @Override
    public String getInfo() {
        return inGame() ? Integer.toString(list().size()) : null;
    }

    @Override
    public void onKey(int key) {
        if (!inGame() || mc.screen != null) return;
        if (addKey.isBound() && key == addKey.get()) {
            Waypoint wp = add("", nextColor());
            Notify.send("Waypoint added", wp.name + " at " + wp.coords(), false, false, false);
        } else if (menuKey.isBound() && key == menuKey.get()) {
            mc.setScreen(new WaypointScreen(this, key));
        }
    }

    // ---- The list

    /** This server's waypoints, every dimension, oldest first. */
    public List<Waypoint> list() {
        load();
        return servers.computeIfAbsent(server(), k -> new ArrayList<>());
    }

    /** A new waypoint where you stand. A blank name gets "Waypoint 3" and so on. */
    public Waypoint add(String name, int color) {
        return addAt(name, mc.player.blockPosition(), color);
    }

    /** A new waypoint at a spot in this dimension, for other modules to drop (Base Finder's finds). */
    public Waypoint addAt(String name, BlockPos pos, int color) {
        String named = name == null || name.isBlank() ? "Waypoint " + (list().size() + 1) : name.strip();
        Waypoint wp = new Waypoint(named, pos.getX(), pos.getY(), pos.getZ(), dimension(), color, false);
        list().add(wp);
        save();
        return wp;
    }

    public void remove(Waypoint wp) {
        list().remove(wp);
        save();
    }

    /** Call after changing a waypoint's name, colour or hidden flag, to keep it. */
    public void changed() {
        save();
    }

    /** The colour a new waypoint gets: the next one round the palette. */
    public int nextColor() {
        return PALETTE[list().size() % PALETTE.length];
    }

    /** Where a waypoint is in the dimension you're in, or null if it doesn't show here. */
    public Vec3 where(Waypoint wp) {
        if (mc.level == null) return null;
        String here = dimension();
        double x = wp.x + 0.5;
        double z = wp.z + 0.5;
        if (wp.dimension.equals(here)) return new Vec3(x, wp.y, z);
        if (!otherDimension.get()) return null;
        if (wp.dimension.equals(OVERWORLD) && here.equals(NETHER)) return new Vec3(x / 8, wp.y, z / 8);
        if (wp.dimension.equals(NETHER) && here.equals(OVERWORLD)) return new Vec3(x * 8, wp.y, z * 8);
        return null;
    }

    /** "Overworld", "Nether" or "End", for a waypoint from another dimension. */
    public static String dimensionName(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dimension.substring(dimension.indexOf(':') + 1);
        };
    }

    public boolean fromHere(Waypoint wp) {
        return mc.level != null && wp.dimension.equals(dimension());
    }

    /** "45m" or "1.2km". */
    public static String distance(double metres) {
        return metres < 1000 ? Math.round(metres) + "m" : String.format(Locale.ROOT, "%.1fkm", metres / 1000);
    }

    /** Where to draw it, or null: hidden, from a dimension that doesn't show here, or past Max distance. */
    private Vec3 shown(Waypoint wp, Vec3 from) {
        if (wp.hidden) return null;
        Vec3 at = where(wp);
        if (at == null) return null;
        double max = maxDistance.get();
        return max > 0 && at.distanceTo(from) > max ? null : at;
    }

    // ---- In the world

    @Override
    public void onRender3D(Render3D render) {
        if (!beams.get()) return;
        Vec3 camera = render.camera();
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 450.0);
        for (Waypoint wp : list()) {
            Vec3 at = shown(wp, camera);
            if (at == null) continue;
            int rgb = 0xFF000000 | wp.color;
            // A beam of light up into the sky, fading as it rises.
            for (int i = 0; i < BEAM_SEGMENTS; i++) {
                float fade = 1 - (float) i / BEAM_SEGMENTS;
                Vec3 from = new Vec3(at.x, at.y + i * BEAM_STEP, at.z);
                render.line(from, from.add(0, BEAM_STEP, 0), Theme.alpha(rgb, 235 * fade * fade), 2.4f, true);
            }
            // A square on the ground under it, breathing.
            AABB pad = new AABB(at.x - 0.5, at.y, at.z - 0.5, at.x + 0.5, at.y + 0.03, at.z + 0.5);
            render.box(pad, Theme.alpha(rgb, 28 + 46 * pulse), Theme.alpha(rgb, 200 + 55 * pulse), 1.6f, true);
        }
    }

    // ---- On screen

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double focusPx = FOCUS * window.getHeight() / 1080.0;
        List<Shown> shown = new ArrayList<>();
        Shown focused = null;
        for (Waypoint wp : list()) {
            Vec3 at = shown(wp, eye);
            if (at == null) continue;
            Vec3 point = at.add(0, 1.3, 0);
            double[] screen = Projection.toScreen(point);
            boolean on = screen != null && screen[0] >= 0 && screen[0] <= window.getWidth() && screen[1] >= 0 && screen[1] <= window.getHeight();
            double off = on ? Math.hypot(screen[0] - midX, screen[1] - midY) : Double.MAX_VALUE;
            Shown s = new Shown(wp, at, screen, point.distanceTo(eye), on, off);
            shown.add(s);
            if (off < focusPx && (focused == null || off < focused.offCentre())) focused = s;
        }
        // Far ones first, so the near ones sit on top.
        shown.sort(Comparator.comparingDouble(Shown::distance).reversed());
        for (Shown s : shown) {
            if (s.onScreen()) badge(g, s, s == focused);
            else if (arrows.get()) arrow(g, s, camera);
        }
    }

    /** The round badge with its initial, and under it the name and distance (the coordinates too, when focused). */
    private void badge(GuiGraphics g, Shown shown, boolean focused) {
        Waypoint wp = shown.wp();
        int unit = Render2D.scale();
        g.pose().pushMatrix();
        g.pose().translate(Math.round(shown.screen()[0]) / (float) unit, Math.round(shown.screen()[1]) / (float) unit);
        g.pose().scale(size.get().floatValue());
        int s = unit;
        int rgb = 0xFF000000 | wp.color;
        float a = focused ? 1f : 0.8f;

        int r = BADGE * s;
        int ring = Math.max(2, s);
        Render2D.pixels(g);
        Render2D.glow(g, -r, -r, 2 * r, 2 * r, r, 7 * s, Theme.alpha(rgb, (focused ? 130 : 70) * Theme.glow()));
        Render2D.rect(g, -r, -r, 2 * r, 2 * r, r, Theme.fade(0xFF0B0D11, a));
        Render2D.rect(g, -r + ring, -r + ring, 2 * (r - ring), 2 * (r - ring), r - ring, Theme.fade(rgb, a));
        Render2D.end(g);
        String letter = initial(wp.name);
        Text.draw(g, letter, -Text.width(letter) / 2, -Text.CAP / 2, Theme.fade(0xFF0B0D11, a), false);

        String dist = distance(shown.distance());
        String sub = null;
        if (focused && coordinates.get()) {
            sub = StreamerMode.mask(wp.coords());
            if (!fromHere(wp)) sub += "  " + dimensionName(wp.dimension);
        }
        int line = Text.CAP + 4;
        int w = Text.width(wp.name) + 6 + Text.width(dist) + PAD * 2;
        if (sub != null) w = Math.max(w, Text.width(sub) + PAD * 2);
        int h = PAD * 2 + Text.CAP + (sub != null ? line : 0) + 1;
        int lx = -w / 2;
        int ly = BADGE + 4;
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);
        Render2D.pixels(g);
        Render2D.rect(g, lx * s, ly * s, w * s, h * s, corner, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, lx * s + 1, ly * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), Theme.fade(0xE6101217, a));
        Render2D.end(g);
        Text.draw(g, wp.name, lx + PAD, ly + PAD + 1, Theme.fade(Theme.TEXT, a));
        Text.draw(g, dist, lx + PAD + Text.width(wp.name) + 6, ly + PAD + 1, Theme.fade(Theme.mix(rgb, 0xFFFFFFFF, 0.25f), a));
        if (sub != null) Text.draw(g, sub, lx + PAD, ly + PAD + 1 + line, Theme.TEXT_DIM);
        g.pose().popMatrix();
    }

    /** A little arrow of its colour on a ring round the crosshair, pointing the way to turn. */
    private void arrow(GuiGraphics g, Shown shown, Camera camera) {
        Vec3 eye = camera.position();
        float towards = (float) Math.toDegrees(Math.atan2(shown.at().z - eye.z, shown.at().x - eye.x)) - 90f;
        double turn = Math.toRadians(Mth.wrapDegrees(towards - camera.yRot()));
        int unit = Render2D.scale();
        double ring = RING * size.get();
        int px = (int) Math.round((mc.getWindow().getWidth() / 2.0 / unit + Math.sin(turn) * ring) * unit);
        int py = (int) Math.round((mc.getWindow().getHeight() / 2.0 / unit - Math.cos(turn) * ring) * unit);
        int rgb = 0xFF000000 | shown.wp().color;
        float k = size.get().floatValue();
        int glow = Math.round(6 * unit * k);
        Render2D.pixels(g);
        Render2D.glow(g, px - glow, py - glow, 2 * glow, 2 * glow, glow, 5 * unit, Theme.alpha(rgb, 110 * Theme.glow()));
        // The chevron points down at angle 0; turned to point out along the ring, and drawn large.
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().scale(1.8f * k);
        Render2D.chevron(g, 0, 0, (float) (turn + Math.PI), rgb);
        g.pose().popMatrix();
        Render2D.end(g);
    }

    private static String initial(String name) {
        String trimmed = name.strip();
        return trimmed.isEmpty() ? "?" : trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    // ---- Saving

    private String server() {
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip;
        if (mc.getSingleplayerServer() != null) return "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return "unknown";
    }

    private String dimension() {
        return mc.level.dimension().identifier().toString();
    }

    private Path file() {
        return Config.dir().resolve("waypoints.json");
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> server : json.entrySet()) {
                List<Waypoint> list = new ArrayList<>();
                for (JsonElement e : server.getValue().getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    list.add(new Waypoint(o.get("name").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        o.get("dimension").getAsString(), o.get("color").getAsInt(), o.has("hidden") && o.get("hidden").getAsBoolean()));
                }
                servers.put(server.getKey(), list);
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken file: start empty rather than lose the game.
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        servers.forEach((server, list) -> {
            if (list.isEmpty()) return;
            JsonArray array = new JsonArray();
            for (Waypoint wp : list) {
                JsonObject o = new JsonObject();
                o.addProperty("name", wp.name);
                o.addProperty("x", wp.x);
                o.addProperty("y", wp.y);
                o.addProperty("z", wp.z);
                o.addProperty("dimension", wp.dimension);
                o.addProperty("color", wp.color);
                if (wp.hidden) o.addProperty("hidden", true);
                array.add(o);
            }
            json.add(server, array);
        });
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Still there for this session.
        }
    }
}

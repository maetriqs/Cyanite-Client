package com.cyanite.client.modules.misc;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers where you last died, on each server and in each dimension, and shows a waypoint back
 * to it with the distance. Saved to cyanite/deaths.json, so it survives relogging. It clears
 * itself once you get back there.
 */
public class DeathPosition extends Module {
    private static final double ARRIVED = 4;
    private static final int PAD = 4;

    private final BoolSetting waypoint = add(new BoolSetting("Waypoint", true));
    private final BoolSetting tracer = add(new BoolSetting("Tracer", false));
    private final BoolSetting chat = add(new BoolSetting("Chat message", true));
    private final BoolSetting clearOnArrival = add(new BoolSetting("Clear on arrival", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFF5D62));

    private JsonObject deaths;
    private boolean wasDead;

    public DeathPosition() {
        super(Category.MISC, "Death Position", "Shows where you last died, with a waypoint back");
    }

    @Override
    public void onTick() {
        if (deaths == null) load();
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            BlockPos pos = mc.player.blockPosition();
            JsonObject death = new JsonObject();
            death.addProperty("x", pos.getX());
            death.addProperty("y", pos.getY());
            death.addProperty("z", pos.getZ());
            death.addProperty("time", System.currentTimeMillis());
            deaths.add(key(), death);
            save();
            if (chat.get()) {
                Notify.send("Death", "You died at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                    + " in " + mc.level.dimension().identifier().getPath().replace('_', ' '), false, false, false);
            }
        }
        wasDead = dead;

        BlockPos spot = current();
        if (!dead && spot != null && clearOnArrival.get() && Vec3.atCenterOf(spot).distanceTo(mc.player.position()) <= ARRIVED) {
            deaths.remove(key());
            save();
        }
    }

    /** Your last death on this server, in this dimension, or null. */
    public BlockPos current() {
        if (deaths == null || mc.level == null) return null;
        if (!deaths.has(key())) return null;
        JsonObject death = deaths.getAsJsonObject(key());
        return new BlockPos(death.get("x").getAsInt(), death.get("y").getAsInt(), death.get("z").getAsInt());
    }

    @Override
    public void onRender3D(Render3D render) {
        BlockPos spot = current();
        if (spot == null || mc.player.isDeadOrDying()) return;
        int rgb = color.get();
        // A thin pillar of light from the spot, so it's easy to find from afar.
        AABB beam = new AABB(spot.getX() + 0.35, spot.getY(), spot.getZ() + 0.35, spot.getX() + 0.65, spot.getY() + 48, spot.getZ() + 0.65);
        render.box(beam, 0x30000000 | rgb, 0x80000000 | rgb, 1.5f, true);
        render.box(new AABB(spot), 0x40000000 | rgb, 0xFF000000 | rgb, 2f, true);
        if (tracer.get()) render.tracer(Vec3.atCenterOf(spot), 0xC0000000 | rgb, 1.5f, true);
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        BlockPos spot = current();
        if (!waypoint.get() || spot == null || mc.player.isDeadOrDying()) return;
        Vec3 point = Vec3.atCenterOf(spot).add(0, 1.2, 0);
        double[] screen = Projection.toScreen(point);
        if (screen == null) return;

        int unit = Render2D.scale();
        g.pose().pushMatrix();
        g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
        String title = "Last death";
        String dist = Math.round(point.distanceTo(mc.player.getEyePosition(partialTick))) + "m";
        String coords = StreamerMode.mask(spot.getX() + ", " + spot.getY() + ", " + spot.getZ());
        int line = Text.CAP + 4;
        int w = Math.max(Text.width(title) + 6 + Text.width(dist), Text.width(coords)) + PAD * 2;
        int h = PAD * 2 + line * 2 - 4;
        int x = -w / 2;
        int y = -h;
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, corner, 6 * s, Theme.alpha(rgb, 40 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, corner, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), 0xE6101217);
        Render2D.rect(g, x * s + corner, y * s + 1, w * s - 2 * corner, Math.max(1, s / 2), 0, rgb);
        Render2D.end(g);
        Text.draw(g, title, x + PAD, y + PAD, rgb);
        Text.draw(g, dist, x + PAD + Text.width(title) + 6, y + PAD, Theme.TEXT_FAINT);
        Text.draw(g, coords, x + PAD, y + PAD + line, Theme.TEXT_DIM);
        g.pose().popMatrix();
    }

    /** Server (or singleplayer world) plus dimension: a death on one server means nothing on another. */
    private String key() {
        String where;
        if (mc.getCurrentServer() != null) where = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) where = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else where = "unknown";
        return where + "|" + mc.level.dimension().identifier();
    }

    private Path file() {
        return Config.dir().resolve("deaths.json");
    }

    private void load() {
        deaths = new JsonObject();
        try {
            if (Files.exists(file())) deaths = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException ignored) {
            deaths = new JsonObject();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), deaths.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The waypoint still works for this session.
        }
    }
}

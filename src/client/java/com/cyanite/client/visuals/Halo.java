package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.List;

/**
 * A glowing ring floating over your head, or a neon hat, for when you're seen from outside (F5, Freecam), and
 * over other players' heads if you like. Only you see it.
 */
public final class Halo {
    public static final Group GROUP = new Group("halo", "Halo",
        "A glowing halo or neon hat over your head in third person, and on other players if you like. Only you see it.",
        "halo hat china cosmetic head ring glow rainbow angel");
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Style", "Halo", List.of("Halo", "Hat")));
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFFD86B).full());
    static final BoolSetting RAINBOW = GROUP.add(new BoolSetting("Rainbow", false));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 1, 0.5, 2, 0.05).format(Particles::times));
    static final SliderSetting HEIGHT = GROUP.add(new SliderSetting("Height", 0.3, 0, 1, 0.05));
    static final BoolSetting OTHERS = GROUP.add(new BoolSetting("Other players", false));

    private static final int SEGMENTS = 40;
    private static final int SPOKES = 10;
    private static final double OTHERS_RANGE = 48;

    private Halo() {
    }

    static void render3D(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        float partial = render.partialTick();
        if (camera.isDetached()) draw(render, mc.player, partial);
        if (!OTHERS.get()) return;
        for (Entity e : WorldScan.entities()) {
            if (e instanceof Player player && player != mc.player && !player.isInvisible()
                && player.position().distanceToSqr(render.camera()) < OTHERS_RANGE * OTHERS_RANGE) draw(render, player, partial);
        }
    }

    private static void draw(Render3D render, Player player, float partial) {
        double size = SIZE.get();
        double spin = System.nanoTime() / 1e9 * 0.8;
        Vec3 top = player.getPosition(partial).add(0, player.getBbHeight() + 0.05, 0);
        if (STYLE.is("Hat")) {
            // A wide flat cone: a ring for the brim and spokes up to the point.
            double radius = 0.55 * size;
            Vec3 tip = top.add(0, 0.3 * size, 0);
            Vec3 prev = null;
            for (int i = 0; i <= SEGMENTS; i++) {
                double a = spin + Math.PI * 2 * i / SEGMENTS;
                Vec3 p = top.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
                if (prev != null) segment(render, prev, p, color(i, SEGMENTS), size);
                if (i % (SEGMENTS / SPOKES) == 0 && i < SEGMENTS) segment(render, p, tip, color(i, SEGMENTS), size * 0.7);
                prev = p;
            }
            render.glow(tip, 0.18 * size, Visuals.argb(color(0, 1), 0.6), false);
            return;
        }
        double radius = 0.3 * size;
        Vec3 middle = top.add(0, HEIGHT.get() + 0.04 * Math.sin(spin * 2.5), 0);
        Vec3 prev = null;
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = spin + Math.PI * 2 * i / SEGMENTS;
            Vec3 p = middle.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            if (prev != null) segment(render, prev, p, color(i, SEGMENTS), size);
            prev = p;
        }
    }

    /** A soft streak of light with a bright thin one down its middle. */
    private static void segment(Render3D render, Vec3 a, Vec3 b, int rgb, double size) {
        render.glowLine(a, b, Visuals.argb(rgb, 0.45), Visuals.argb(rgb, 0.45), 0.09 * size, false);
        render.glowLine(a, b, Visuals.argb(rgb, 1), Visuals.argb(rgb, 1), 0.025 * size, false);
    }

    /** The colour {@code i} of {@code of} round the ring: the same all round, or a rainbow that turns. */
    static int color(int i, int of) {
        if (!RAINBOW.get()) return COLOR.get();
        float hue = (float) ((System.nanoTime() / 1e9 * 0.2 + i / (double) of) % 1);
        return Color.HSBtoRGB(hue, 0.75f, 1) & 0xFFFFFF;
    }
}

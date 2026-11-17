package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Short effects placed in the world by the Visuals features: rings that spread out on the ground, glowing bursts
 * of light, words that rise and fade, and a flash over the screen. Each one lasts a second or two; there are never more than {@link #MAX}.
 */
public final class WorldFx {
    private static final int MAX = 96;
    private static final int RING_SEGMENTS = 36;

    private record Ring(Vec3 center, int color, double from, double to, float seconds, float width, long start, boolean upright) {
    }

    private record Word(Vec3 at, String text, int color, float size, float seconds, long start) {
    }

    private record Burst(Vec3 at, int color, double radius, float seconds, long start) {
    }

    private static final List<Ring> rings = new ArrayList<>();
    private static final List<Word> words = new ArrayList<>();
    private static final List<Burst> bursts = new ArrayList<>();
    private static int flashColor;
    private static float flashStrength;
    private static float flashSeconds;
    private static long flashStart;

    private WorldFx() {
    }

    /** A ring flat on the ground around {@code center}, growing from {@code from} to {@code to} blocks across while it fades. */
    public static void ring(Vec3 center, int rgb, double from, double to, float seconds, float width) {
        add(rings, new Ring(center, rgb, from, to, seconds, width, System.nanoTime(), false));
    }

    /** Like {@link #ring}, but standing up and facing you, like a shockwave seen side on. */
    public static void uprightRing(Vec3 center, int rgb, double from, double to, float seconds, float width) {
        add(rings, new Ring(center, rgb, from, to, seconds, width, System.nanoTime(), true));
    }

    /** A ball of light at {@code at} that swells to {@code radius} blocks in a blink and fades over {@code seconds}. */
    public static void burst(Vec3 at, int rgb, double radius, float seconds) {
        add(bursts, new Burst(at, rgb, radius, seconds, System.nanoTime()));
    }

    /** Text that rises from {@code at} and fades out. */
    public static void word(Vec3 at, String text, int argb, float size, float seconds) {
        add(words, new Word(at, text, argb, size, seconds, System.nanoTime()));
    }

    /** The whole screen washed with {@code rgb}, {@code strength} 0 to 1 at first, gone after {@code seconds}. */
    public static void flash(int rgb, float strength, float seconds) {
        if (strength <= 0) return;
        flashColor = rgb;
        flashStrength = Math.min(1, strength);
        flashSeconds = seconds;
        flashStart = System.nanoTime();
    }

    private static <T> void add(List<T> list, T fx) {
        if (list.size() >= MAX) list.remove(0);
        list.add(fx);
    }

    public static void clear() {
        rings.clear();
        words.clear();
        bursts.clear();
        flashStrength = 0;
    }

    static void tick() {
        long now = System.nanoTime();
        rings.removeIf(r -> age(r.start, now) > r.seconds);
        words.removeIf(w -> age(w.start, now) > w.seconds);
        bursts.removeIf(b -> age(b.start, now) > b.seconds);
    }

    private static float age(long start, long now) {
        return (now - start) / 1e9f;
    }

    static void render3D(Render3D render) {
        if (rings.isEmpty() && words.isEmpty() && bursts.isEmpty()) return;
        long now = System.nanoTime();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        for (Burst b : bursts) {
            float t = age(b.start, now) / b.seconds;
            if (t >= 1) continue;
            // Swells fast and fades slower, so it reads as a flash of light, not a growing ball.
            float swell = 1 - (1 - Math.min(1, t * 4)) * (1 - Math.min(1, t * 4));
            render.glow(b.at, b.radius * (0.45 + 0.55 * swell), Visuals.argb(b.color, (1 - t) * (1 - t) * 0.9), false);
        }
        for (Ring r : rings) {
            float t = age(r.start, now) / r.seconds;
            if (t >= 1) continue;
            float eased = 1 - (1 - t) * (1 - t);
            double radius = (r.from + (r.to - r.from) * eased) / 2;
            int color = Visuals.argb(r.color, (1 - t) * 0.9);
            Vec3 prev = null;
            for (int i = 0; i <= RING_SEGMENTS; i++) {
                double a = Math.PI * 2 * i / RING_SEGMENTS;
                Vec3 p;
                if (r.upright) {
                    // In the plane facing the camera: across the camera's right and up.
                    double yaw = Math.toRadians(camera.yRot());
                    double x = Math.cos(a) * radius;
                    p = r.center.add(-Math.cos(yaw) * x, Math.sin(a) * radius, -Math.sin(yaw) * x);
                } else {
                    p = r.center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
                }
                if (prev != null) render.line(prev, p, color, r.width, true);
                prev = p;
            }
        }
        Quaternionf facing = facing(camera);
        var font = Minecraft.getInstance().font;
        for (Word w : words) {
            float t = age(w.start, now) / w.seconds;
            if (t >= 1) continue;
            float alpha = t < 0.7f ? 1 : 1 - (t - 0.7f) / 0.3f;
            int a = Math.max(5, Math.round((w.color >>> 24) * alpha));
            Vec3 at = w.at.add(0, t * 0.8, 0);
            render.text(at, facing, w.size, -font.width(w.text) / 2f, -font.lineHeight / 2f, w.text, a << 24 | w.color & 0xFFFFFF, 0, false);
        }
    }

    /** Text standing in the world turned to read straight at the camera. */
    static Quaternionf facing(Camera camera) {
        return new Quaternionf().rotationYXZ((float) Math.PI - camera.yRot() * Mth.DEG_TO_RAD, -camera.xRot() * Mth.DEG_TO_RAD, 0);
    }

    static void render2D(GuiGraphics g) {
        if (flashStrength <= 0) return;
        float t = age(flashStart, System.nanoTime()) / flashSeconds;
        if (t >= 1) {
            flashStrength = 0;
            return;
        }
        float a = flashStrength * (1 - t) * (1 - t);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), Visuals.argb(flashColor, a * 0.6));
    }
}

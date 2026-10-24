package com.cyanite.client.gui.render;

import com.cyanite.client.system.setting.ColorSetting;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Shape drawing for the GUI. Everything here works in real framebuffer pixels, so wrap
 * calls in {@link #pixels(GuiGraphics)} / {@link #end(GuiGraphics)}. Shapes are baked into
 * small textures once and drawn 1:1, which keeps corners and glows smooth at any GUI scale.
 */
public final class Render2D {
    private static final Map<Long, Identifier> SHAPES = new HashMap<>();
    private static final Map<Integer, Identifier> CHEVRONS = new HashMap<>();
    private static final int FADE_WIDTH = 128;
    private static Identifier fade;
    private static Identifier hue;
    private static int unit;

    private Render2D() {
    }

    /** Pixels per GUI unit. The click GUI picks its own instead of using Minecraft's GUI scale. */
    public static int scale() {
        return unit > 0 ? unit : Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** @return the previous value, so a caller can put it back. */
    public static int setScale(int pixelsPerUnit) {
        int previous = unit;
        unit = pixelsPerUnit;
        return previous;
    }

    public static void pixels(GuiGraphics g) {
        g.pose().pushMatrix();
        g.pose().scale(1f / scale());
    }

    public static void end(GuiGraphics g) {
        g.pose().popMatrix();
    }

    public static void rect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;

        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }

        Identifier tex = shape(r, 0);
        float size = 2 * r + 1;
        // One piece for the game's GUI: the four corners from the baked shape, and the straight parts
        // from its middle pixel, which is solid.
        Quads quads = Quads.of(g, tex, color);
        float a = r / size;
        float b = (r + 1) / size;
        quads.add(x, y, x + r, y + r, 0, 0, a, a);
        quads.add(x + w - r, y, x + w, y + r, b, 0, 1, a);
        quads.add(x, y + h - r, x + r, y + h, 0, b, a, 1);
        quads.add(x + w - r, y + h - r, x + w, y + h, b, b, 1, 1);
        float mid = (r + 0.5f) / size;
        quads.add(x + r, y, x + w - r, y + r, mid, mid, mid, mid);
        quads.add(x, y + r, x + w, y + h - r, mid, mid, mid, mid);
        quads.add(x + r, y + h - r, x + w - r, y + h, mid, mid, mid, mid);
        quads.submit(g);
    }

    /** Soft light (or shadow) spreading {@code blur} pixels out from the given rounded rect. */
    public static void glow(GuiGraphics g, int x, int y, int w, int h, int radius, int blur, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;

        int r = Math.min(radius, Math.min(w, h) / 2);
        int c = r + blur;
        int size = 2 * c + 1;
        Identifier tex = shape(r, blur);

        int gx = x - blur;
        int gy = y - blur;
        int gw = w + 2 * blur;
        int gh = h + 2 * blur;
        int midW = gw - 2 * c;
        int midH = gh - 2 * c;

        // One piece for the game's GUI: corners, edges stretched from the shape's middle row and
        // column, and the middle from its centre pixel (solid when the shape has corners to round).
        Quads quads = Quads.of(g, tex, color);
        float f = size;
        float a = c / f;
        float b = (c + 1) / f;
        quads.add(gx, gy, gx + c, gy + c, 0, 0, a, a);
        quads.add(gx + gw - c, gy, gx + gw, gy + c, b, 0, 1, a);
        quads.add(gx, gy + gh - c, gx + c, gy + gh, 0, b, a, 1);
        quads.add(gx + gw - c, gy + gh - c, gx + gw, gy + gh, b, b, 1, 1);
        if (midW > 0) {
            quads.add(gx + c, gy, gx + c + midW, gy + c, a, 0, b, a);
            quads.add(gx + c, gy + gh - c, gx + c + midW, gy + gh, a, b, b, 1);
        }
        if (midH > 0) {
            quads.add(gx, gy + c, gx + c, gy + c + midH, 0, a, a, b);
            quads.add(gx + gw - c, gy + c, gx + gw, gy + c + midH, b, a, 1, b);
        }
        boolean middle = midW > 0 && midH > 0;
        if (middle && r > 0) {
            float mid = (c + 0.5f) / f;
            quads.add(gx + c, gy + c, gx + gw - c, gy + gh - c, mid, mid, mid, mid);
        }
        quads.submit(g);
        if (middle && r <= 0) g.fill(gx + c, gy + c, gx + gw - c, gy + gh - c, color);
    }

    /** Horizontal gradient from {@code color} on the left to transparent on the right. */
    public static void fadeRight(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;

        if (fade == null) {
            NativeImage img = new NativeImage(FADE_WIDTH, 1, false);
            for (int i = 0; i < FADE_WIDTH; i++) {
                float t = 1f - i / (FADE_WIDTH - 1f);
                img.setPixel(i, 0, white((float) Math.pow(t, 1.6)));
            }
            fade = upload("fade", img);
        }

        g.blit(RenderPipelines.GUI_TEXTURED, fade, x, y, 0, 0, w, h, FADE_WIDTH, 1, FADE_WIDTH, 1, color);
    }

    /** A strip running through every hue, left to right. */
    public static void hueBar(GuiGraphics g, int x, int y, int w, int h, int tint) {
        if (w <= 0 || h <= 0) return;

        if (hue == null) {
            NativeImage img = new NativeImage(256, 1, false);
            for (int i = 0; i < 256; i++) {
                img.setPixel(i, 0, 0xFF000000 | ColorSetting.fromHsv(i / 255f, 0.72f, 1f));
            }
            hue = upload("hue", img);
        }

        g.blit(RenderPipelines.GUI_TEXTURED, hue, x, y, 0, 0, w, h, 256, 1, 256, 1, tint);
    }

    /** A "v" chevron centred on (cx, cy). Angle 0 points down. */
    public static void chevron(GuiGraphics g, int cx, int cy, float angle, int color) {
        int s = scale();
        Identifier tex = CHEVRONS.computeIfAbsent(s, Render2D::bakeChevron);
        int n = 6 * s;

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(angle);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, -n / 2, -n / 2, 0, 0, n, n, n, n, color);
        g.pose().popMatrix();
    }

    private static Identifier shape(int r, int blur) {
        long key = ((long) r << 32) | blur;
        Identifier cached = SHAPES.get(key);
        if (cached != null) return cached;

        int c = r + blur;
        int size = 2 * c + 1;
        NativeImage img = new NativeImage(size, size, false);

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                // Distance from the pixel centre to the 1px core, minus the radius.
                float dx = Math.max(Math.max(c - (px + 0.5f), (px + 0.5f) - (c + 1)), 0);
                float dy = Math.max(Math.max(c - (py + 0.5f), (py + 0.5f) - (c + 1)), 0);
                float sd = (float) Math.sqrt(dx * dx + dy * dy) - r;

                float a;
                if (blur == 0) {
                    a = clamp(0.5f - sd);
                } else {
                    float t = clamp((sd + 0.5f) / blur);
                    a = (float) Math.pow(1f - t, 2.2);
                }
                img.setPixel(px, py, white(a));
            }
        }

        Identifier id = upload("shape_" + r + "_" + blur, img);
        SHAPES.put(key, id);
        return id;
    }

    private static Identifier bakeChevron(int s) {
        int n = 6 * s;
        float hw = Math.max(0.75f, 0.055f * n);
        float ax = 0.22f * n, ay = 0.36f * n;
        float bx = 0.50f * n, by = 0.64f * n;
        float cx = 0.78f * n;

        NativeImage img = new NativeImage(n, n, false);
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                float x = px + 0.5f, y = py + 0.5f;
                float d = Math.min(segment(x, y, ax, ay, bx, by), segment(x, y, bx, by, cx, ay));
                img.setPixel(px, py, white(clamp(hw - d + 0.5f)));
            }
        }
        return upload("chevron_" + s, img);
    }

    private static float segment(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float t = clamp(((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy));
        float dx = px - (ax + vx * t), dy = py - (ay + vy * t);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static Identifier upload(String name, NativeImage img) {
        Identifier id = Identifier.fromNamespaceAndPath("cyanite_client", "gui/" + name);
        DynamicTexture texture = new DynamicTexture(() -> "cyanite_client:" + name, img);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    private static int white(float alpha) {
        return (Math.round(clamp(alpha) * 255f) << 24) | 0xFFFFFF;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}

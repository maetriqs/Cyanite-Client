package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import net.minecraft.client.gui.GuiGraphics;

final class Widgets {
    static final int TOGGLE_W = 14;
    static final int TOGGLE_H = 8;

    private Widgets() {
    }

    /** Accent pill button. {@code hover} is animated, 0 to 1; disabled buttons go grey. */
    static void button(GuiGraphics g, int x, int y, int w, int h, String label, float hover, boolean enabled, float alpha) {
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();

        Render2D.pixels(g);
        if (enabled) {
            Render2D.glow(g, x * s, y * s, w * s, h * s, r, 6 * s, Theme.alpha(accent, (30 + hover * 60) * Theme.glow() * alpha));
            Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.alpha(accent, (70 + hover * 60) * alpha));
        } else {
            Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.fade(Theme.TRACK, alpha));
        }
        Render2D.end(g);

        int color = enabled ? Theme.mix(Theme.accentText(), 0xFFFFFFFF, hover) : Theme.TEXT_FAINT;
        Text.draw(g, label, x + (w - Text.width(label)) / 2, y + (h - Text.CAP) / 2, Theme.fade(color, alpha));
    }

    /**
     * A filled five-pointed star centred on (cx, cy), {@code r} to each point, all in framebuffer
     * pixels: call between {@link Render2D#pixels} and {@link Render2D#end}.
     */
    static void star(GuiGraphics g, double cx, double cy, double r, int color) {
        double[] xs = new double[10];
        double[] ys = new double[10];
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36);
            double d = i % 2 == 0 ? r : r * 0.45;
            xs[i] = cx + Math.cos(a) * d;
            ys[i] = cy + Math.sin(a) * d;
        }
        // A pixel row at a time: inside is between each pair of edge crossings.
        double[] crossings = new double[10];
        for (int y = (int) Math.floor(cy - r); y < (int) Math.ceil(cy + r); y++) {
            double row = y + 0.5;
            int n = 0;
            for (int i = 0; i < 10; i++) {
                int j = (i + 1) % 10;
                if ((row < ys[i]) == (row < ys[j])) continue;
                crossings[n++] = xs[i] + (row - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i]);
            }
            java.util.Arrays.sort(crossings, 0, n);
            for (int k = 0; k + 1 < n; k += 2) {
                int x0 = (int) Math.round(crossings[k]);
                g.fill(x0, y, Math.max(x0 + 1, (int) Math.round(crossings[k + 1])), y + 1, color);
            }
        }
    }

    /** On/off switch at (x, y) in GUI units. {@code on} is animated, 0 to 1. */
    static void toggle(GuiGraphics g, int x, int y, float on, float alpha) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        int tw = TOGGLE_W * s;
        int th = TOGGLE_H * s;
        int tx = x * s;
        int ty = y * s;
        int knob = 6 * s;
        int kx = tx + s + Math.round(on * (tw - knob - 2 * s));

        Render2D.pixels(g);
        Render2D.glow(g, tx, ty, tw, th, th / 2, 5 * s, Theme.alpha(accent, on * 90 * Theme.glow() * alpha));
        Render2D.rect(g, tx, ty, tw, th, th / 2, Theme.fade(Theme.mix(Theme.TRACK, accent, on), alpha));
        Render2D.rect(g, kx, ty + s, knob, knob, knob / 2, Theme.fade(Theme.mix(Theme.TEXT_DIM, 0xFFFFFFFF, on), alpha));
        Render2D.end(g);
    }
}

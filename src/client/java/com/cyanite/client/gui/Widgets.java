package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import net.minecraft.client.gui.GuiGraphics;

final class Widgets {
    static final int TOGGLE_W = 14;
    static final int TOGGLE_H = 8;
    /** The classic menu's check box, in UI units. */
    private static final int BOX = 8;
    /** Key caps and value pills, in UI units. */
    static final int CAP_H = Text.CAP + 5;

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

    /** The classic, Meteor-style menu rather than the Beta UI: check boxes, key caps and value pills. */
    static boolean classic() {
        return !GuiSettings.BETA.get();
    }

    /**
     * On/off at (x, y) in GUI units, filling TOGGLE_W by TOGGLE_H. {@code on} is animated, 0 to 1.
     * A check box in the classic menu, a switch in the Beta UI.
     */
    static void toggle(GuiGraphics g, int x, int y, float on, float alpha) {
        if (classic()) {
            checkbox(g, x + TOGGLE_W - BOX, y + (TOGGLE_H - BOX) / 2, on, alpha);
            return;
        }
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

    /** A Meteor-style check box at (x, y) in UI units: an outlined square, filled with the accent and ticked when on. */
    static void checkbox(GuiGraphics g, int x, int y, float on, float alpha) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        int px = x * s;
        int py = y * s;
        int n = BOX * s;
        int r = Math.round(Math.min(Theme.radius(), 2) * s);
        int edge = Math.max(1, s / 2);

        Render2D.pixels(g);
        Render2D.glow(g, px, py, n, n, r, 4 * s, Theme.alpha(accent, on * 80 * Theme.glow() * alpha));
        Render2D.rect(g, px, py, n, n, r, Theme.fade(Theme.mix(Theme.EDGE, accent, on), alpha));
        Render2D.rect(g, px + edge, py + edge, n - 2 * edge, n - 2 * edge, Math.max(0, r - edge),
            Theme.fade(Theme.mix(Theme.FIELD, accent, on), alpha));
        if (on > 0.02f) Render2D.check(g, px + n / 2, py + n / 2, Theme.alpha(0xFFFFFF, on * 255 * alpha));
        Render2D.end(g);
    }

    /**
     * A key name in a key cap, its right edge at {@code right} and its middle at {@code midY},
     * in UI units. @return its width
     */
    static int keycap(GuiGraphics g, int right, int midY, String key, int textColor, float alpha) {
        int w = Text.width(key) + 7;
        int x = right - w;
        int y = midY - CAP_H / 2;
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 2) * s);
        int edge = Math.max(1, s / 2);

        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, CAP_H * s, r, Theme.fade(Theme.EDGE, alpha));
        // A deeper bottom edge, the way a key sits.
        Render2D.rect(g, x * s + edge, y * s + edge, w * s - 2 * edge, CAP_H * s - 3 * edge, Math.max(0, r - edge),
            Theme.fade(Theme.KEY, alpha));
        Render2D.end(g);

        Text.draw(g, key, x + 4, y + (CAP_H - Text.CAP) / 2, Theme.fade(textColor, alpha));
        return w;
    }

    /**
     * A setting's value in a dropdown pill with an arrow, right edge at {@code right}, middle at
     * {@code midY}; {@code hover} lights its outline. @return its width
     */
    static int pill(GuiGraphics g, int right, int midY, String value, int textColor, float hover, float alpha) {
        int w = Text.width(value) + 15;
        int x = right - w;
        int y = midY - CAP_H / 2;
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 2) * s);
        int edge = Math.max(1, s / 2);

        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, CAP_H * s, r, Theme.fade(Theme.mix(Theme.EDGE, Theme.accent(), hover * 0.6f), alpha));
        Render2D.rect(g, x * s + edge, y * s + edge, w * s - 2 * edge, CAP_H * s - 2 * edge, Math.max(0, r - edge),
            Theme.fade(Theme.FIELD, alpha));
        Render2D.chevron(g, (right - 5) * s, midY * s, 0, Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, hover), alpha));
        Render2D.end(g);

        Text.draw(g, value, x + 4, y + (CAP_H - Text.CAP) / 2, Theme.fade(textColor, alpha));
        return w;
    }

    /** The search icon: a ring with a handle, centred on (x, y) in UI units. */
    static void magnifier(GuiGraphics g, int x, int y, int color, float a) {
        int s = Render2D.scale();
        int ring = Math.round(3.3f * s);
        int thick = Math.max(1, Math.round(0.9f * s));
        int icx = x * s - s / 2;
        int icy = y * s - s / 2;
        Render2D.pixels(g);
        Render2D.rect(g, icx - ring, icy - ring, ring * 2, ring * 2, ring, color);
        Render2D.rect(g, icx - ring + thick, icy - ring + thick, (ring - thick) * 2, (ring - thick) * 2, ring - thick, Theme.fade(Theme.FIELD, a));
        g.pose().pushMatrix();
        g.pose().translate(icx + ring * 0.62f, icy + ring * 0.62f);
        g.pose().rotate((float) Math.PI / 4);
        g.fill(0, -thick / 2, Math.round(2.8f * s), thick - thick / 2, color);
        g.pose().popMatrix();
        Render2D.end(g);
    }
}

package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import net.minecraft.client.gui.GuiGraphics;

/** Window chrome shared by every panel: shadow, glow, border, body and header. */
final class Panel {
    private Panel() {
    }

    /**
     * @param focus       0 to 1, drives the outer glow and border tint
     * @param headerHover 0 to 1
     * @param line        0 to 1, how much of the accent separator under the header shows
     */
    static void draw(GuiGraphics g, int x, int y, int w, int header, int body,
                     float alpha, float focus, float headerHover, float line) {
        int s = Render2D.scale();
        int px = x * s;
        int py = y * s;
        int pw = w * s;
        int ph = (header + body) * s;
        int hh = header * s;
        int r = Math.round(Theme.radius() * s);
        int ir = Math.max(0, r - 1);
        int accent = Theme.accent();
        float glow = Theme.glow();

        Render2D.pixels(g);

        Render2D.glow(g, px, py + 2 * s, pw, ph, r, 10 * s, Theme.fade(Theme.SHADOW, alpha));
        Render2D.glow(g, px, py, pw, ph, r, 8 * s, Theme.alpha(accent, focus * 120 * glow * alpha));

        Render2D.rect(g, px, py, pw, ph, r, Theme.fade(Theme.mix(Theme.BORDER, accent, focus * 0.3f), alpha));
        Render2D.rect(g, px + 1, py + 1, pw - 2, ph - 2, ir, Theme.fade(Theme.BODY, alpha));

        int headerColor = Theme.fade(Theme.mix(Theme.HEADER, Theme.HEADER_HOVER, headerHover), alpha);
        if (body * s <= r) {
            Render2D.rect(g, px + 1, py + 1, pw - 2, ph - 2, ir, headerColor);
        } else {
            // Rounded top, square bottom: draw it taller and cover the lower corners with body colour.
            Render2D.rect(g, px + 1, py + 1, pw - 2, hh - 1 + r, ir, headerColor);
            g.fill(px + 1, py + hh, px + pw - 1, py + hh + r, Theme.fade(Theme.BODY, alpha));

            int thickness = Math.max(1, s / 2);
            Render2D.fadeRight(g, px + 1, py + hh - thickness, pw - 2, thickness, Theme.alpha(accent, 230 * line * alpha));
            Render2D.glow(g, px + 4 * s, py + hh - thickness, pw / 2, thickness, 0, 5 * s,
                Theme.alpha(accent, 70 * glow * line * alpha));
        }

        Render2D.end(g);
    }
}

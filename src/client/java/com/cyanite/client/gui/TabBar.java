package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;

final class TabBar {
    static final int HEIGHT = 16;
    private static final int TAB_PAD = 9;

    private final String[] names;
    private final float[] hover;
    private final int[] tabX;
    private final int[] tabW;
    private int y;
    private Rect bounds = Rect.EMPTY;
    private int selected;
    private float indicatorX = Float.NaN;
    private float indicatorW;

    TabBar(String... names) {
        this.names = names;
        this.hover = new float[names.length];
        this.tabX = new int[names.length];
        this.tabW = new int[names.length];
    }

    int selected() {
        return selected;
    }

    void select(int index) {
        selected = index;
    }

    void render(GuiGraphics g, int centerX, int y, int mouseX, int mouseY, float dt, float alpha) {
        this.y = y;

        int total = 4;
        for (int i = 0; i < names.length; i++) {
            tabW[i] = Text.width(names[i]) + TAB_PAD * 2;
            total += tabW[i];
        }
        int x = centerX - total / 2;
        bounds = new Rect(x, y, total, HEIGHT);
        int cursor = x + 2;
        for (int i = 0; i < names.length; i++) {
            tabX[i] = cursor;
            cursor += tabW[i];
        }

        if (Float.isNaN(indicatorX)) {
            indicatorX = tabX[selected];
            indicatorW = tabW[selected];
        }
        indicatorX = Anim.approach(indicatorX, tabX[selected], dt, 18);
        indicatorW = Anim.approach(indicatorW, tabW[selected], dt, 18);

        int s = Render2D.scale();
        int r = Math.round(Theme.radius() * s);
        int accent = Theme.accent();

        Render2D.pixels(g);
        Render2D.glow(g, x * s, (y + 1) * s, total * s, HEIGHT * s, r, 8 * s, Theme.fade(Theme.SHADOW, alpha));
        Render2D.rect(g, x * s, y * s, total * s, HEIGHT * s, r, Theme.fade(Theme.BORDER, alpha));
        Render2D.rect(g, x * s + 1, y * s + 1, total * s - 2, HEIGHT * s - 2, Math.max(0, r - 1), Theme.fade(Theme.BODY, alpha));

        int ix = Math.round(indicatorX * s);
        int iw = Math.round(indicatorW * s);
        int iy = (y + 2) * s;
        int ih = (HEIGHT - 4) * s;
        int ir = Math.max(0, r - 2 * s);
        Render2D.glow(g, ix, iy, iw, ih, ir, 7 * s, Theme.alpha(accent, 55 * Theme.glow() * alpha));
        Render2D.rect(g, ix, iy, iw, ih, ir, Theme.alpha(accent, 50 * alpha));
        Render2D.rect(g, ix + 3 * s, iy + ih - Math.max(1, s / 2), iw - 6 * s, Math.max(1, s / 2), 0, Theme.alpha(accent, 220 * alpha));
        Render2D.end(g);

        for (int i = 0; i < names.length; i++) {
            boolean over = Page.inside(mouseX, mouseY, tabX[i], y, tabW[i], HEIGHT);
            hover[i] = Anim.approach(hover[i], over ? 1 : 0, dt, 16);
            if (over && i != selected) g.requestCursor(CursorTypes.POINTING_HAND);

            int color = i == selected ? Theme.accentText() : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, hover[i]);
            Text.draw(g, names[i], tabX[i] + TAB_PAD, y + (HEIGHT - Text.CAP) / 2, Theme.fade(color, alpha));
        }
    }

    Rect bounds() {
        return bounds;
    }

    /** @return the tab under the mouse, or -1. */
    int tabAt(double mouseX, double mouseY) {
        for (int i = 0; i < names.length; i++) {
            if (Page.inside(mouseX, mouseY, tabX[i], y, tabW[i], HEIGHT)) return i;
        }
        return -1;
    }
}

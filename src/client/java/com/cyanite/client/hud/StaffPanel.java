package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.donut.StaffList;
import com.cyanite.client.system.Modules;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Staff List's panel: each staff member online with their star, in tab order. */
public class StaffPanel extends HudElement {
    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int STAR = Text.CAP + 2;
    private static final String TITLE = "Staff";
    private static final String NONE = "No staff online";
    private static final String OFF = "Turn on Staff List";

    private final List<StaffList.Staff> rows = new ArrayList<>();
    private boolean shown;
    private String empty;

    public StaffPanel() {
        super("Staff List", true, Horizontal.RIGHT, Vertical.BOTTOM, 4, 4);
    }

    @Override
    public void update(float dt, boolean editing) {
        StaffList module = Modules.get(StaffList.class);
        boolean on = module != null && module.isEnabled();
        rows.clear();
        if (on) rows.addAll(module.online());
        shown = editing || on && module.applies();
        empty = on ? NONE : OFF;

        int w = Text.width(TITLE) + 8 + Text.width(Integer.toString(rows.size()));
        if (rows.isEmpty()) w = Math.max(w, Text.width(empty));
        for (StaffList.Staff s : rows) w = Math.max(w, STAR + 4 + Text.width(s.name()));
        int lines = Math.max(1, rows.size());
        width = shown ? w + PAD * 2 : 0;
        height = shown ? PAD * 2 + Text.CAP + 5 + lines * LINE - (LINE - Text.CAP) : 0;
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        if (!shown) return;
        int s = Render2D.scale();
        int accent = Theme.accent();
        int r = Math.round(Theme.radius() * s);

        // The same chip as the watermark and region map.
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s + s, width * s, height * s, r, 7 * s, Theme.fade(Theme.SHADOW, alpha * 0.8f));
        Render2D.glow(g, x * s, y * s, width * s, height * s, r, 7 * s, Theme.alpha(accent, 35 * Theme.glow() * alpha));
        Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, alpha));
        Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xE8101217, alpha));
        int line = Math.max(1, s / 2);
        Render2D.fadeRight(g, x * s + 1, (y + height) * s - line - 1, width * s - 2, line, Theme.alpha(accent, 200 * alpha));
        Render2D.end(g);

        int tx = x + PAD;
        int ty = y + PAD;
        Text.draw(g, TITLE, tx, ty, Theme.fade(Theme.accentText(), alpha), false);
        String count = Integer.toString(rows.size());
        Text.draw(g, count, x + width - PAD - Text.width(count), ty, Theme.fade(Theme.TEXT_DIM, alpha), false);
        ty += Text.CAP + 5;

        if (rows.isEmpty()) {
            Text.draw(g, empty, tx, ty, Theme.fade(Theme.TEXT_FAINT, alpha), false);
            return;
        }
        for (StaffList.Staff staff : rows) {
            Render2D.pixels(g);
            star(g, (tx + STAR / 2.0) * s, (ty + Text.CAP / 2.0) * s, STAR / 2.0 * s, Theme.fade(staff.star(), alpha));
            Render2D.end(g);
            Text.draw(g, staff.name(), tx + STAR + 4, ty, Theme.fade(Theme.TEXT, alpha), false);
            ty += LINE;
        }
    }

    /** A five-pointed star centred on (cx, cy), {@code r} pixels to each point. */
    private static void star(GuiGraphics g, double cx, double cy, double r, int color) {
        double[][] p = new double[10][];
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36);
            double d = i % 2 == 0 ? r : r * 0.45;
            p[i] = new double[] {cx + Math.cos(a) * d, cy + Math.sin(a) * d};
        }
        RegionMap.fill(g, p, color);
    }
}

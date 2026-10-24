package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What Cyanite costs: frames a second, the longest frame (a stutter shows there even when the
 * average is fine), and the modules taking the most of the game's time, as a share of each second.
 */
public class PerformancePanel extends HudElement {
    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int SHOWN = 6;
    private static final String TITLE = "Performance";

    private final List<String[]> rows = new ArrayList<>();
    private String head = "";
    private String total = "";
    private float refresh;

    public PerformancePanel() {
        super("Performance", false, Horizontal.LEFT, Vertical.CENTER, 4, 0);
    }

    @Override
    public void update(float dt, boolean editing) {
        // The numbers change once a second; reading them twice a second is plenty.
        refresh -= dt;
        if (refresh <= 0 || rows.isEmpty() && head.isEmpty()) {
            refresh = 0.5f;
            rows.clear();
            List<Module> busy = new ArrayList<>();
            float sum = 0;
            for (Module m : Modules.getAll()) {
                if (!m.isEnabled() && m.cost() < 0.05f) continue;
                sum += m.cost();
                if (m.cost() >= 0.05f) busy.add(m);
            }
            busy.sort(Comparator.comparingDouble(Module::cost).reversed());
            for (Module m : busy.subList(0, Math.min(SHOWN, busy.size()))) {
                rows.add(new String[] {m.name, String.format(Locale.ROOT, "%.1f%%", m.cost())});
            }
            head = Modules.fps() + " fps, worst frame " + String.format(Locale.ROOT, "%.0f ms", Modules.worstFrameMs());
            total = String.format(Locale.ROOT, "%.1f%%", sum);
        }
        int w = Math.max(Text.width(TITLE) + 8 + Text.width(total), Text.width(head));
        for (String[] r : rows) w = Math.max(w, Text.width(r[0]) + 10 + Text.width(r[1]));
        if (rows.isEmpty()) w = Math.max(w, Text.width("Nothing costs anything"));
        width = w + PAD * 2;
        height = PAD * 2 + Text.CAP + 5 + LINE + Math.max(1, rows.size()) * LINE - (LINE - Text.CAP);
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        int r = Math.round(Theme.radius() * s);

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s + s, width * s, height * s, r, 7 * s, Theme.fade(Theme.SHADOW, alpha * 0.8f));
        Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, alpha));
        Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xE8101217, alpha));
        int line = Math.max(1, s / 2);
        Render2D.fadeRight(g, x * s + 1, (y + height) * s - line - 1, width * s - 2, line, Theme.alpha(accent, 200 * alpha));
        Render2D.end(g);

        int tx = x + PAD;
        int ty = y + PAD;
        Text.draw(g, TITLE, tx, ty, Theme.fade(Theme.accentText(), alpha), false);
        Text.draw(g, total, x + width - PAD - Text.width(total), ty, Theme.fade(Theme.TEXT_DIM, alpha), false);
        ty += Text.CAP + 5;
        Text.draw(g, head, tx, ty, Theme.fade(Modules.worstFrameMs() > 50 ? Theme.DANGER : Theme.TEXT_DIM, alpha), false);
        ty += LINE;
        if (rows.isEmpty()) {
            Text.draw(g, "Nothing costs anything", tx, ty, Theme.fade(Theme.TEXT_FAINT, alpha), false);
            return;
        }
        for (String[] row : rows) {
            Text.draw(g, row[0], tx, ty, Theme.fade(Theme.TEXT, alpha), false);
            Text.draw(g, row[1], x + width - PAD - Text.width(row[1]), ty, Theme.fade(Theme.TEXT_DIM, alpha), false);
            ty += LINE;
        }
    }
}

package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** A chip slides in when a module's switched on or off: its name, and a green "on" or red "off". */
public class ToggleAlerts extends HudElement {
    private static final float SHOW = 2.0f;
    private static final float SLIDE = 0.18f;
    private static final float FADE = 0.4f;
    private static final int CHIP = 15;
    private static final int GAP = 3;
    private static final int PAD = 6;
    private static final int ON = 0xFF5BE07A;
    private static final int OFF = 0xFFFF5D62;

    private static final class Alert {
        final String name;
        final boolean on;
        float age;

        Alert(String name, boolean on) {
            this.name = name;
            this.on = on;
        }
    }

    private static final List<Alert> ALERTS = new ArrayList<>();
    private static ToggleAlerts instance;

    public ToggleAlerts() {
        super("Toggle Alerts", true, Horizontal.RIGHT, Vertical.CENTER, 4, 0);
        instance = this;
    }

    /** A module was switched (from Module.setEnabled). Nothing before you're in a world, or while hidden. */
    public static void onToggle(Module module, boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (instance == null || !instance.isEnabled() || mc.player == null || mc.level == null || Stealth.active()) return;
        ALERTS.removeIf(a -> a.name.equals(module.name));
        ALERTS.add(new Alert(module.name, on));
        while (ALERTS.size() > 5) ALERTS.remove(0);
    }

    @Override
    public void update(float dt, boolean editing) {
        for (Alert a : ALERTS) a.age += dt;
        ALERTS.removeIf(a -> a.age > SHOW);
        int w = 0;
        for (Alert a : shown(editing)) w = Math.max(w, chipWidth(a));
        int n = shown(editing).size();
        width = n == 0 ? 0 : w;
        height = n == 0 ? 0 : n * CHIP + (n - 1) * GAP;
    }

    private List<Alert> shown(boolean editing) {
        if (!ALERTS.isEmpty() || !editing) return ALERTS;
        return List.of(new Alert("Kill Aura", true), new Alert("Speed", false));
    }

    private static int chipWidth(Alert a) {
        return PAD + 4 + Text.width(a.name) + 6 + Text.width(a.on ? "on" : "off") + PAD;
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        List<Alert> list = shown(editing);
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 4f) * s);
        int cy = y;
        for (Alert a : list) {
            // Slides in from the right, holds, then fades.
            float in = Math.min(1, a.age / SLIDE);
            float eased = 1 - (1 - in) * (1 - in) * (1 - in);
            float out = a.age > SHOW - FADE ? (SHOW - a.age) / FADE : 1;
            float fade = alpha * Math.max(0, Math.min(1, out)) * (editing && ALERTS.isEmpty() ? 1 : eased);
            int cw = chipWidth(a);
            int cx = x + width - cw + Math.round((1 - eased) * (cw + 8));
            int tone = a.on ? ON : OFF;
            Render2D.pixels(g);
            Render2D.glow(g, cx * s, cy * s, cw * s, CHIP * s, r, 6 * s, Theme.alpha(tone, 40 * Theme.glow() * fade));
            Render2D.rect(g, cx * s, cy * s, cw * s, CHIP * s, r, Theme.fade(Theme.BORDER, fade));
            Render2D.rect(g, cx * s + 1, cy * s + 1, cw * s - 2, CHIP * s - 2, Math.max(0, r - 1), Theme.fade(0xEC101217, fade));
            Render2D.rect(g, cx * s + 3 * s, cy * s + 3 * s, Math.max(1, s), CHIP * s - 6 * s, 0, Theme.fade(tone, fade));
            Render2D.end(g);
            int ty = cy + (CHIP - Text.CAP) / 2;
            int tx = cx + PAD + 4;
            Text.draw(g, a.name, tx, ty, Theme.fade(Theme.TEXT, fade), false);
            Text.draw(g, a.on ? "on" : "off", tx + Text.width(a.name) + 6, ty, Theme.fade(tone, fade), false);
            cy += CHIP + GAP;
        }
    }
}

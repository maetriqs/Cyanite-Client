package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.hud.HudElement;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * HUD editor. The elements are drawn live, in HUD units, and can be dragged anywhere;
 * the panel switches them on and off and holds their settings.
 */
final class HudPage extends Page {
    private static final int W = 196;
    private static final int HEADER = 18;
    private static final int ROW = 15;
    private static final int PAD = 3;
    private static final int GENERAL_LABEL = 14;
    private static final int FOOTER = 14;
    private static final String RESET = "Reset layout";

    private final Map<HudElement, Row> rows = new IdentityHashMap<>();
    private final SettingsList general = new SettingsList(Hud.SETTINGS);

    private int x;
    private int y;
    private int panelHeight;
    private float resetHover;

    private HudElement dragged;
    private double grabX;
    private double grabY;
    private SettingsList editing;

    private static final class Row {
        final SettingsList settings;
        float hover;
        float on;
        float expand;
        boolean open;

        Row(SettingsList settings) {
            this.settings = settings;
        }
    }

    private Row row(HudElement element) {
        return rows.computeIfAbsent(element, e -> new Row(new SettingsList(e.settings())));
    }

    /** GUI units to HUD units. */
    private static double toHud(double v) {
        return v * Render2D.scale() / Hud.unit();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        renderElements(g, mouseX, mouseY, dt, alpha);
        g.nextStratum();
        renderPanel(g, mouseX, mouseY, dt, alpha * entrance());
    }

    private void renderElements(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        int guiUnit = Render2D.scale();
        int hudMouseX = (int) toHud(mouseX);
        int hudMouseY = (int) toHud(mouseY);
        boolean overPanel = inside(mouseX, mouseY, x, y, W, panelHeight);
        HudElement hovered = dragged != null ? dragged : overPanel ? null : elementAt(hudMouseX, hudMouseY);

        // Back out of the click GUI's units into Minecraft's, then let the HUD apply its own.
        g.pose().pushMatrix();
        g.pose().scale((float) Minecraft.getInstance().getWindow().getGuiScale() / guiUnit);
        Hud.begin(g);

        int width = Hud.screenWidth();
        int height = Hud.screenHeight();
        int s = Render2D.scale();

        for (HudElement element : Hud.ELEMENTS) {
            element.update(dt, true);
            element.place(width, height);
            // Elements stay at full strength so they match the real HUD; disabled ones are ghosted.
            element.draw(g, element.isEnabled() ? 1 : 0.35f * alpha, true);

            boolean hot = element == hovered;
            int ox = (element.x - 2) * s;
            int oy = (element.y - 2) * s;
            int ow = (element.width() + 4) * s;
            int oh = (element.height() + 4) * s;
            int color = hot ? Theme.alpha(Theme.accent(), 220 * alpha) : Theme.alpha(Theme.TEXT_DIM, 70 * alpha);

            Render2D.pixels(g);
            if (hot) Render2D.glow(g, ox, oy, ow, oh, 2 * s, 6 * s, Theme.alpha(Theme.accent(), 50 * Theme.glow() * alpha));
            outline(g, ox, oy, ow, oh, Math.max(1, s / 2), color);
            Render2D.end(g);

            if (hot) {
                g.requestCursor(dragged != null ? CursorTypes.RESIZE_ALL : CursorTypes.POINTING_HAND);
                int labelY = element.y - 4 - Text.CAP >= 0 ? element.y - 4 - Text.CAP : element.y + element.height() + 4;
                Text.draw(g, element.name, element.x, labelY, Theme.fade(Theme.accentText(), alpha), true);
            }
        }

        Hud.end(g);
        g.pose().popMatrix();
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int t, int color) {
        g.fill(x, y, x + w, y + t, color);
        g.fill(x, y + h - t, x + w, y + h, color);
        g.fill(x, y + t, x + t, y + h - t, color);
        g.fill(x + w - t, y + t, x + w, y + h - t, color);
    }

    private HudElement elementAt(double hudX, double hudY) {
        for (int i = Hud.ELEMENTS.size() - 1; i >= 0; i--) {
            HudElement element = Hud.ELEMENTS.get(i);
            if (inside(hudX, hudY, element.x - 2, element.y - 2, element.width() + 4, element.height() + 4)) return element;
        }
        return null;
    }

    private int bodyHeight() {
        int h = PAD;
        for (HudElement element : Hud.ELEMENTS) {
            Row row = row(element);
            h += ROW + Math.round(row.settings.height() * row.expand);
        }
        return h + GENERAL_LABEL + general.height() + FOOTER;
    }

    private void renderPanel(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        x = (width - W) / 2;
        y = TOP + Math.round((1 - entrance()) * -6);

        int body = bodyHeight();
        panelHeight = HEADER + body;
        boolean hovered = dragged == null && inside(mouseX, mouseY, x, y, W, panelHeight);

        Panel.draw(g, x, y, W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        bounds = new Rect(x, y, W, HEADER + body);
        Text.draw(g, "HUD", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        int resetX = x + W - 8 - Text.width(RESET);
        boolean overReset = hovered && inside(mouseX, mouseY, resetX - 3, y, Text.width(RESET) + 6, HEADER);
        resetHover = Anim.approach(resetHover, overReset ? 1 : 0, dt, 16);
        if (overReset) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, RESET, resetX, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.accentText(), resetHover), a));

        int s = Render2D.scale();
        int rowY = y + HEADER + PAD;

        for (HudElement element : Hud.ELEMENTS) {
            Row row = row(element);
            boolean over = hovered && editing == null && inside(mouseX, mouseY, x + 3, rowY, W - 6, ROW);
            row.hover = Anim.approach(row.hover, over ? 1 : 0, dt, 16);
            row.on = Anim.approach(row.on, element.isEnabled() ? 1 : 0, dt, 14);
            row.expand = Anim.approach(row.expand, row.open ? 1 : 0, dt, 16);
            if (over) g.requestCursor(CursorTypes.POINTING_HAND);

            Render2D.pixels(g);
            Render2D.rect(g, (x + 3) * s, rowY * s, (W - 6) * s, ROW * s, 2 * s, Theme.alpha(0xFFFFFF, row.hover * 12 * a));
            if (!row.settings.isEmpty()) {
                Render2D.chevron(g, chevronX() * s, rowY * s + ROW * s / 2, (float) (-Math.PI / 2) * (1 - row.expand),
                    Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, Math.max(row.hover, row.expand)), a));
            }
            Render2D.end(g);

            int labelColor = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, row.on), Theme.TEXT, row.hover);
            Text.draw(g, element.name, x + 9, rowY + (ROW - Text.CAP) / 2, Theme.fade(labelColor, a));
            Widgets.toggle(g, x + W - 8 - Widgets.TOGGLE_W, rowY + (ROW - Widgets.TOGGLE_H) / 2, row.on, a);

            int settingsHeight = Math.round(row.settings.height() * row.expand);
            if (settingsHeight > 0) {
                int top = rowY + ROW;
                g.enableScissor(x, top, x + W, top + settingsHeight);

                Render2D.pixels(g);
                g.fill((x + 3) * s, top * s, (x + W - 3) * s, (top + settingsHeight) * s, Theme.fade(0x40000000, a));
                g.fill((x + 4) * s, top * s, (x + 4) * s + Math.max(1, s / 2), (top + settingsHeight) * s, Theme.alpha(Theme.accent(), 90 * a));
                Render2D.end(g);

                row.settings.render(g, x + 6, top, W - 12, mouseX, mouseY, dt, a, hovered && row.expand > 0.98f);
                g.disableScissor();
            }
            rowY += ROW + settingsHeight;
        }

        Render2D.pixels(g);
        g.fill((x + 8) * s, (rowY + 4) * s, (x + W - 8) * s, (rowY + 4) * s + Math.max(1, s / 2), Theme.fade(Theme.BORDER, a));
        Render2D.end(g);
        Text.draw(g, "General", x + 8, rowY + 7, Theme.fade(Theme.TEXT_FAINT, a));
        rowY += GENERAL_LABEL;

        general.render(g, x + 4, rowY + 2, W - 8, mouseX, mouseY, dt, a, hovered);
        rowY += general.height();

        Text.draw(g, "Drag elements to move them", x + 8, rowY + 4, Theme.fade(Theme.TEXT_FAINT, a));
    }

    private int chevronX() {
        return x + W - 8 - Widgets.TOGGLE_W - 9;
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inside(mouseX, mouseY, x, y, W, panelHeight)) {
            clickPanel(mouseX, mouseY, button);
            return true;
        }

        double hx = toHud(mouseX);
        double hy = toHud(mouseY);
        HudElement element = elementAt(hx, hy);
        if (element == null) return false;

        if (button == 0) {
            dragged = element;
            grabX = hx - element.x;
            grabY = hy - element.y;
        } else if (button == 1) {
            element.toggle();
        }
        return true;
    }

    private void clickPanel(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, x + W - 11 - Text.width(RESET), y, Text.width(RESET) + 6, HEADER)) {
            for (HudElement element : Hud.ELEMENTS) element.resetPosition();
            return;
        }

        int rowY = y + HEADER + PAD;
        for (HudElement element : Hud.ELEMENTS) {
            Row row = row(element);

            if (inside(mouseX, mouseY, x + 3, rowY, W - 6, ROW)) {
                boolean onChevron = mouseX >= chevronX() - 6 && mouseX < chevronX() + 6;
                if (!row.settings.isEmpty() && (button == 1 || onChevron)) row.open = !row.open;
                else if (button == 0) element.toggle();
                return;
            }

            int settingsHeight = Math.round(row.settings.height() * row.expand);
            if (row.open && row.expand > 0.98f && inside(mouseX, mouseY, x, rowY + ROW, W, settingsHeight)) {
                if (row.settings.mouseClicked(mouseX, mouseY, button) && row.settings.isDragging()) editing = row.settings;
                return;
            }
            rowY += ROW + settingsHeight;
        }

        if (general.mouseClicked(mouseX, mouseY, button) && general.isDragging()) editing = general;
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        if (editing != null) {
            editing.mouseDragged(mouseX);
        } else if (dragged != null) {
            dragged.moveTo((int) Math.round(toHud(mouseX) - grabX), (int) Math.round(toHud(mouseY) - grabY),
                Hud.screenWidth(), Hud.screenHeight());
        }
    }

    @Override
    void mouseReleased(int button) {
        if (editing != null) editing.mouseReleased();
        editing = null;
        dragged = null;
    }
}

package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.Setting;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A floating window with every setting of one module, for modules with too many to
 * show inline. Long lists are split into two columns of roughly equal height.
 */
final class SettingsWindow {
    static final int COLUMN = 150;
    private static final int GAP = 6;
    private static final int HEADER = 18;
    private static final int DESCRIPTION = 13;
    private static final int PAD = 4;
    private static final int TWO_COLUMNS_FROM = 220;

    final Module module;
    int x;
    int y;

    private final List<SettingsList> columns = new ArrayList<>();
    private float appear;
    private boolean closing;
    private float focus;
    private float on = Float.NaN;
    private float closeHover;

    private boolean dragging;
    private double grabX;
    private double grabY;
    private SettingsList editing;

    SettingsWindow(Module module, int x, int y) {
        this.module = module;
        this.x = x;
        this.y = y;

        List<Setting<?>> settings = module.settings();
        int total = 0;
        for (Setting<?> setting : settings) total += SettingsList.heightOf(setting);

        if (total < TWO_COLUMNS_FROM) {
            columns.add(new SettingsList(settings));
            return;
        }

        // Fill the first column up to half the total height, the rest goes in the second.
        int split = 0;
        int height = 0;
        while (split < settings.size() && height + SettingsList.heightOf(settings.get(split)) / 2 < total / 2) {
            height += SettingsList.heightOf(settings.get(split));
            split++;
        }
        columns.add(new SettingsList(settings.subList(0, split)));
        columns.add(new SettingsList(settings.subList(split, settings.size())));
    }

    int width() {
        return columns.size() * COLUMN + (columns.size() - 1) * GAP + PAD * 2;
    }

    private int bodyHeight() {
        int tallest = 0;
        for (SettingsList column : columns) tallest = Math.max(tallest, column.height());
        return DESCRIPTION + tallest + PAD * 2;
    }

    int height() {
        return HEADER + bodyHeight();
    }

    boolean isOver(double mouseX, double mouseY) {
        return !closing && Page.inside(mouseX, mouseY, x, y, width(), height());
    }

    boolean isGone() {
        return closing && appear < 0.02f;
    }

    void close() {
        closing = true;
        if (editing != null) editing.mouseReleased();
        editing = null;
        dragging = false;
    }

    void reopen() {
        closing = false;
    }

    void clamp(int screenWidth, int screenHeight) {
        x = Math.max(2, Math.min(x, screenWidth - width() - 2));
        y = Math.max(2, Math.min(y, screenHeight - height() - 2));
    }

    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha, boolean hovered) {
        appear = Anim.approach(appear, closing ? 0 : 1, dt, closing ? 22 : 16);
        float a = alpha * Anim.easeOut(appear);
        int slide = Math.round((1 - Anim.easeOut(appear)) * 6);

        float target = module.isEnabled() ? 1 : 0;
        on = Float.isNaN(on) ? target : Anim.approach(on, target, dt, 14);
        focus = Anim.approach(focus, dragging ? 1 : hovered ? 0.7f : 0.45f, dt, 9);

        int top = y + slide;
        int w = width();
        int closeX = x + w - 14;
        int toggleX = closeX - 6 - Widgets.TOGGLE_W;

        boolean overClose = hovered && Page.inside(mouseX, mouseY, closeX - 3, top, 14, HEADER);
        boolean overToggle = hovered && Page.inside(mouseX, mouseY, toggleX - 2, top, Widgets.TOGGLE_W + 4, HEADER);
        boolean overHeader = hovered && !overClose && !overToggle && Page.inside(mouseX, mouseY, x, top, w, HEADER);
        closeHover = Anim.approach(closeHover, overClose ? 1 : 0, dt, 16);

        if (dragging) g.requestCursor(CursorTypes.RESIZE_ALL);
        else if (overClose || overToggle) g.requestCursor(CursorTypes.POINTING_HAND);
        else if (overHeader) g.requestCursor(CursorTypes.RESIZE_ALL);

        Panel.draw(g, x, top, w, HEADER, bodyHeight(), a, focus, overHeader || dragging ? 1 : 0, 1);

        Text.draw(g, module.name, x + 8, top + (HEADER - Text.CAP) / 2, Theme.fade(module.anticheatDownOnly() ? Theme.DANGER : Theme.TEXT, a));
        Widgets.toggle(g, toggleX, top + (HEADER - Widgets.TOGGLE_H) / 2, on, a);
        drawCross(g, closeX + 4, top + HEADER / 2, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.DANGER, closeHover), a));

        if (module.anticheatDownOnly()) {
            String warning = "Anticheat down only  ";
            Text.draw(g, warning, x + 8, top + HEADER + 4, Theme.fade(Theme.DANGER, a));
            Text.draw(g, fit(module.description, w - 16 - Text.width(warning)), x + 8 + Text.width(warning), top + HEADER + 4,
                Theme.fade(Theme.TEXT_FAINT, a));
        } else {
            Text.draw(g, fit(module.description, w - 16), x + 8, top + HEADER + 4, Theme.fade(Theme.TEXT_FAINT, a));
        }

        int columnY = top + HEADER + DESCRIPTION + PAD;
        boolean interactive = hovered && !dragging && appear > 0.9f;
        for (int i = 0; i < columns.size(); i++) {
            int columnX = x + PAD + i * (COLUMN + GAP);
            columns.get(i).render(g, columnX, columnY, COLUMN, mouseX, mouseY, dt, a, interactive);

            if (i > 0) {
                int s = Render2D.scale();
                Render2D.pixels(g);
                int lineX = (columnX - GAP / 2) * s;
                g.fill(lineX, columnY * s, lineX + Math.max(1, s / 2), (columnY + columns.get(i - 1).height()) * s,
                    Theme.fade(Theme.BORDER, a));
                Render2D.end(g);
            }
        }
    }

    private static String fit(String text, int maxWidth) {
        if (Text.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "...") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut.stripTrailing() + "...";
    }

    static void drawCross(GuiGraphics g, int cx, int cy, int color) {
        int s = Render2D.scale();
        int arm = Math.round(3.5f * s);
        int thickness = Math.max(2, Math.round(s * 0.75f));

        Render2D.pixels(g);
        for (float angle : new float[]{(float) Math.PI / 4, (float) -Math.PI / 4}) {
            g.pose().pushMatrix();
            g.pose().translate(cx * s, cy * s);
            g.pose().rotate(angle);
            g.fill(-arm, -thickness / 2, arm, thickness - thickness / 2, color);
            g.pose().popMatrix();
        }
        Render2D.end(g);
    }

    /** @return true if the click landed on this window. */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isOver(mouseX, mouseY)) return false;

        int w = width();
        int closeX = x + w - 14;
        int toggleX = closeX - 6 - Widgets.TOGGLE_W;

        if (Page.inside(mouseX, mouseY, x, y, w, HEADER)) {
            if (button != 0) return true;
            if (Page.inside(mouseX, mouseY, closeX - 3, y, 14, HEADER)) {
                close();
            } else if (Page.inside(mouseX, mouseY, toggleX - 2, y, Widgets.TOGGLE_W + 4, HEADER)) {
                module.toggle();
            } else {
                dragging = true;
                grabX = mouseX - x;
                grabY = mouseY - y;
            }
            return true;
        }

        for (SettingsList column : columns) {
            if (column.mouseClicked(mouseX, mouseY, button)) {
                if (column.isDragging()) editing = column;
                break;
            }
        }
        return true;
    }

    void mouseDragged(double mouseX, double mouseY) {
        if (editing != null) {
            editing.mouseDragged(mouseX);
        } else if (dragging) {
            x = (int) Math.round(mouseX - grabX);
            y = (int) Math.round(mouseY - grabY);
        }
    }

    void mouseReleased() {
        if (editing != null) editing.mouseReleased();
        editing = null;
        dragging = false;
    }
}

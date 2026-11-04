package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.system.setting.Setting;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.Mirror;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the tool tabs (Area Miner, Schematic Builder) share: panels of labelled rows, buttons and
 * text buttons, a progress bar and a status dot, a note that fades, and the tool's settings in a
 * scrolling panel of their own.
 */
abstract class ToolPage extends Page {
    static final int SETTINGS_W = 168;
    static final int GAP = 8;
    static final int HEADER = 18;
    static final int PAD = 6;
    static final int ROW = 14;
    static final int BUTTON_H = 16;
    static final int LIST_ROW = 16;
    private static final float STATUS_TIME = 4;

    protected final List<Button> buttons = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private SettingsList settings;

    private String note = "";
    private boolean noteError;
    private float noteLeft;
    private int settingsScroll;
    private int settingsX;
    private int settingsY;
    private int settingsH;
    private boolean draggingSetting;

    protected record Button(String key, int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    /** The tool's settings, shown in the right-hand panel. */
    protected abstract List<Setting<?>> toolSettings();

    /** Call at the top of render(): clears last frame's buttons and runs the note's clock. */
    protected void frame(float dt) {
        buttons.clear();
        noteLeft = Math.max(0, noteLeft - dt);
        if (settings == null) settings = new SettingsList(toolSettings());
    }

    // ---- Settings

    protected Rect renderSettings(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        int content = settings.height();
        int maxView = Math.max(80, height - y - HEADER - PAD * 2 - 12);
        int viewH = Math.min(content, maxView);
        settingsScroll = Math.max(0, Math.min(settingsScroll, content - viewH));
        int body = PAD + viewH + PAD;
        boolean hovered = inside(mouseX, mouseY, x, y, SETTINGS_W, HEADER + body);
        Panel.draw(g, x, y, SETTINGS_W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, "Settings", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        settingsX = x + 4;
        settingsY = y + HEADER + PAD;
        settingsH = viewH;
        int s = Render2D.scale();
        boolean overList = inside(mouseX, mouseY, settingsX, settingsY, SETTINGS_W - 8, viewH);
        g.enableScissor(x, settingsY, x + SETTINGS_W, settingsY + viewH);
        settings.render(g, settingsX, settingsY - settingsScroll, SETTINGS_W - 8, mouseX, overList || draggingSetting ? mouseY : -10000, dt, a, true);
        g.disableScissor();
        if (content > viewH) {
            int thumbH = Math.max(10, viewH * viewH / content);
            int thumbY = settingsY + (viewH - thumbH) * settingsScroll / Math.max(1, content - viewH);
            int w = Math.max(2, s);
            Render2D.pixels(g);
            Render2D.rect(g, (x + SETTINGS_W - 2) * s - w, thumbY * s, w, thumbH * s, w / 2, Theme.fade(Theme.TEXT_FAINT, a));
            Render2D.end(g);
        }
        return new Rect(x, y, SETTINGS_W, HEADER + body);
    }

    protected boolean overSettings(double mouseX, double mouseY) {
        return inside(mouseX, mouseY, settingsX, settingsY, SETTINGS_W - 8, settingsH);
    }

    // ---- Small pieces

    protected void row(GuiGraphics g, int x, int w, int y, String label, String value, int valueColor, float a) {
        int textY = y + (ROW - Text.CAP) / 2;
        Text.draw(g, label, x + PAD + 2, textY, Theme.fade(Theme.TEXT_DIM, a));
        String shown = fit(value, w - PAD * 2 - 8 - Text.width(label) - 10);
        Text.draw(g, shown, x + w - PAD - 2 - Text.width(shown), textY, Theme.fade(valueColor, a));
    }

    protected void renderNote(GuiGraphics g, int x, int w, int y, String idle, float a) {
        if (noteLeft > 0) {
            Text.draw(g, fit(note, w - PAD * 2 - 4), x + PAD + 2, y, Theme.fade(noteError ? Theme.DANGER : Theme.TEXT_DIM, a * Math.min(1, noteLeft / 0.4f)));
        } else {
            Text.draw(g, fit(idle, w - PAD * 2 - 4), x + PAD + 2, y, Theme.fade(Theme.TEXT_FAINT, a));
        }
    }

    protected void setNote(String text, boolean error) {
        note = text;
        noteError = error;
        noteLeft = STATUS_TIME;
    }

    protected void progressBar(GuiGraphics g, int x, int y, int w, double progress, float a) {
        int s = Render2D.scale();
        int h = 8 * s;
        int r = h / 2;
        int filled = (int) Math.round(w * s * Math.max(0, Math.min(1, progress)));
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h, r, Theme.fade(Theme.TRACK, a));
        if (filled > 0) {
            Render2D.glow(g, x * s, y * s, Math.max(filled, h), h, r, 5 * s, Theme.alpha(accent, 70 * Theme.glow() * a));
            Render2D.rect(g, x * s, y * s, Math.max(filled, h), h, r, Theme.fade(accent, a));
        }
        Render2D.end(g);
    }

    protected void dot(GuiGraphics g, int cx, int cy, int color, boolean glow, float a) {
        int s = Render2D.scale();
        int d = 4 * s;
        Render2D.pixels(g);
        if (glow) Render2D.glow(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, 5 * s, Theme.alpha(color, 140 * Theme.glow() * a));
        Render2D.rect(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, Theme.fade(color, a));
        Render2D.end(g);
    }

    protected void button(GuiGraphics g, String key, int x, int y, int w, int h, String label, boolean enabled,
                          int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        float hv = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.button(g, x, y, w, h, label, hv, enabled, a);
        if (enabled) buttons.add(new Button(key, x, y, w, h, action, null));
    }

    protected void textButton(GuiGraphics g, String key, String label, int bx, int by, int mouseX, int mouseY,
                              float dt, float a, int color, int hoverColor, Runnable action) {
        int w = Text.width(label);
        boolean over = inside(mouseX, mouseY, bx - 3, by - 3, w + 6, Text.CAP + 6);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, label, bx, by, Theme.fade(Theme.mix(color, hoverColor, h), a));
        buttons.add(new Button(key, bx - 3, by - 3, w + 6, Text.CAP + 6, action, null));
    }

    protected float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    /** One Litematica placement in a list: its name, how it's turned, its size; highlighted when it's the one picked. */
    protected void placementRow(GuiGraphics g, int x, int w, int rowY, Litematica.Placement p, boolean chosen,
                                int mouseX, int mouseY, float dt, float a, Runnable choose) {
        int s = Render2D.scale();
        String key = "pl:" + p.key() + p.origin().asLong();
        boolean over = inside(mouseX, mouseY, x + 3, rowY, w - 6, LIST_ROW);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        Render2D.pixels(g);
        Render2D.rect(g, (x + 3) * s, rowY * s, (w - 6) * s, LIST_ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        if (chosen) {
            int accent = Theme.accent();
            int barW = Math.max(2, Math.round(1.5f * s));
            int barY = (rowY + 4) * s;
            int barH = (LIST_ROW - 8) * s;
            Render2D.fadeRight(g, (x + 3) * s, rowY * s, (w - 6) * s, LIST_ROW * s, Theme.alpha(accent, 50 * a));
            Render2D.glow(g, (x + 3) * s, barY, barW, barH, barW / 2, 5 * s, Theme.alpha(accent, 160 * Theme.glow() * a));
            Render2D.rect(g, (x + 3) * s, barY, barW, barH, barW / 2, Theme.fade(accent, a));
        }
        Render2D.end(g);

        Region b = p.bounds();
        String size = b.sizeX() + "×" + b.sizeY() + "×" + b.sizeZ();
        String turn = turn(p);
        String right = turn.isEmpty() ? size : turn + "  " + size;
        int textY = rowY + (LIST_ROW - Text.CAP) / 2;
        int rightX = x + w - 10 - Text.width(right);
        String name = fit(p.name() + (p.enabled() ? "" : " (hidden)"), rightX - 8 - (x + 10));
        Text.draw(g, name, x + 10, textY, Theme.fade(chosen ? Theme.accentText() : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        Text.draw(g, right, rightX, textY, Theme.fade(Theme.TEXT_FAINT, a));
        buttons.add(new Button(key, x + 3, rowY, w - 6, LIST_ROW, choose, null));
    }

    protected static String turn(Litematica.Placement p) {
        String rotation = switch (p.rotation()) {
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
            default -> "";
        };
        String mirror = p.mirror() == Mirror.NONE ? "" : "mirrored";
        return (rotation + (rotation.isEmpty() || mirror.isEmpty() ? "" : " ") + mirror).trim();
    }

    protected static String fit(String text, int maxWidth) {
        if (Text.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "…") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    protected static String duration(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%dh %02dm", h, m) : String.format(Locale.ROOT, "%dm %02ds", m, s);
    }

    // ---- Input

    /** A click on one of this frame's buttons, or in the settings. @return whether it was taken. */
    protected boolean clickButtonsOrSettings(double mouseX, double mouseY, int button) {
        for (Button b : List.copyOf(buttons)) {
            if (!inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) continue;
            Runnable action = button == 1 ? b.right : button == 0 ? b.left : null;
            if (action != null) action.run();
            return true;
        }
        if (settings != null && overSettings(mouseX, mouseY) && settings.mouseClicked(mouseX, mouseY, button)) {
            draggingSetting = settings.isDragging();
            return true;
        }
        return false;
    }

    /** Scrolls the settings if the mouse is over them. @return whether it did. */
    protected boolean scrollSettings(double mouseX, double mouseY, double amount) {
        if (!overSettings(mouseX, mouseY)) return false;
        settingsScroll = Math.max(0, settingsScroll - (int) Math.signum(amount) * 14);
        return true;
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        if (settings != null) settings.mouseDragged(mouseX);
    }

    @Override
    void mouseReleased(int button) {
        if (settings != null) settings.mouseReleased();
        draggingSetting = false;
    }
}

package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.Setting;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One draggable, collapsible panel per category. Left click a module to toggle it,
 * right click to open its settings. Positions and sizes are in GUI units. While the menu's
 * search has something in it, each window shows only the modules that match, and windows
 * with none fold away and fade.
 */
public class CategoryWindow {
    public static final int WIDTH = 124;
    public static final int HEADER = 18;
    public static final int ROW = 15;
    private static final int PAD = 3;
    // Settings lists taller than this only show a preview inline, plus a row that opens the full window.
    private static final int INLINE_MAX = 140;
    private static final int PREVIEW_HEIGHT = 90;
    private static final int MORE_ROW = 14;

    public final Category category;
    public int x;
    public int y;

    private boolean open = true;
    private float expand = 1;
    private float headerHover;
    private float focus;
    private final Map<Module, Row> rows = new IdentityHashMap<>();

    private boolean dragging;
    private boolean moved;
    private double grabX;
    private double grabY;
    private SettingsList editing;

    // Long settings lists can outgrow the screen, so the body scrolls.
    private int maxBody = Integer.MAX_VALUE;
    private float scroll;
    private float scrollTarget;

    /** The module Enter would switch: the menu's best search match, wherever it is. */
    static Module best;
    // The search (stripped, lower case; empty for none), what in here matches it, and why.
    private String query = "";
    private List<Module> matches = List.of();
    private final Map<Module, ModuleSearch.Hit> hits = new IdentityHashMap<>();
    private float dim;

    public CategoryWindow(Category category) {
        this.category = category;
    }

    private Row row(Module module) {
        return rows.computeIfAbsent(module, Row::new);
    }

    private int rowHeight(Module module) {
        Row row = row(module);
        return ROW + Math.round(row.inlineHeight() * row.expand);
    }

    /** Shows only the modules matching {@code q} (stripped, lower case); empty shows them all. */
    void filter(String q) {
        if (q.equals(query)) return;
        query = q;
        hits.clear();
        List<Module> list = new ArrayList<>();
        if (!q.isEmpty()) {
            for (Module module : Modules.get(category)) {
                ModuleSearch.Hit hit = ModuleSearch.match(module, q);
                if (hit == null) continue;
                hits.put(module, hit);
                list.add(module);
            }
        }
        matches = list;
        scrollTarget = 0;
    }

    private boolean searching() {
        return !query.isEmpty();
    }

    /** The rows: every module, or while searching just the ones that match. */
    private List<Module> modules() {
        return searching() ? matches : Modules.get(category);
    }

    /** Open as far as you can see: searching opens windows with matches and folds the rest. */
    private boolean shownOpen() {
        return searching() ? !matches.isEmpty() : open;
    }

    private int contentHeight() {
        List<Module> modules = modules();
        if (modules.isEmpty()) return PAD * 2 + ROW;

        int h = PAD * 2;
        for (Module module : modules) h += rowHeight(module);
        return h;
    }

    private int visibleBody() {
        return Math.round(Math.min(contentHeight(), maxBody) * expand);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - maxBody);
    }

    private int contentTop() {
        return y + HEADER + PAD - Math.round(scroll);
    }

    public int height() {
        return HEADER + visibleBody();
    }

    public boolean isOver(double mouseX, double mouseY) {
        return Page.inside(mouseX, mouseY, x, y, WIDTH, height());
    }

    public void clamp(int screenWidth, int screenHeight) {
        x = Math.max(2, Math.min(x, screenWidth - WIDTH - 2));
        y = Math.max(2, Math.min(y, screenHeight - HEADER - 2));
        maxBody = Math.max(ROW * 3, screenHeight - (y + HEADER) - 8);
    }

    public JsonObject save() {
        JsonObject json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("open", open);
        return json;
    }

    public void load(JsonObject json) {
        if (json.has("x")) x = json.get("x").getAsInt();
        if (json.has("y")) y = json.get("y").getAsInt();
        if (json.has("open")) {
            open = json.get("open").getAsBoolean();
            expand = open ? 1 : 0;
        }
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha, boolean hovered) {
        float target = shownOpen() ? 1 : 0;
        expand = Math.abs(expand - target) < 0.002f ? target : Anim.approach(expand, target, dt, 16);
        // Nothing in here matches: it steps back so the windows that do stand out.
        dim = Anim.approach(dim, searching() && matches.isEmpty() ? 1 : 0, dt, 12);
        alpha *= 1 - dim * 0.6f;

        boolean overHeader = hovered && Page.inside(mouseX, mouseY, x, y, WIDTH, HEADER);
        headerHover = Anim.approach(headerHover, overHeader || dragging ? 1 : 0, dt, 14);
        focus = Anim.approach(focus, dragging ? 1 : hovered ? 0.65f : 0.35f, dt, 9);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
        scroll = Anim.approach(scroll, scrollTarget, dt, 18);

        if (dragging) g.requestCursor(CursorTypes.RESIZE_ALL);
        else if (overHeader) g.requestCursor(CursorTypes.POINTING_HAND);

        int body = visibleBody();
        Panel.draw(g, x, y, WIDTH, HEADER, body, alpha, focus, headerHover, expand);

        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.chevron(g, (x + WIDTH - 10) * s, y * s + HEADER * s / 2, (float) (-Math.PI / 2) * (1 - expand),
            Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, headerHover), alpha));
        Render2D.end(g);

        int textY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, category.title, x + 8, textY, Theme.fade(Theme.TEXT, alpha));

        int active = 0;
        for (Module module : Modules.get(category)) {
            if (module.isEnabled()) active++;
        }
        if (active > 0) {
            String count = Integer.toString(active);
            Text.draw(g, count, x + WIDTH - 18 - Text.width(count), textY, Theme.fade(Theme.accentText(), alpha));
        }

        if (body > 0) renderBody(g, mouseX, mouseY, dt, alpha, hovered, body);
    }

    private void renderBody(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha, boolean hovered, int body) {
        g.enableScissor(x, y + HEADER, x + WIDTH, y + HEADER + body);

        List<Module> modules = modules();
        int rowY = contentTop();

        if (modules.isEmpty()) {
            String text = "No modules yet";
            Text.draw(g, text, x + (WIDTH - Text.width(text)) / 2, rowY + (ROW - Text.CAP) / 2,
                Theme.fade(Theme.TEXT_FAINT, alpha));
        }

        for (Module module : modules) {
            Row row = row(module);
            row.expand = Anim.approach(row.expand, row.open ? 1 : 0, dt, 16);

            renderRow(g, module, row, rowY, mouseX, mouseY, dt, alpha, hovered);

            int settingsHeight = Math.round(row.inlineHeight() * row.expand);
            if (settingsHeight > 0) {
                int top = rowY + ROW;
                g.enableScissor(x, top, x + WIDTH, top + settingsHeight);

                int s = Render2D.scale();
                Render2D.pixels(g);
                g.fill((x + 3) * s, top * s, (x + WIDTH - 3) * s, (top + settingsHeight) * s, Theme.fade(0x40000000, alpha));
                g.fill((x + 4) * s, top * s, (x + 4) * s + Math.max(1, s / 2), (top + settingsHeight) * s,
                    Theme.alpha(Theme.accent(), 90 * alpha));
                Render2D.end(g);

                boolean interactive = hovered && !dragging && row.expand > 0.98f;
                row.settings.render(g, x + 5, top, WIDTH - 9, mouseX, mouseY, dt, alpha, interactive);
                if (row.hidden > 0) {
                    renderMoreRow(g, row, top + row.settings.height(), mouseX, mouseY, dt, alpha, interactive);
                }
                g.disableScissor();
            }

            rowY += ROW + settingsHeight;
        }

        if (maxScroll() > 0) drawScrollbar(g, body, alpha);
        g.disableScissor();
    }

    private void renderMoreRow(GuiGraphics g, Row row, int rowY, int mouseX, int mouseY, float dt, float alpha, boolean interactive) {
        boolean over = interactive && editing == null && Page.inside(mouseX, mouseY, x + 5, rowY, WIDTH - 9, MORE_ROW);
        row.moreHover = Anim.approach(row.moreHover, over ? 1 : 0, dt, 16);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 5) * s, rowY * s, (WIDTH - 9) * s, MORE_ROW * s, 2 * s, Theme.alpha(Theme.accent(), (14 + row.moreHover * 26) * alpha));
        Render2D.chevron(g, (x + WIDTH - 10) * s, rowY * s + MORE_ROW * s / 2, (float) (-Math.PI / 2),
            Theme.fade(Theme.accentText(), alpha));
        Render2D.end(g);

        int textY = rowY + (MORE_ROW - Text.CAP) / 2;
        String label = "All settings";
        Text.draw(g, label, x + 9, textY, Theme.fade(Theme.mix(Theme.accentText(), Theme.TEXT, row.moreHover), alpha));
        Text.draw(g, "+" + row.hidden, x + 13 + Text.width(label), textY, Theme.fade(Theme.TEXT_FAINT, alpha));
    }

    private void drawScrollbar(GuiGraphics g, int body, float alpha) {
        int s = Render2D.scale();
        int content = contentHeight();
        int thumb = Math.max(12, body * body / content);
        int top = y + HEADER + Math.round((body - thumb) * (scroll / maxScroll()));
        int w = Math.max(2, s);

        Render2D.pixels(g);
        Render2D.rect(g, (x + WIDTH - 2) * s - w, top * s, w, thumb * s, w / 2, Theme.fade(Theme.TEXT_FAINT, alpha));
        Render2D.end(g);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isOver(mouseX, mouseY) || maxScroll() == 0) return false;
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) amount * 24));
        return true;
    }

    private void renderRow(GuiGraphics g, Module module, Row row, int rowY, int mouseX, int mouseY,
                           float dt, float alpha, boolean hovered) {
        boolean over = hovered && !dragging && editing == null && expand > 0.98f && mouseY >= y + HEADER
            && Page.inside(mouseX, mouseY, x, rowY, WIDTH, ROW);
        row.hover = Anim.approach(row.hover, over ? 1 : 0, dt, 16);
        row.on = Anim.approach(row.on, module.isEnabled() ? 1 : 0, dt, 12);

        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        // Anticheat-down-only modules are red through and through, on or off.
        boolean risky = module.anticheatDownOnly();
        int accent = risky ? Theme.DANGER : Theme.accent();
        float glow = Theme.glow();
        int rx = (x + 3) * s;
        int ry = rowY * s;
        int rw = (WIDTH - 6) * s;
        int rh = ROW * s;
        int nudge = Math.round(row.on * 2 * s);
        int textX = x + 9;
        int textY = rowY + (ROW - Text.CAP) / 2;

        Render2D.pixels(g);
        if (module == best) {
            // What Enter switches.
            Render2D.glow(g, rx, ry, rw, rh, 2 * s, 5 * s, Theme.alpha(accent, 40 * glow * alpha));
            Render2D.rect(g, rx, ry, rw, rh, 2 * s, Theme.alpha(accent, 34 * alpha));
        }
        Render2D.rect(g, rx, ry, rw, rh, 2 * s, Theme.alpha(0xFFFFFF, row.hover * 12 * alpha));
        Render2D.fadeRight(g, rx, ry, rw, rh, Theme.alpha(accent, row.on * 60 * alpha));

        if (row.on > 0.01f) {
            int barW = Math.max(2, Math.round(1.5f * s));
            int barH = Math.round((rh - 6 * s) * (0.4f + 0.6f * row.on));
            int barY = ry + (rh - barH) / 2;
            Render2D.glow(g, rx, barY, barW, barH, barW / 2, 5 * s, Theme.alpha(accent, row.on * 170 * glow * alpha));
            Render2D.rect(g, rx, barY, barW, barH, barW / 2, Theme.alpha(accent, row.on * 255 * alpha));

            Render2D.glow(g, textX * s + nudge, textY * s, Text.width(module.name) * s, Text.CAP * s,
                2 * s, 6 * s, Theme.alpha(accent, row.on * 45 * glow * alpha));
        }

        if (!row.settings.isEmpty() || row.hidden > 0) {
            float angle = (float) (-Math.PI / 2) * (1 - row.expand);
            Render2D.chevron(g, (x + WIDTH - 10) * s, ry + rh / 2, angle,
                Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, Math.max(row.hover, row.expand)), alpha));
        }
        Render2D.end(g);

        int color = risky
            ? Theme.mix(Theme.mix(0xFFC2585C, Theme.DANGER, row.hover * 0.7f), 0xFFFF9A9D, row.on)
            : Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, row.hover * 0.7f), Theme.accentText(), row.on);

        g.pose().pushMatrix();
        g.pose().translate(nudge / (float) s, 0);
        Text.draw(g, module.name, textX, textY, Theme.fade(color, alpha));
        g.pose().popMatrix();

        ModuleSearch.Hit hit = hits.get(module);
        if (hit != null) drawWhy(g, module, hit, textX * s + nudge, textY, alpha);
    }

    /** Why a module matched: what was typed underlined in its name, or the setting it matched by. */
    private void drawWhy(GuiGraphics g, Module module, ModuleSearch.Hit hit, int namePx, int textY, float alpha) {
        int s = Render2D.scale();
        int at = module.name.toLowerCase(Locale.ROOT).indexOf(query);
        if (hit.score() <= ModuleSearch.INSIDE && at >= 0) {
            int from = namePx + Text.width(module.name.substring(0, at)) * s;
            int w = Text.width(module.name.substring(at, at + query.length())) * s;
            int lineY = (textY + Text.CAP + 1) * s;
            Render2D.pixels(g);
            g.fill(from, lineY, from + w, lineY + Math.max(1, s / 2), Theme.fade(Theme.accentText(), alpha));
            Render2D.end(g);
        } else if (hit.setting() != null) {
            // In the gap between the name and the chevron, cut short if it has to be.
            int room = WIDTH - 9 - Text.width(module.name) - 8 - 17;
            String label = hit.setting().name;
            if (Text.width(label) > room) {
                String cut = label;
                while (cut.length() > 2 && Text.width(cut + "…") > room) cut = cut.substring(0, cut.length() - 1);
                label = cut.length() > 2 ? cut + "…" : null;
            }
            if (label != null) Text.draw(g, label, x + WIDTH - 17 - Text.width(label), textY, Theme.fade(Theme.TEXT_FAINT, alpha));
        }
    }

    /** @return true if this window consumed the click. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (Page.inside(mouseX, mouseY, x, y, WIDTH, HEADER)) {
            if (button == 0) {
                dragging = true;
                moved = false;
                grabX = mouseX - x;
                grabY = mouseY - y;
            } else if (button == 1 && !searching()) {
                open = !open;
            }
            return true;
        }

        if (!isOver(mouseX, mouseY)) return false;
        if (!shownOpen() || expand < 0.98f) return true;

        int rowY = contentTop();
        for (Module module : modules()) {
            Row row = row(module);

            if (Page.inside(mouseX, mouseY, x, rowY, WIDTH, ROW)) {
                if (button == 0) module.toggle();
                else if (button == 1 && (!row.settings.isEmpty() || row.hidden > 0)) row.open = !row.open;
                return true;
            }

            int settingsHeight = Math.round(row.inlineHeight() * row.expand);
            if (row.open && row.expand > 0.98f && Page.inside(mouseX, mouseY, x, rowY + ROW, WIDTH, settingsHeight)) {
                int moreY = rowY + ROW + row.settings.height();
                if (row.hidden > 0 && Page.inside(mouseX, mouseY, x, moreY, WIDTH, MORE_ROW)) {
                    if (button == 0) {
                        row.open = false;
                        ModulesPage.openSettings(module, x + WIDTH + 8, rowY);
                    }
                } else if (row.settings.mouseClicked(mouseX, mouseY, button) && row.settings.isDragging()) {
                    editing = row.settings;
                }
                return true;
            }

            rowY += ROW + settingsHeight;
        }
        return true;
    }

    public void mouseReleased(int button) {
        if (editing != null) {
            editing.mouseReleased();
            editing = null;
        }
        if (dragging && button == 0) {
            dragging = false;
            if (!moved && !searching()) open = !open;
        }
    }

    public void mouseDragged(double mouseX, double mouseY) {
        if (editing != null) {
            editing.mouseDragged(mouseX);
            return;
        }
        if (!dragging) return;

        int newX = (int) Math.round(mouseX - grabX);
        int newY = (int) Math.round(mouseY - grabY);
        if (newX != x || newY != y) moved = true;

        x = newX;
        y = newY;
    }

    private static final class Row {
        final SettingsList settings;
        /** Settings left out of the inline preview; they're in the module's settings window. */
        final int hidden;
        float hover;
        float on;
        float expand;
        float moreHover;
        boolean open;

        Row(Module module) {
            List<Setting<?>> all = module.settings();
            int total = 0;
            for (Setting<?> setting : all) total += SettingsList.heightOf(setting);

            int shown = all.size();
            if (total > INLINE_MAX) {
                shown = 0;
                int height = 0;
                while (shown < all.size() && height + SettingsList.heightOf(all.get(shown)) <= PREVIEW_HEIGHT) {
                    height += SettingsList.heightOf(all.get(shown));
                    shown++;
                }
            }
            settings = new SettingsList(all.subList(0, shown));
            hidden = all.size() - shown;
        }

        int inlineHeight() {
            return settings.height() + (hidden > 0 ? MORE_ROW : 0);
        }
    }
}

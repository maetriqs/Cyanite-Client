package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.player.InventoryMove;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.visuals.Crosshair;
import com.cyanite.client.visuals.Group;
import com.cyanite.client.visuals.Preview;
import com.cyanite.client.visuals.Visuals;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Visuals tab: the profile on the left with a live preview of whatever you're setting, and on the right
 * every section (General, Crystal, Sword, Axe, Pot, UHC, General PvP) folding open to its groups, each group
 * a switch with its settings under it. The search box filters groups by name, description and setting.
 */
final class VisualsPage extends Page {
    private static final int SIDE_W = 172;
    private static final int GAP = 8;
    private static final int MAIN_MAX = 380;
    private static final int HEADER = 18;
    private static final int PAD = 4;
    private static final int CHIP_H = 16;
    private static final int BUTTON_H = 14;
    private static final int PREVIEW_H = 100;
    private static final int SEARCH_H = 16;
    private static final int SECTION_H = 26;
    private static final int GROUP_H = 18;
    private static final int LINE = 10;

    // What's folded open lasts while the game runs.
    private static final Set<String> openSections = new HashSet<>(Set.of("General Visuals"));
    /** "section/group id": a group shown in two sections opens in each on its own. */
    private static final Set<String> openGroups = new HashSet<>();
    private static Group focus = Crosshair.GROUP;

    private final TextField search = new TextField(40, cp -> cp >= 32 && cp != 127);
    private final SettingsList profileSettings = new SettingsList(List.of(Visuals.AUTO, Visuals.FALLBACK));
    private final Map<String, SettingsList> lists = new HashMap<>();
    private final Map<SettingsList, Group> listGroup = new IdentityHashMap<>();
    private final Map<Object, float[]> anim = new HashMap<>();
    /** Lists drawn this frame, the only ones a click can reach. */
    private final List<SettingsList> shown = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> listHits = new ArrayList<>();
    private float scroll;
    private float scrollTarget;
    private int content;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;
    private boolean draggingList;
    private long resetArmedAt;

    private record Hit(Rect at, Runnable action) {
    }

    VisualsPage() {
        search.setPadLeft(17);
    }

    @Override
    void onShow() {
        search.setFocused(false);
    }

    private float[] anim(Object key) {
        return anim.computeIfAbsent(key, k -> new float[]{0, Float.NaN});
    }

    private String query() {
        return search.text().strip().toLowerCase(Locale.ROOT);
    }

    // ---- Drawing

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        int mainW = Math.max(220, Math.min(MAIN_MAX, width - SIDE_W - GAP - 16));
        int total = SIDE_W + GAP + mainW;
        int x0 = Math.max(8, (width - total) / 2);
        int y = TOP + Math.round((1 - entrance()) * -6);
        hits.clear();
        listHits.clear();
        shown.clear();

        int profileBottom = renderProfiles(g, x0, y, mouseX, mouseY, dt, a);
        int previewBottom = renderPreview(g, x0, profileBottom + GAP, mouseX, mouseY, dt, a);
        Rect main = renderMain(g, x0 + SIDE_W + GAP, y, mainW, mouseX, mouseY, dt, a);
        bounds = new Rect(x0, y, total, Math.max(previewBottom - y, main.h()));
    }

    private int renderProfiles(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        int chipW = (SIDE_W - 8 - 4) / 2;
        int rows = (Visuals.PROFILES.size() + 1) / 2;
        int body = PAD + rows * (CHIP_H + 3) + 1 + profileSettings.height() + 4 + BUTTON_H + PAD + 2;
        boolean hovered = inside(mouseX, mouseY, x, y, SIDE_W, HEADER + body);
        Panel.draw(g, x, y, SIDE_W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, "Visual profile", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int cy = y + HEADER + PAD;
        for (int i = 0; i < Visuals.PROFILES.size(); i++) {
            String profile = Visuals.PROFILES.get(i);
            int cx = x + 4 + (i % 2) * (chipW + 4);
            int ry = cy + (i / 2) * (CHIP_H + 3);
            boolean active = profile.equals(Visuals.active());
            boolean over = inside(mouseX, mouseY, cx, ry, chipW, CHIP_H);
            float[] h = anim("chip:" + profile);
            h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
            if (Float.isNaN(h[1])) h[1] = active ? 1 : 0;
            h[1] = Anim.approach(h[1], active ? 1 : 0, dt, 14);
            if (over) g.requestCursor(CursorTypes.POINTING_HAND);
            int accent = Theme.accent();
            Render2D.pixels(g);
            Render2D.glow(g, cx * s, ry * s, chipW * s, CHIP_H * s, r, 6 * s, Theme.alpha(accent, h[1] * 50 * Theme.glow() * a));
            Render2D.rect(g, cx * s, ry * s, chipW * s, CHIP_H * s, r,
                Theme.fade(Theme.mix(Theme.mix(Theme.EDGE, accent, h[0] * 0.5f), accent, h[1]), a));
            Render2D.rect(g, cx * s + 1, ry * s + 1, chipW * s - 2, CHIP_H * s - 2, Math.max(0, r - 1),
                Theme.fade(Theme.mix(Theme.FIELD, accent | 0xFF000000, h[1] * 0.28f), a));
            Render2D.end(g);
            int ink = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h[0]), 0xFFFFFFFF, h[1]);
            String name = fit(profile, chipW - 6);
            Text.draw(g, name, cx + (chipW - Text.width(name)) / 2, ry + (CHIP_H - Text.CAP) / 2, Theme.fade(ink, a));
            hits.add(new Hit(new Rect(cx, ry, chipW, CHIP_H), () -> Visuals.switchTo(profile)));
        }

        int sy = cy + rows * (CHIP_H + 3) + 1;
        profileSettings.render(g, x + 4, sy, SIDE_W - 8, mouseX, mouseY, dt, a, hovered);

        int by = sy + profileSettings.height() + 4;
        boolean armed = System.currentTimeMillis() - resetArmedAt < 3000;
        button(g, "copy", "Copy to all", x + 4, by, chipW, mouseX, mouseY, dt, a, Visuals::copyToAll);
        button(g, "reset", armed ? "Click to confirm" : "Reset profile", x + 8 + chipW, by, chipW, mouseX, mouseY, dt, a, () -> {
            if (System.currentTimeMillis() - resetArmedAt < 3000) {
                Visuals.resetActive();
                resetArmedAt = 0;
            } else {
                resetArmedAt = System.currentTimeMillis();
            }
        });
        return y + HEADER + body;
    }

    private void button(GuiGraphics g, String key, String label, int x, int y, int w, int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = inside(mouseX, mouseY, x, y, w, BUTTON_H);
        float[] h = anim("button:" + key);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.button(g, x, y, w, BUTTON_H, fit(label, w - 4), h[0], true, a);
        hits.add(new Hit(new Rect(x, y, w, BUTTON_H), action));
    }

    private int renderPreview(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        Group group = focus;
        String action = Preview.action(group);
        int body = PAD + PREVIEW_H + 4 + LINE + (action != null ? BUTTON_H + 4 : 0) + PAD;
        boolean hovered = inside(mouseX, mouseY, x, y, SIDE_W, HEADER + body);
        Panel.draw(g, x, y, SIDE_W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, fit("Preview: " + group.name, SIDE_W - 16), x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        int bx = x + 4;
        int by = y + HEADER + PAD;
        int bw = SIDE_W - 8;
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        Render2D.pixels(g);
        Render2D.rect(g, bx * s, by * s, bw * s, PREVIEW_H * s, r, Theme.fade(Theme.EDGE, a));
        Render2D.rect(g, bx * s + 1, by * s + 1, bw * s - 2, PREVIEW_H * s - 2, Math.max(0, r - 1), Theme.fade(Theme.FIELD, a));
        Render2D.end(g);
        if (a > 0.05f) {
            g.enableScissor(bx + 1, by + 1, bx + bw - 1, by + PREVIEW_H - 1);
            Preview.draw(g, group, bx + 1, by + 1, bw - 2, PREVIEW_H - 2, a);
            g.disableScissor();
        }

        int ty = by + PREVIEW_H + 4;
        String state;
        int color;
        if (!Visuals.live()) {
            state = "Stealth is holding visuals off";
            color = Theme.TEXT_FAINT;
        } else if (group.enabled.get()) {
            state = "On in " + Visuals.active();
            color = Theme.accentText();
        } else {
            state = "Off: switch it on to use it";
            color = Theme.TEXT_DIM;
        }
        Text.draw(g, fit(state, SIDE_W - 16), x + 8, ty, Theme.fade(color, a));
        if (action != null) button(g, "preview", action, x + 4, ty + LINE + 2, SIDE_W - 8, mouseX, mouseY, dt, a, () -> Preview.act(group));
        return y + HEADER + body;
    }

    // ---- The sections

    /** One line of the list on the right. */
    private record Row(int kind, Visuals.Section section, Group group, int h) {
        static final int SECTION = 0;
        static final int GROUP = 1;
        static final int SETTINGS = 2;
        static final int EMPTY = 3;
    }

    private List<Row> rows(int listW) {
        String q = query();
        List<Row> rows = new ArrayList<>();
        Set<Group> seen = new HashSet<>();
        for (Visuals.Section section : Visuals.SECTIONS) {
            List<Group> groups = new ArrayList<>();
            for (Group group : section.groups()) {
                if (q.isEmpty()) groups.add(group);
                else if (!seen.contains(group) && matches(group, section, q)) groups.add(group);
            }
            if (groups.isEmpty()) continue;
            seen.addAll(groups);
            rows.add(new Row(Row.SECTION, section, null, SECTION_H));
            if (q.isEmpty() && !openSections.contains(section.name())) continue;
            for (Group group : groups) {
                rows.add(new Row(Row.GROUP, section, group, GROUP_H));
                if (isOpen(section, group)) rows.add(new Row(Row.SETTINGS, section, group, settingsHeight(section, group, listW)));
            }
        }
        if (rows.isEmpty()) rows.add(new Row(Row.EMPTY, null, null, 30));
        return rows;
    }

    private static boolean matches(Group group, Visuals.Section section, String q) {
        StringBuilder text = new StringBuilder(group.name).append(' ').append(group.description).append(' ').append(group.keywords)
            .append(' ').append(section.name());
        for (Setting<?> setting : group.settings) text.append(' ').append(setting.name);
        String hay = text.toString().toLowerCase(Locale.ROOT);
        for (String word : q.split("\\s+")) {
            if (!hay.contains(word)) return false;
        }
        return true;
    }

    private static String key(Visuals.Section section, Group group) {
        return section.name() + "/" + group.id;
    }

    private boolean isOpen(Visuals.Section section, Group group) {
        return openGroups.contains(key(section, group));
    }

    private SettingsList list(Visuals.Section section, Group group) {
        return lists.computeIfAbsent(key(section, group), k -> {
            SettingsList list = new SettingsList(group.settings);
            listGroup.put(list, group);
            return list;
        });
    }

    private int settingsHeight(Visuals.Section section, Group group, int listW) {
        return 3 + wrap(group.description, listW - 20).size() * LINE + 3 + list(section, group).height() + 6;
    }

    private Rect renderMain(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        int maxBody = Math.max(120, height - y - HEADER - 12);
        int listTop = PAD + SEARCH_H + PAD;
        List<Row> rows = rows(w);
        content = 0;
        for (Row row : rows) content += row.h();
        viewH = Math.min(content, maxBody - listTop - PAD);
        int body = listTop + viewH + PAD;
        boolean hovered = inside(mouseX, mouseY, x, y, w, HEADER + body);
        Panel.draw(g, x, y, w, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, "Visuals", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        long on = Visuals.GROUPS.stream().filter(gr -> gr.enabled.get()).count();
        String count = on + " of " + Visuals.GROUPS.size() + " on";
        Text.draw(g, count, x + w - 8 - Text.width(count), y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));

        int fy = y + HEADER + PAD;
        search.render(g, x + 4, fy, w - 8, SEARCH_H, "Search visuals (Ctrl+F)...", mouseX, mouseY, dt, a);
        Widgets.magnifier(g, x + 4 + 9, fy + SEARCH_H / 2, Theme.fade(search.isFocused() ? Theme.accentText() : Theme.TEXT_DIM, a), a);

        viewX = x + 4;
        viewY = y + HEADER + listTop;
        viewW = w - 8;
        scrollTarget = Math.max(0, Math.min(scrollTarget, content - viewH));
        scroll = Anim.enabled() ? Anim.approach(scroll, scrollTarget, dt, 18) : scrollTarget;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - viewH)));
        boolean overView = inside(mouseX, mouseY, viewX, viewY, viewW, viewH);
        int my = overView || draggingList ? mouseY : -10000;

        g.enableScissor(viewX, viewY, viewX + viewW, viewY + viewH);
        int ry = viewY - Math.round(scroll);
        for (Row row : rows) {
            boolean visible = ry + row.h() > viewY && ry < viewY + viewH;
            if (visible) {
                switch (row.kind()) {
                    case Row.SECTION -> sectionRow(g, row.section(), viewX, ry, viewW, mouseX, my, dt, a);
                    case Row.GROUP -> groupRow(g, row.section(), row.group(), viewX, ry, viewW, mouseX, my, dt, a);
                    case Row.SETTINGS -> settingsRow(g, row.section(), row.group(), viewX, ry, viewW, mouseX, my, dt, a);
                    default -> {
                        String none = "Nothing matches \"" + search.text().strip() + "\"";
                        Text.draw(g, fit(none, viewW - 8), viewX + (viewW - Math.min(viewW - 8, Text.width(none))) / 2, ry + 11,
                            Theme.fade(Theme.TEXT_FAINT, a));
                    }
                }
            }
            ry += row.h();
        }
        g.disableScissor();

        if (content > viewH) {
            int s = Render2D.scale();
            int thumbH = Math.max(10, viewH * viewH / content);
            int thumbY = viewY + Math.round((viewH - thumbH) * scroll / Math.max(1, content - viewH));
            int tw = Math.max(2, s);
            Render2D.pixels(g);
            Render2D.rect(g, (x + w - 2) * s - tw, thumbY * s, tw, thumbH * s, tw / 2, Theme.fade(Theme.TEXT_FAINT, a));
            Render2D.end(g);
        }
        return new Rect(x, y, w, HEADER + body);
    }

    private void sectionRow(GuiGraphics g, Visuals.Section section, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        boolean searching = !query().isEmpty();
        boolean open = searching || openSections.contains(section.name());
        boolean over = inside(mouseX, mouseY, x, y + 2, w, SECTION_H - 4);
        float[] h = anim("section:" + section.name());
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (Float.isNaN(h[1])) h[1] = open ? 1 : 0;
        h[1] = Anim.approach(h[1], open ? 1 : 0, dt, 14);
        if (over && !searching) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, (y + 2) * s, w * s, (SECTION_H - 4) * s, r, Theme.fade(Theme.mix(Theme.HEADER, Theme.HEADER_HOVER, h[0]), a));
        g.fill(x * s, (y + 2) * s + r, x * s + Math.max(1, s), (y + SECTION_H - 2) * s - r, Theme.alpha(accent, (90 + 120 * h[1]) * a));
        Render2D.chevron(g, (x + 9) * s, (y + SECTION_H / 2) * s, (float) (-Math.PI / 2) * (1 - h[1]),
            Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.accentText(), h[1]), a));
        Render2D.end(g);

        Text.draw(g, section.name(), x + 18, y + 5, Theme.fade(Theme.TEXT, a));
        Text.draw(g, fit(section.blurb(), w - 26), x + 18, y + 15, Theme.fade(Theme.TEXT_FAINT, a));
        int on = 0;
        for (Group group : section.groups()) if (group.enabled.get()) on++;
        String count = on + "/" + section.groups().size() + " on";
        Text.draw(g, count, x + w - 6 - Text.width(count), y + 5, Theme.fade(on > 0 ? Theme.accentText() : Theme.TEXT_FAINT, a));
        if (!searching) listHits.add(new Hit(new Rect(x, y + 2, w, SECTION_H - 4), () -> {
            if (!openSections.remove(section.name())) openSections.add(section.name());
        }));
    }

    private void groupRow(GuiGraphics g, Visuals.Section section, Group group, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        boolean open = isOpen(section, group);
        boolean over = inside(mouseX, mouseY, x, y, w, GROUP_H);
        String key = key(section, group);
        float[] h = anim("group:" + key);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (Float.isNaN(h[1])) h[1] = open ? 1 : 0;
        h[1] = Anim.approach(h[1], open ? 1 : 0, dt, 14);
        float[] t = anim("toggle:" + group.id);
        if (Float.isNaN(t[1])) {
            t[1] = 0;
            t[0] = group.enabled.get() ? 1 : 0;
        }
        t[0] = Anim.approach(t[0], group.enabled.get() ? 1 : 0, dt, 14);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 6) * s, y * s, (w - 6) * s, GROUP_H * s, 2 * s,
            Theme.alpha(0xFFFFFF, (h[0] * 10 + (group == focus ? 6 : 0)) * a));
        Render2D.chevron(g, (x + w - 9) * s, (y + GROUP_H / 2) * s, (float) (-Math.PI / 2) * (1 - h[1]),
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, Math.max(h[0], h[1])), a));
        Render2D.end(g);

        Widgets.toggle(g, x + 10, y + (GROUP_H - Widgets.TOGGLE_H) / 2, t[0], a);
        int nx = x + 10 + Widgets.TOGGLE_W + 7;
        int ink = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h[0]), Theme.TEXT, t[0]);
        Text.draw(g, group.name, nx, y + (GROUP_H - Text.CAP) / 2, Theme.fade(ink, a));
        if (group.isShared()) {
            String tag = "shared";
            int tx = nx + Text.width(group.name) + 6;
            if (tx + Text.width(tag) < x + w - 18) Text.draw(g, tag, tx, y + (GROUP_H - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }

        listHits.add(new Hit(new Rect(x + 6, y, Widgets.TOGGLE_W + 10, GROUP_H), () -> {
            group.enabled.set(!group.enabled.get());
            focus = group;
        }));
        listHits.add(new Hit(new Rect(x + 6, y, w - 6, GROUP_H), () -> {
            if (!openGroups.remove(key)) openGroups.add(key);
            focus = group;
        }));
    }

    private void settingsRow(GuiGraphics g, Visuals.Section section, Group group, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        int h = settingsHeight(section, group, w);
        Render2D.pixels(g);
        g.fill((x + 12) * s, (y + 1) * s, (x + 12) * s + Math.max(1, s / 2), (y + h - 5) * s, Theme.alpha(Theme.accent(), 70 * a));
        Render2D.end(g);
        int ty = y + 3;
        for (String line : wrap(group.description, w - 20)) {
            Text.draw(g, line, x + 18, ty, Theme.fade(Theme.TEXT_DIM, a));
            ty += LINE;
        }
        SettingsList list = list(section, group);
        list.render(g, x + 14, ty + 3, w - 18, mouseX, mouseY, dt, a, mouseY > -10000);
        shown.add(list);
    }

    // ---- Input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (search.mouseClicked(mouseX, mouseY)) return true;
        if (button == 0) {
            for (Hit hit : hits) {
                if (inside(mouseX, mouseY, hit.at.x(), hit.at.y(), hit.at.w(), hit.at.h())) {
                    hit.action.run();
                    return true;
                }
            }
        }
        if (profileSettings.mouseClicked(mouseX, mouseY, button)) return true;
        if (!inside(mouseX, mouseY, viewX, viewY, viewW, viewH)) return false;
        for (SettingsList list : shown) {
            if (list.mouseClicked(mouseX, mouseY, button)) {
                draggingList = list.isDragging();
                Group group = listGroup.get(list);
                if (group != null) focus = group;
                return true;
            }
        }
        if (button != 0) return false;
        for (Hit hit : listHits) {
            if (inside(mouseX, mouseY, hit.at.x(), hit.at.y(), hit.at.w(), hit.at.h())) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        profileSettings.mouseDragged(mouseX);
        for (SettingsList list : shown) list.mouseDragged(mouseX);
    }

    @Override
    void mouseReleased(int button) {
        profileSettings.mouseReleased();
        for (SettingsList list : lists.values()) list.mouseReleased();
        draggingList = false;
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!inside(mouseX, mouseY, viewX, viewY, viewW, viewH)) return false;
        scrollTarget = Math.max(0, Math.min(content - viewH, scrollTarget - (float) amount * 28));
        return true;
    }

    @Override
    boolean isTyping() {
        return search.isFocused();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            search.setFocused(true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // One Esc clears the search, the next closes.
            search.setFocused(false);
            if (search.text().isEmpty()) return false;
            search.clear();
            scrollTarget = 0;
            return true;
        }
        if (!search.isFocused()) return false;
        boolean types = key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT;
        if (CyaniteClientMod.clickGuiKey().matches(event) && !types) return false;
        String before = search.text();
        boolean used = search.keyPressed(event);
        if (!before.equals(search.text())) scrollTarget = 0;
        return used;
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (!search.isFocused()) {
            // Typing anywhere searches, unless Inventory Move has you walking around with the menu open.
            if (Modules.get(InventoryMove.class).walksInClickGui()) return false;
            search.setFocused(true);
        }
        boolean used = search.charTyped(event);
        scrollTarget = 0;
        return used;
    }

    // ---- Text

    private static String fit(String text, int room) {
        if (Text.width(text) <= room) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "…") > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    private static List<String> wrap(String text, int room) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Text.width(next) > room && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}

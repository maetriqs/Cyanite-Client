package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.player.InventoryMove;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.visuals.Crosshair;
import com.cyanite.client.visuals.Group;
import com.cyanite.client.visuals.Preview;
import com.cyanite.client.visuals.Visuals;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
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
 * The Visuals tab. Along the top, the visual profile you're editing (each PvP style keeps its own). On the left,
 * what to browse: everything, what's switched on, or one section, and the auto switch. In the middle a card for
 * every group, each a live picture of it with its switch. On the right, the group you picked: a big preview,
 * what it does, its settings, and putting it back or copying it to every profile. Typing anywhere searches.
 */
final class VisualsPage extends Page {
    private static final int MAX_W = 940;
    private static final int GAP = 6;
    private static final int BAR_H = 24;
    private static final int HEADER = 18;
    private static final int PAD = 4;
    private static final int CHIP_H = 16;
    private static final int ICON = 11;
    private static final int SEARCH_H = 16;
    private static final int RAIL_ROW = 16;
    private static final int CARD_MIN = 112;
    private static final int CARD_GAP = 5;
    private static final int CARD_FOOT = 19;
    private static final int HEADING_H = 18;
    private static final int PREVIEW_H = 112;
    private static final int BUTTON_H = 14;
    private static final int LINE = 10;
    private static final String ALL = "All visuals";
    private static final String ON = "Switched on";

    /** The profiles' and sections' icons, by name: the PvP styles share theirs. */
    private static final Map<String, ItemStack> ICONS = Map.ofEntries(
        Map.entry(ALL, new ItemStack(Items.NETHER_STAR)), Map.entry(ON, new ItemStack(Items.LIME_DYE)),
        Map.entry(Visuals.GENERAL, new ItemStack(Items.GRASS_BLOCK)), Map.entry("General Visuals", new ItemStack(Items.SPYGLASS)),
        Map.entry("World", new ItemStack(Items.GLOWSTONE_DUST)),
        Map.entry("Crystal PvP", new ItemStack(Items.END_CRYSTAL)), Map.entry("Sword PvP", new ItemStack(Items.NETHERITE_SWORD)),
        Map.entry("Axe PvP", new ItemStack(Items.NETHERITE_AXE)),
        Map.entry("Pot PvP", PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HEALING)),
        Map.entry("UHC / SMP", new ItemStack(Items.GOLDEN_APPLE)), Map.entry("General PvP", new ItemStack(Items.SHIELD)));

    // What you were looking at lasts while the game runs.
    private static String filter = ALL;
    private static Group focus = Crosshair.GROUP;

    private final TextField search = new TextField(40, cp -> cp >= 32 && cp != 127);
    private final Map<Group, SettingsList> lists = new IdentityHashMap<>();
    private final Map<Object, float[]> anim = new HashMap<>();
    /** Everything clickable outside the two scrolling views, this frame. */
    private final List<Hit> hits = new ArrayList<>();
    /** The cards drawn this frame, where the mouse can reach them. */
    private final List<Hit> cardHits = new ArrayList<>();
    /** Every card laid out this frame, in the grid's own coordinates, for the arrow keys. */
    private final List<Card> cards = new ArrayList<>();
    private final Map<Group, Boolean> changed = new IdentityHashMap<>();
    private final Scroll grid = new Scroll();
    private final Scroll inspector = new Scroll();
    private int changedAt = -1;
    /** Counts frames, so a switch drawn twice in one (card and inspector) eases once. */
    private int frame;
    private SettingsList shownList;
    private boolean draggingList;
    /** Scroll the grid to the focused card on the next frame: it moved by keyboard or search. */
    private boolean reveal;
    private long resetProfileAt;
    private long resetGroupAt;

    private record Hit(Rect at, Runnable action) {
    }

    private record Card(Group group, Rect at) {
    }

    /** One thing in the grid: a section's heading with the groups shown under it, or a group's card. */
    private record Entry(Visuals.Section heading, List<Group> under, Group group) {
    }

    /** A view that scrolls: where it was drawn, how tall its content is, and how far down it is. */
    private static final class Scroll {
        int x;
        int y;
        int w;
        int h;
        int content;
        float at;
        float target;

        void update(float dt) {
            target = Math.max(0, Math.min(target, content - h));
            at = Anim.enabled() ? Anim.approach(at, target, dt, 18) : target;
            at = Math.max(0, Math.min(at, Math.max(0, content - h)));
        }

        boolean over(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, w, h);
        }

        void wheel(double amount) {
            target = Math.max(0, Math.min(content - h, target - (float) amount * 28));
        }

        int offset() {
            return Math.round(at);
        }
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

    /** Whether the group has a setting off its default, worked out again only when something changes. */
    private boolean isChanged(Group group) {
        if (changedAt != Visuals.version()) {
            changed.clear();
            changedAt = Visuals.version();
        }
        return changed.computeIfAbsent(group, Visuals::isChanged);
    }

    private static Visuals.Section section(String name) {
        for (Visuals.Section section : Visuals.SECTIONS) {
            if (section.name().equals(name)) return section;
        }
        return null;
    }

    private void select(Group group) {
        if (group != focus) {
            focus = group;
            inspector.target = 0;
            inspector.at = 0;
            resetGroupAt = 0;
        }
    }

    private static void toggle(Group group) {
        group.enabled.set(!group.enabled.get());
    }

    // ---- Drawing

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        int total = Math.min(MAX_W, width - 16);
        int x0 = (width - total) / 2;
        int y = TOP + Math.round((1 - entrance()) * -6);
        // The classic menu's name and count sit along the bottom; Beta's window has its own.
        int bottom = height - (Minecraft.getInstance().screen instanceof ClickGuiScreen ? 22 : 8);
        hits.clear();
        cardHits.clear();
        shownList = null;
        frame++;

        boolean roomy = total >= 820;
        int railW = roomy ? 148 : 124;
        int inspectW = roomy ? 248 : 224;
        int gridW = total - railW - inspectW - 2 * GAP;

        renderBar(g, x0, y, total, mouseX, mouseY, dt, a);
        int top = y + BAR_H + GAP;
        int colH = Math.max(160, bottom - top);
        renderRail(g, x0, top, railW, colH, mouseX, mouseY, dt, a);
        renderGrid(g, x0 + railW + GAP, top, gridW, colH, mouseX, mouseY, dt, a);
        renderInspector(g, x0 + railW + GAP + gridW + GAP, top, inspectW, colH, mouseX, mouseY, dt, a);
        bounds = new Rect(x0, y, total, top + colH - y);
    }

    /** A small item picture, {@code ICON} across, at (x, y). */
    private static void icon(GuiGraphics g, String name, int x, int y) {
        ItemStack stack = ICONS.get(name);
        if (stack != null) Preview.icon(g, stack, x, y, ICON);
    }

    // ---- The profile bar

    private void renderBar(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        boolean hovered = inside(mouseX, mouseY, x, y, w, BAR_H);
        Panel.draw(g, x, y, w, BAR_H, 0, a, hovered ? 0.5f : 0.3f, 0, 0);
        int mid = y + BAR_H / 2;
        String label = "Profile";
        Text.draw(g, label, x + 9, mid - Text.CAP / 2, Theme.fade(Theme.TEXT_DIM, a));

        // The buttons on the right first, so the chips know how much room is left.
        boolean armed = System.currentTimeMillis() - resetProfileAt < 3000;
        String reset = armed ? "Click to confirm" : "Reset profile";
        String copy = "Copy to all";
        int resetW = Text.width(reset) + 14;
        int copyW = Text.width(copy) + 14;
        int bx = x + w - 5 - resetW;
        button(g, "reset_profile", reset, bx, mid - BUTTON_H / 2, resetW, mouseX, mouseY, dt, a, () -> {
            if (System.currentTimeMillis() - resetProfileAt < 3000) {
                Visuals.resetActive();
                resetProfileAt = 0;
            } else {
                resetProfileAt = System.currentTimeMillis();
            }
        });
        bx -= copyW + 4;
        button(g, "copy_profile", copy, bx, mid - BUTTON_H / 2, copyW, mouseX, mouseY, dt, a, Visuals::copyToAll);

        int cx = x + 9 + Text.width(label) + 8;
        String auto = Visuals.AUTO.get() ? "Auto" : null;
        int room = bx - 8 - cx - (auto != null ? Text.width(auto) + 14 : 0);
        int full = 0;
        for (String profile : Visuals.PROFILES) full += chipWidth(profile, false) + 3;
        boolean compact = full > room;
        for (String profile : Visuals.PROFILES) {
            boolean active = profile.equals(Visuals.active());
            int cw = chipWidth(profile, compact && !active);
            chip(g, profile, cx, mid - CHIP_H / 2, cw, active, compact && !active, mouseX, mouseY, dt, a);
            cx += cw + 3;
        }
        if (auto != null) {
            // The profile follows what's in your hand: say so beside the chips.
            int px = cx + 4;
            int pw = Text.width(auto) + 8;
            int s = Render2D.scale();
            Render2D.pixels(g);
            Render2D.rect(g, px * s, (mid - 6) * s, pw * s, 12 * s, 6 * s, Theme.alpha(Theme.accent(), 60 * a));
            Render2D.end(g);
            Text.draw(g, auto, px + 4, mid - Text.CAP / 2, Theme.fade(Theme.accentText(), a));
        }
    }

    private static int chipWidth(String profile, boolean iconOnly) {
        return iconOnly ? ICON + 10 : ICON + 4 + Text.width(profile) + 14;
    }

    private void chip(GuiGraphics g, String profile, int x, int y, int w, boolean active, boolean iconOnly, int mouseX, int mouseY, float dt, float a) {
        boolean over = inside(mouseX, mouseY, x, y, w, CHIP_H);
        float[] h = anim("chip:" + profile);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (Float.isNaN(h[1])) h[1] = active ? 1 : 0;
        h[1] = Anim.approach(h[1], active ? 1 : 0, dt, 14);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, CHIP_H * s, r, 6 * s, Theme.alpha(accent, h[1] * 60 * Theme.glow() * a));
        Render2D.rect(g, x * s, y * s, w * s, CHIP_H * s, r, Theme.fade(Theme.mix(Theme.mix(Theme.EDGE, accent, h[0] * 0.5f), accent, h[1]), a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, CHIP_H * s - 2, Math.max(0, r - 1),
            Theme.fade(Theme.mix(Theme.FIELD, accent | 0xFF000000, h[1] * 0.3f), a));
        Render2D.end(g);
        int ix = iconOnly ? x + (w - ICON) / 2 : x + 6;
        icon(g, profile, ix, y + (CHIP_H - ICON) / 2);
        if (!iconOnly) {
            int ink = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h[0]), 0xFFFFFFFF, h[1]);
            Text.draw(g, profile, ix + ICON + 4, y + (CHIP_H - Text.CAP) / 2, Theme.fade(ink, a));
        }
        hits.add(new Hit(new Rect(x, y, w, CHIP_H), () -> Visuals.switchTo(profile)));
    }

    private void button(GuiGraphics g, String key, String label, int x, int y, int w, int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = inside(mouseX, mouseY, x, y, w, BUTTON_H);
        float[] h = anim("button:" + key);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.button(g, x, y, w, BUTTON_H, fit(label, w - 4), h[0], true, a);
        hits.add(new Hit(new Rect(x, y, w, BUTTON_H), action));
    }

    // ---- The rail: what to browse, and the auto switch

    private void renderRail(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        List<String> rows = new ArrayList<>(List.of(ALL, ON));
        for (Visuals.Section section : Visuals.SECTIONS) rows.add(section.name());
        int body = PAD + rows.size() * RAIL_ROW + 7 + PAD;
        boolean hovered = inside(mouseX, mouseY, x, y, w, HEADER + body);
        Panel.draw(g, x, y, w, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, "Browse", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        boolean searching = !query().isEmpty();
        int ry = y + HEADER + PAD;
        for (String name : rows) {
            railRow(g, name, x + 3, ry, w - 6, searching, mouseX, mouseY, dt, a);
            ry += RAIL_ROW;
            if (name.equals(ON)) {
                // A line between the two views and the sections.
                g.fill(x + 8, ry + 3, x + w - 8, ry + 4, Theme.fade(Theme.BORDER, a));
                ry += 7;
            }
        }

        int ay = y + HEADER + body + GAP;
        int room = y + h - ay;
        List<String> hint1 = wrap("Picks the profile for what's in your hand.", w - 16);
        List<String> hint2 = wrap("When what you hold fits no profile.", w - 16);
        int autoBody = PAD + 14 + hint1.size() * LINE + 4 + 14 + hint2.size() * LINE + PAD;
        if (room < HEADER + autoBody) return;
        boolean overAuto = inside(mouseX, mouseY, x, ay, w, HEADER + autoBody);
        Panel.draw(g, x, ay, w, HEADER, autoBody, a, overAuto ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, "Auto switch", x + 8, ay + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        int ty = ay + HEADER + PAD;
        ty = switchRow(g, Visuals.AUTO, Visuals.AUTO.name, hint1, true, x + 4, ty, w - 8, mouseX, mouseY, dt, a);
        switchRow(g, Visuals.FALLBACK, Visuals.FALLBACK.name, hint2, Visuals.AUTO.get(), x + 4, ty + 4, w - 8, mouseX, mouseY, dt, a);
    }

    private void railRow(GuiGraphics g, String name, int x, int y, int w, boolean searching, int mouseX, int mouseY, float dt, float a) {
        boolean active = !searching && filter.equals(name);
        boolean over = inside(mouseX, mouseY, x, y, w, RAIL_ROW);
        float[] h = anim("rail:" + name);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (Float.isNaN(h[1])) h[1] = active ? 1 : 0;
        h[1] = Anim.approach(h[1], active ? 1 : 0, dt, 14);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, RAIL_ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h[0] * 9 * a));
        if (h[1] > 0.01f) {
            Render2D.rect(g, x * s, y * s, w * s, RAIL_ROW * s, 2 * s, Theme.alpha(accent, h[1] * 46 * a));
            g.fill(x * s, (y + 3) * s, x * s + Math.max(1, s), (y + RAIL_ROW - 3) * s, Theme.alpha(accent, h[1] * 230 * a));
        }
        Render2D.end(g);

        int total;
        int on = 0;
        if (name.equals(ALL)) {
            total = Visuals.GROUPS.size();
            for (Group group : Visuals.GROUPS) if (group.enabled.get()) on++;
        } else if (name.equals(ON)) {
            for (Group group : Visuals.GROUPS) if (group.enabled.get()) on++;
            total = on;
        } else {
            Visuals.Section section = section(name);
            total = section.groups().size();
            for (Group group : section.groups()) if (group.enabled.get()) on++;
        }
        String count = name.equals(ON) ? String.valueOf(on) : on > 0 ? on + "/" + total : String.valueOf(total);
        int countColor = on > 0 ? Theme.accentText() : Theme.TEXT_FAINT;
        int cw = Text.width(count);
        Text.draw(g, count, x + w - 5 - cw, y + (RAIL_ROW - Text.CAP) / 2, Theme.fade(countColor, a));

        icon(g, name, x + 5, y + (RAIL_ROW - ICON) / 2);
        int ink = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h[0]), Theme.TEXT, h[1]);
        Text.draw(g, fit(name, w - 5 - ICON - 5 - cw - 9), x + 5 + ICON + 5, y + (RAIL_ROW - Text.CAP) / 2, Theme.fade(ink, a));
        hits.add(new Hit(new Rect(x, y, w, RAIL_ROW), () -> {
            filter = name;
            search.clear();
            search.setFocused(false);
            grid.target = 0;
        }));
    }

    /** A switch with its name and a line or two on what it does; {@code usable} false greys it out. @return the y under it */
    private int switchRow(GuiGraphics g, BoolSetting setting, String name, List<String> hint, boolean usable, int x, int y, int w,
                          int mouseX, int mouseY, float dt, float a) {
        int h = 14 + hint.size() * LINE;
        boolean over = usable && inside(mouseX, mouseY, x, y, w, h);
        float[] k = anim(setting);
        k[0] = Anim.approach(k[0], over ? 1 : 0, dt, 16);
        if (Float.isNaN(k[1])) k[1] = setting.get() ? 1 : 0;
        k[1] = Anim.approach(k[1], setting.get() ? 1 : 0, dt, 14);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        float fa = a * (usable ? 1 : 0.45f);
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h * s, 2 * s, Theme.alpha(0xFFFFFF, k[0] * 9 * a));
        Render2D.end(g);
        Widgets.toggle(g, x + 4, y + 3, k[1], fa);
        Text.draw(g, fit(name, w - Widgets.TOGGLE_W - 14), x + Widgets.TOGGLE_W + 9, y + 3, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, Math.max(k[0], k[1])), fa));
        int ty = y + 14;
        for (String line : hint) {
            Text.draw(g, line, x + 4, ty, Theme.fade(Theme.TEXT_FAINT, fa));
            ty += LINE;
        }
        if (usable) hits.add(new Hit(new Rect(x, y, w, h), () -> setting.set(!setting.get())));
        return y + h;
    }

    // ---- The cards

    private List<Entry> entries() {
        String q = query();
        List<Entry> out = new ArrayList<>();
        if (!q.isEmpty()) {
            // What matches, in one grid: the inspector says where each is listed.
            Set<Group> seen = new HashSet<>();
            for (Visuals.Section section : Visuals.SECTIONS) {
                for (Group group : section.groups()) {
                    if (seen.add(group) && matches(group, q)) out.add(new Entry(null, null, group));
                }
            }
        } else if (filter.equals(ALL)) {
            // Each group once, under the first section that lists it.
            Set<Group> seen = new HashSet<>();
            for (Visuals.Section section : Visuals.SECTIONS) {
                List<Group> groups = new ArrayList<>();
                for (Group group : section.groups()) {
                    if (!seen.contains(group)) groups.add(group);
                }
                if (groups.isEmpty()) continue;
                seen.addAll(groups);
                out.add(new Entry(section, groups, null));
                for (Group group : groups) out.add(new Entry(null, null, group));
            }
        } else if (filter.equals(ON)) {
            for (Group group : Visuals.GROUPS) if (group.enabled.get()) out.add(new Entry(null, null, group));
        } else {
            Visuals.Section section = section(filter);
            if (section != null) for (Group group : section.groups()) out.add(new Entry(null, null, group));
        }
        return out;
    }

    private static boolean matches(Group group, String q) {
        StringBuilder text = new StringBuilder(group.name).append(' ').append(group.description).append(' ').append(group.keywords);
        for (String section : Visuals.sectionsOf(group)) text.append(' ').append(section);
        for (Setting<?> setting : group.settings) text.append(' ').append(setting.name);
        String hay = text.toString().toLowerCase(Locale.ROOT);
        for (String word : q.split("\\s+")) {
            if (!hay.contains(word)) return false;
        }
        return true;
    }

    private void renderGrid(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        boolean searching = !query().isEmpty();
        List<Entry> entries = entries();
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        Panel.draw(g, x, y, w, HEADER, h - HEADER, a, hovered ? 0.6f : 0.35f, 0, 1);

        int on = 0;
        int count = 0;
        for (Entry entry : entries) {
            if (entry.group() == null) continue;
            count++;
            if (entry.group().enabled.get()) on++;
        }
        String title = searching ? "Search" : filter;
        int tx = x + 8;
        if (!searching) {
            icon(g, filter, x + 6, y + (HEADER - ICON) / 2);
            tx = x + 6 + ICON + 5;
        }
        String right = !Visuals.live() ? "Stealth is holding visuals off" : searching ? count + (count == 1 ? " match" : " matches") : filter.equals(ON) ? on + " on" : on + " of " + count + " on";
        int rw = Text.width(right);
        Text.draw(g, right, x + w - 8 - rw, y + (HEADER - Text.CAP) / 2, Theme.fade(Visuals.live() ? Theme.TEXT_FAINT : Theme.DANGER, a));
        Text.draw(g, fit(title, x + w - 16 - rw - tx), tx, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        int fy = y + HEADER + PAD;
        search.render(g, x + PAD, fy, w - 2 * PAD, SEARCH_H, "Search visuals (Ctrl+F)...", mouseX, mouseY, dt, a);
        Widgets.magnifier(g, x + PAD + 9, fy + SEARCH_H / 2, Theme.fade(search.isFocused() ? Theme.accentText() : Theme.TEXT_DIM, a), a);

        grid.x = x + PAD;
        grid.y = fy + SEARCH_H + PAD;
        grid.w = w - 2 * PAD - 4;
        grid.h = y + h - PAD - grid.y;
        int cols = Math.max(1, (grid.w + CARD_GAP) / (CARD_MIN + CARD_GAP));
        int cardW = (grid.w - (cols - 1) * CARD_GAP) / cols;
        int thumbH = Math.max(46, Math.min(66, Math.round(cardW * 0.48f)));
        int cardH = thumbH + CARD_FOOT;

        // Lay everything out from the top of the content, then draw what's in view.
        cards.clear();
        List<Rect> headings = new ArrayList<>();
        List<Entry> headingEntries = new ArrayList<>();
        int cy = 0;
        int col = 0;
        for (Entry entry : entries) {
            if (entry.heading() != null) {
                if (col != 0) {
                    cy += cardH + CARD_GAP;
                    col = 0;
                }
                headings.add(new Rect(0, cy, grid.w, HEADING_H));
                headingEntries.add(entry);
                cy += HEADING_H;
                continue;
            }
            cards.add(new Card(entry.group(), new Rect(col * (cardW + CARD_GAP), cy, cardW, cardH)));
            if (++col == cols) {
                col = 0;
                cy += cardH + CARD_GAP;
            }
        }
        if (col != 0) cy += cardH + CARD_GAP;
        grid.content = Math.max(0, cy - CARD_GAP);

        if (reveal) {
            reveal = false;
            for (Card card : cards) {
                if (card.group() != focus) continue;
                if (card.at().y() < grid.target) grid.target = Math.max(0, card.at().y() - HEADING_H - 2);
                else if (card.at().y() + card.at().h() > grid.target + grid.h) grid.target = card.at().y() + card.at().h() - grid.h + 4;
                break;
            }
        }
        grid.update(dt);
        boolean overView = grid.over(mouseX, mouseY);
        int my = overView || draggingList ? mouseY : -10000;
        int top = grid.y - grid.offset();

        g.enableScissor(x + 1, grid.y, x + w - 1, grid.y + grid.h);
        for (int i = 0; i < headings.size(); i++) {
            Rect at = headings.get(i);
            int hy = top + at.y();
            if (hy + at.h() > grid.y && hy < grid.y + grid.h) heading(g, headingEntries.get(i), grid.x, hy, grid.w, a);
        }
        for (Card card : cards) {
            Rect at = card.at();
            int cardY = top + at.y();
            if (cardY + at.h() <= grid.y || cardY >= grid.y + grid.h) continue;
            card(g, card.group(), grid.x + at.x(), cardY, at.w(), thumbH, mouseX, my, dt, a);
        }
        if (cards.isEmpty()) {
            String[] lines = searching ? new String[]{"Nothing matches \"" + search.text().strip() + "\"", "Try fewer words, or a setting's name."}
                : filter.equals(ON) ? new String[]{"Nothing's switched on in " + Visuals.active() + " yet.", "Flip a card's switch to turn it on."}
                : new String[]{"Nothing here."};
            int ly = grid.y + 24;
            for (int i = 0; i < lines.length; i++) {
                String line = fit(lines[i], grid.w - 8);
                Text.draw(g, line, grid.x + (grid.w - Text.width(line)) / 2, ly, Theme.fade(i == 0 ? Theme.TEXT_DIM : Theme.TEXT_FAINT, a));
                ly += LINE + 2;
            }
        }
        g.disableScissor();
        scrollbar(g, grid, x + w - 3, a);
    }

    private void heading(GuiGraphics g, Entry entry, int x, int y, int w, float a) {
        Visuals.Section section = entry.heading();
        icon(g, section.name(), x + 1, y + (HEADING_H - ICON) / 2 - 1);
        // Only the cards under it: a group listed in two sections shows under the first.
        int on = 0;
        for (Group group : entry.under()) if (group.enabled.get()) on++;
        int tx = x + ICON + 6;
        int ty = y + (HEADING_H - Text.CAP) / 2 - 1;
        Text.draw(g, section.name(), tx, ty, Theme.fade(Theme.TEXT, a));
        int lx = tx + Text.width(section.name()) + 6;
        String count = on > 0 ? on + " on" : "";
        int cw = count.isEmpty() ? 0 : Text.width(count) + 6;
        if (!count.isEmpty()) Text.draw(g, count, x + w - Text.width(count), ty, Theme.fade(Theme.accentText(), a));
        // The section's blurb if it fits, then a rule to the edge.
        String blurb = section.blurb();
        int room = x + w - cw - lx;
        if (Text.width(blurb) + 10 <= room) {
            Text.draw(g, blurb, lx, ty, Theme.fade(Theme.TEXT_FAINT, a));
            lx += Text.width(blurb) + 6;
        }
        int s = Render2D.scale();
        int ly = (y + HEADING_H / 2 - 1) * s;
        if (x + w - cw - 4 > lx) {
            Render2D.pixels(g);
            Render2D.fadeRight(g, lx * s, ly, (x + w - cw - 4 - lx) * s, Math.max(1, s / 2), Theme.fade(Theme.BORDER, a));
            Render2D.end(g);
        }
    }

    private void card(GuiGraphics g, Group group, int x, int y, int w, int thumbH, int mouseX, int mouseY, float dt, float a) {
        int h = thumbH + CARD_FOOT;
        boolean over = inside(mouseX, mouseY, x, y, w, h);
        boolean selected = group == focus;
        boolean on = group.enabled.get();
        float[] k = anim("card:" + group.id);
        k[0] = Anim.approach(k[0], over ? 1 : 0, dt, 16);
        if (Float.isNaN(k[1])) k[1] = selected ? 1 : 0;
        k[1] = Anim.approach(k[1], selected ? 1 : 0, dt, 14);
        float t = toggleAnim(group, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 4) * s);
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, r, 7 * s, Theme.alpha(accent, (k[1] * 70 + k[0] * 18) * Theme.glow() * a));
        int edge = Theme.mix(Theme.mix(Theme.BORDER, Theme.EDGE, k[0]), accent, Math.max(k[1], t * 0.35f));
        Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.fade(edge, a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, r - 1), Theme.fade(Theme.mix(Theme.FIELD, Theme.HEADER, 0.4f + k[0] * 0.6f), a));
        Render2D.end(g);

        // The live picture, dimmed while the group is off.
        int tx = x + 2;
        int ty = y + 2;
        int tw = w - 4;
        int th = thumbH - 2;
        g.enableScissor(tx, ty, tx + tw, ty + th);
        if (a > 0.05f) Preview.draw(g, group, tx, ty, tw, th, a, true);
        float dim = on ? 0 : 0.55f * (1 - k[0] * 0.7f);
        if (dim > 0.01f) g.fill(tx, ty, tx + tw, ty + th, Theme.alpha(Theme.BODY, dim * 255 * a));
        g.disableScissor();
        g.fill(tx, ty + th, tx + tw, ty + th + 1, Theme.fade(Theme.mix(Theme.BORDER, accent, t * 0.6f), a));

        if (isChanged(group)) {
            // A dot: some setting is off its default in this profile.
            Render2D.pixels(g);
            Render2D.rect(g, (tx + 4) * s, (ty + 4) * s, 4 * s, 4 * s, 2 * s, Theme.fade(Theme.mix(accent, 0xFFFFFFFF, 0.3f), a));
            Render2D.end(g);
        }

        int fy = y + thumbH;
        int toggleX = x + w - 6 - Widgets.TOGGLE_W;
        Widgets.toggle(g, toggleX, fy + (CARD_FOOT - Widgets.TOGGLE_H) / 2, t, a);
        int ink = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, k[0]), Theme.TEXT, Math.max(t, k[1]));
        Text.draw(g, fit(group.name, toggleX - x - 10), x + 6, fy + (CARD_FOOT - Text.CAP) / 2, Theme.fade(ink, a));

        cardHits.add(new Hit(new Rect(toggleX - 5, fy, Widgets.TOGGLE_W + 10, CARD_FOOT), () -> {
            toggle(group);
            select(group);
        }));
        cardHits.add(new Hit(new Rect(x, y, w, h), () -> select(group)));
    }

    private float toggleAnim(Group group, float dt) {
        float[] t = anim("toggle:" + group.id);
        if (Float.isNaN(t[1])) {
            t[1] = 0;
            t[0] = group.enabled.get() ? 1 : 0;
        }
        // Cards and the inspector both draw the same switch: ease it once a frame.
        if (t[1] != frame) {
            t[1] = frame;
            t[0] = Anim.approach(t[0], group.enabled.get() ? 1 : 0, dt, 14);
        }
        return t[0];
    }

    private void scrollbar(GuiGraphics g, Scroll view, int right, float a) {
        if (view.content <= view.h) return;
        int s = Render2D.scale();
        int thumbH = Math.max(10, view.h * view.h / view.content);
        int thumbY = view.y + Math.round((view.h - thumbH) * view.at / Math.max(1, view.content - view.h));
        int tw = Math.max(2, s);
        Render2D.pixels(g);
        Render2D.rect(g, right * s - tw, thumbY * s, tw, thumbH * s, tw / 2, Theme.fade(Theme.TEXT_FAINT, a));
        Render2D.end(g);
    }

    // ---- The inspector: the picked group

    private SettingsList list(Group group) {
        return lists.computeIfAbsent(group, k -> new SettingsList(group.settings));
    }

    private void renderInspector(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        Group group = focus;
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        Panel.draw(g, x, y, w, HEADER, h - HEADER, a, hovered ? 0.6f : 0.35f, 0, 1);

        // The header: its name, and its switch.
        float t = toggleAnim(group, dt);
        int toggleX = x + w - 8 - Widgets.TOGGLE_W;
        int mid = y + HEADER / 2;
        String state = group.enabled.get() ? "On" : "Off";
        int sw = Text.width(state);
        boolean overToggle = inside(mouseX, mouseY, toggleX - sw - 10, y, w - (toggleX - sw - 10 - x), HEADER);
        if (overToggle) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.toggle(g, toggleX, mid - Widgets.TOGGLE_H / 2, t, a);
        Text.draw(g, state, toggleX - 6 - sw, mid - Text.CAP / 2, Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.accentText(), t), a));
        Text.draw(g, fit(group.name, toggleX - sw - 16 - x), x + 8, mid - Text.CAP / 2, Theme.fade(Theme.TEXT, a));
        hits.add(new Hit(new Rect(toggleX - sw - 10, y, x + w - (toggleX - sw - 10), HEADER), () -> toggle(group)));

        // The big preview.
        int px = x + PAD;
        int py = y + HEADER + PAD;
        int pw = w - 2 * PAD;
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        Render2D.pixels(g);
        Render2D.rect(g, px * s, py * s, pw * s, PREVIEW_H * s, r, Theme.fade(Theme.EDGE, a));
        Render2D.rect(g, px * s + 1, py * s + 1, pw * s - 2, PREVIEW_H * s - 2, Math.max(0, r - 1), Theme.fade(Theme.FIELD, a));
        Render2D.end(g);
        if (a > 0.05f) {
            g.enableScissor(px + 1, py + 1, px + pw - 1, py + PREVIEW_H - 1);
            Preview.draw(g, group, px + 1, py + 1, pw - 2, PREVIEW_H - 2, a);
            g.disableScissor();
        }

        // Under it, where it's on, and a button to try it when there is one.
        int sy = py + PREVIEW_H + 5;
        String action = Preview.action(group);
        int actionW = action != null ? Text.width(action) + 14 : 0;
        if (action != null) button(g, "act", action, x + w - PAD - actionW, sy - 2, actionW, mouseX, mouseY, dt, a, () -> Preview.act(group));
        String status;
        int color;
        if (!Visuals.live()) {
            status = "Stealth is holding visuals off";
            color = Theme.DANGER;
        } else if (group.enabled.get()) {
            status = "Showing in " + Visuals.active();
            color = Theme.accentText();
        } else {
            status = "Off in " + Visuals.active();
            color = Theme.TEXT_DIM;
        }
        Text.draw(g, fit(status, w - 16 - (actionW > 0 ? actionW + 4 : 0)), x + 8, sy + (BUTTON_H - Text.CAP) / 2 - 2, Theme.fade(color, a));

        // At the bottom: put it back, or copy it everywhere.
        int by = y + h - PAD - BUTTON_H;
        int half = (w - 2 * PAD - 4) / 2;
        boolean armed = System.currentTimeMillis() - resetGroupAt < 3000;
        button(g, "reset_group", armed ? "Click to confirm" : "Reset settings", x + PAD, by, half, mouseX, mouseY, dt, a, () -> {
            if (System.currentTimeMillis() - resetGroupAt < 3000) {
                Visuals.resetGroup(group);
                resetGroupAt = 0;
            } else {
                resetGroupAt = System.currentTimeMillis();
            }
        });
        button(g, "copy_group", "Copy to all profiles", x + PAD + half + 4, by, half, mouseX, mouseY, dt, a, () -> Visuals.copyGroupToAll(group));

        // Between them, scrolling: what it does, where it shows, and its settings.
        inspector.x = x + PAD;
        inspector.y = sy + BUTTON_H + 3;
        inspector.w = w - 2 * PAD - 4;
        inspector.h = by - 5 - inspector.y;
        g.fill(x + PAD, inspector.y - 2, x + w - PAD, inspector.y - 1, Theme.fade(Theme.BORDER, a));
        List<String> description = wrap(group.description, inspector.w - 8);
        List<String> onIn = Visuals.onIn(group);
        String where = onIn.isEmpty() ? "Off in every profile" : onIn.size() == Visuals.PROFILES.size() ? "On in every profile" : "On in " + String.join(", ", onIn);
        List<String> whereLines = wrap(where, inspector.w - 8);
        List<String> sections = Visuals.sectionsOf(group);
        List<String> listed = sections.size() > 1 ? wrap("Listed under " + String.join(", ", sections), inspector.w - 8) : List.of();
        SettingsList list = list(group);
        int textH = 3 + description.size() * LINE + 4 + whereLines.size() * LINE + listed.size() * LINE + 6;
        inspector.content = textH + list.height() + 4;
        inspector.update(dt);
        boolean overView = inspector.over(mouseX, mouseY);
        int my = overView || draggingList ? mouseY : -10000;

        g.enableScissor(inspector.x, inspector.y, inspector.x + inspector.w + 4, inspector.y + inspector.h);
        int cy = inspector.y - inspector.offset() + 3;
        for (String line : description) {
            Text.draw(g, line, inspector.x + 4, cy, Theme.fade(Theme.TEXT_DIM, a));
            cy += LINE;
        }
        cy += 4;
        for (String line : whereLines) {
            Text.draw(g, line, inspector.x + 4, cy, Theme.fade(onIn.isEmpty() ? Theme.TEXT_FAINT : Theme.accentText(), a));
            cy += LINE;
        }
        for (String line : listed) {
            Text.draw(g, line, inspector.x + 4, cy, Theme.fade(Theme.TEXT_FAINT, a));
            cy += LINE;
        }
        cy += 6;
        list.render(g, inspector.x, cy, inspector.w, mouseX, my, dt, a, my > -10000);
        shownList = list;
        g.disableScissor();
        scrollbar(g, inspector, x + w - 3, a);
    }

    // ---- Input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (search.mouseClicked(mouseX, mouseY)) return true;
        if (button == 0) {
            for (Hit hit : hits) {
                if (hit.at().contains(mouseX, mouseY)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (shownList != null && inspector.over(mouseX, mouseY) && shownList.mouseClicked(mouseX, mouseY, button)) {
            draggingList = shownList.isDragging();
            return true;
        }
        if (!grid.over(mouseX, mouseY)) return false;
        for (Hit hit : cardHits) {
            if (!hit.at().contains(mouseX, mouseY)) continue;
            if (button == 0) {
                hit.action().run();
                return true;
            }
            if (button == 1) {
                // Right click flips a card's switch from anywhere on it.
                for (Card card : cards) {
                    int y = grid.y - grid.offset() + card.at().y();
                    if (inside(mouseX, mouseY, grid.x + card.at().x(), y, card.at().w(), card.at().h())) {
                        toggle(card.group());
                        select(card.group());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        if (shownList != null) shownList.mouseDragged(mouseX);
    }

    @Override
    void mouseReleased(int button) {
        for (SettingsList list : lists.values()) list.mouseReleased();
        draggingList = false;
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (grid.over(mouseX, mouseY)) {
            grid.wheel(amount);
            return true;
        }
        if (inspector.over(mouseX, mouseY)) {
            inspector.wheel(amount);
            return true;
        }
        return false;
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
            grid.target = 0;
            return true;
        }
        if (!search.isFocused()) {
            // The arrows walk the cards and Enter flips the one you're on.
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                toggle(focus);
                return true;
            }
            int dx = key == GLFW.GLFW_KEY_LEFT ? -1 : key == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
            int dy = key == GLFW.GLFW_KEY_UP ? -1 : key == GLFW.GLFW_KEY_DOWN ? 1 : 0;
            if (dx != 0 || dy != 0) {
                step(dx, dy);
                return true;
            }
            return false;
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            // From the search box to the first match.
            search.setFocused(false);
            if (!cards.isEmpty()) {
                select(cards.getFirst().group());
                reveal = true;
            }
            return true;
        }
        boolean types = key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT;
        if (CyaniteClientMod.clickGuiKey().matches(event) && !types) return false;
        String before = search.text();
        boolean used = search.keyPressed(event);
        if (!before.equals(search.text())) grid.target = 0;
        return used;
    }

    /** Left and right go to the card before or after, row to row; up and down to the nearest card that way. */
    private void step(int dx, int dy) {
        Card from = null;
        for (Card card : cards) if (card.group() == focus) from = card;
        if (from == null) {
            if (!cards.isEmpty()) select(cards.getFirst().group());
            reveal = true;
            return;
        }
        if (dx != 0) {
            int next = cards.indexOf(from) + dx;
            if (next >= 0 && next < cards.size()) {
                select(cards.get(next).group());
                reveal = true;
            }
            return;
        }
        int fx = from.at().x() + from.at().w() / 2;
        int fy = from.at().y() + from.at().h() / 2;
        Card best = null;
        long bestScore = Long.MAX_VALUE;
        for (Card card : cards) {
            if (card == from) continue;
            int cx = card.at().x() + card.at().w() / 2;
            int cy = card.at().y() + card.at().h() / 2;
            int along = (cy - fy) * dy;
            int across = Math.abs(cx - fx);
            if (along <= 0) continue;
            // The same column first.
            long score = (long) along * along + 4L * across * across;
            if (score < bestScore) {
                bestScore = score;
                best = card;
            }
        }
        if (best != null) {
            select(best.group());
            reveal = true;
        }
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (!search.isFocused()) {
            // Typing anywhere searches, unless Inventory Move has you walking around with the menu open.
            if (Modules.get(InventoryMove.class).walksInClickGui()) return false;
            if (event.codepoint() == ' ') return false;
            search.setFocused(true);
        }
        boolean used = search.charTyped(event);
        grid.target = 0;
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

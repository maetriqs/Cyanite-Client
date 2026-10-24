package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.modules.render.Waypoints;
import com.cyanite.client.modules.render.Waypoints.Waypoint;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * The waypoint list: one slim panel near the top of the screen, the world still showing round
 * it, the same as Chest Index's search. Type a name and press Enter to drop a waypoint where you
 * stand, in the colour picked under the box. Pick one in the list (Up/Down or a click) and the
 * box and colours edit that one instead: Enter renames it, a colour recolours it, and its row
 * offers Hide and Delete. Esc steps back out of editing, then closes.
 */
public class WaypointScreen extends Screen {
    private static final int W = 320;
    private static final int PAD = 6;
    private static final int FIELD = 18;
    private static final int SWATCH = 12;
    private static final int GAP = 5;
    private static final int ROW = 16;
    private static final int ROWS = 8;
    private static final int BUTTON_H = 11;

    private final Waypoints waypoints;
    private final int openKey;
    private final TextField field = new TextField(32, cp -> cp >= 32 && cp != 127);
    private boolean skippedOpenKey;
    /** The colour for a new waypoint. */
    private int color;
    /** The one being edited, or -1 for a new one. */
    private int selected = -1;
    private int scroll;

    // Laid out each frame, for the mouse.
    private int x;
    private int swatchY;
    private int listY;
    private int hideX;
    private int deleteX;
    private int buttonW;
    private int deleteW;

    public WaypointScreen(Waypoints waypoints, int openKey) {
        super(Component.literal("Waypoints"));
        this.waypoints = waypoints;
        this.openKey = openKey;
        this.color = waypoints.nextColor();
        field.setFocused(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Nothing: the world (and the waypoints in it) stay in view.
    }

    private int unit() {
        return Hud.unit();
    }

    private double toUi(double gui) {
        return gui * minecraft.getWindow().getGuiScale() / unit();
    }

    private List<Waypoint> list() {
        return waypoints.list();
    }

    private Waypoint editing() {
        List<Waypoint> list = list();
        if (selected >= list.size()) selected = -1;
        return selected < 0 ? null : list.get(selected);
    }

    @Override
    public void render(GuiGraphics g, int guiMouseX, int guiMouseY, float partialTick) {
        List<Waypoint> list = list();
        Waypoint editing = editing();
        Window window = minecraft.getWindow();
        int uiW = window.getWidth() / unit();
        int uiH = window.getHeight() / unit();
        int mouseX = (int) toUi(guiMouseX);
        int mouseY = (int) toUi(guiMouseY);
        x = (uiW - W) / 2;
        int y = uiH / 6;
        int shown = Math.min(ROWS, list.size());
        int listH = Math.max(1, shown) * ROW;
        int h = PAD + FIELD + GAP + SWATCH + GAP + 2 + listH + GAP + Text.CAP + PAD + 1;

        Hud.begin(g);
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, W * s, h * s, 4 * s, 8 * s, Theme.alpha(accent, 50 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, W * s, h * s, 4 * s, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, W * s - 2, h * s - 2, 4 * s - 1, 0xF0101217);
        Render2D.end(g);

        // The name box: a new waypoint's, or the picked one's.
        String here = StreamerMode.mask(minecraft.player.getBlockX() + ", " + minecraft.player.getBlockY() + ", " + minecraft.player.getBlockZ());
        String hint = editing == null ? "Name a waypoint at " + here : "Rename " + editing.name;
        field.render(g, x + PAD, y + PAD, W - PAD * 2, FIELD, hint, mouseX, mouseY, 0.016f, 1);

        // The colours, and what Enter will do.
        swatchY = y + PAD + FIELD + GAP;
        int current = editing == null ? color : editing.color;
        Render2D.pixels(g);
        for (int i = 0; i < Waypoints.PALETTE.length; i++) {
            int sx = x + PAD + i * (SWATCH + 4);
            int rgb = 0xFF000000 | Waypoints.PALETTE[i];
            boolean picked = Waypoints.PALETTE[i] == current;
            boolean over = Page.inside(mouseX, mouseY, sx, swatchY, SWATCH, SWATCH);
            if (picked) {
                Render2D.glow(g, sx * s, swatchY * s, SWATCH * s, SWATCH * s, SWATCH * s / 2, 4 * s, Theme.alpha(rgb, 120 * Theme.glow()));
                Render2D.rect(g, sx * s, swatchY * s, SWATCH * s, SWATCH * s, SWATCH * s / 2, Theme.TEXT);
                int in = Math.max(2, s);
                Render2D.rect(g, sx * s + in, swatchY * s + in, SWATCH * s - 2 * in, SWATCH * s - 2 * in, SWATCH * s / 2 - in, rgb);
            } else {
                int in = (over ? 1 : 2) * s;
                Render2D.rect(g, sx * s + in, swatchY * s + in, SWATCH * s - 2 * in, SWATCH * s - 2 * in, SWATCH * s / 2 - in, Theme.fade(rgb, over ? 1 : 0.75f));
            }
        }
        Render2D.end(g);
        String mode = editing == null ? "New waypoint here" : "Editing " + fit(editing.name, 120);
        Text.draw(g, mode, x + W - PAD - Text.width(mode), swatchY + (SWATCH - Text.CAP) / 2, editing == null ? Theme.TEXT_FAINT : Theme.accentText());

        // A hairline, then the list.
        int lineY = swatchY + SWATCH + GAP;
        g.fill(x + PAD, lineY, x + W - PAD, lineY + 1, Theme.BORDER);
        listY = lineY + 2;
        if (list.isEmpty()) {
            Text.draw(g, "No waypoints yet. Press Enter to drop one where you stand", x + PAD + 2, listY + (ROW - Text.CAP) / 2, Theme.TEXT_FAINT);
        }
        scroll = Math.max(0, Math.min(scroll, list.size() - ROWS));
        for (int i = scroll; i < Math.min(list.size(), scroll + ROWS); i++) {
            row(g, list.get(i), i, listY + (i - scroll) * ROW, mouseX, mouseY);
        }
        if (list.size() > ROWS) {
            // A slim scrollbar down the right.
            int track = ROWS * ROW;
            int thumb = Math.max(8, track * ROWS / list.size());
            int ty = listY + (track - thumb) * scroll / Math.max(1, list.size() - ROWS);
            g.fill(x + W - 3, ty, x + W - 2, ty + thumb, Theme.TEXT_FAINT);
        }

        String foot = editing == null
            ? "Enter drops one here   Tab colour   Up/Down picks one   Esc closes"
            : "Enter renames   Tab colour   Ctrl+H hides   Ctrl+D deletes   Esc back";
        Text.draw(g, fit(foot, W - PAD * 2), x + PAD + 2, y + h - PAD - Text.CAP - 1, Theme.TEXT_FAINT);
        Hud.end(g);
    }

    /** One waypoint: its colour, name, coordinates and distance; Hide and Delete on the picked one. */
    private void row(GuiGraphics g, Waypoint wp, int index, int ry, int mouseX, int mouseY) {
        int s = Render2D.scale();
        boolean picked = index == selected;
        boolean over = Page.inside(mouseX, mouseY, x, ry, W, ROW);
        int rgb = 0xFF000000 | wp.color;
        float a = wp.hidden ? 0.45f : 1;
        Render2D.pixels(g);
        if (picked || over) {
            Render2D.rect(g, (x + 3) * s, ry * s, (W - 6) * s, ROW * s, 2 * s, Theme.alpha(wp.hidden ? Theme.accent() : rgb, picked ? 46 : 20));
        }
        int dot = 4 * s;
        int dx = (x + PAD + 5) * s;
        int dy = (ry + ROW / 2) * s;
        Render2D.glow(g, dx - dot, dy - dot, 2 * dot, 2 * dot, dot, 3 * s, Theme.alpha(rgb, 70 * a * Theme.glow()));
        Render2D.rect(g, dx - dot, dy - dot, 2 * dot, 2 * dot, dot, Theme.fade(rgb, a));
        Render2D.end(g);

        int ty = ry + (ROW - Text.CAP) / 2;
        int nameX = x + PAD + 14;
        Text.draw(g, fit(wp.name, 110), nameX, ty, Theme.fade(Theme.TEXT, a));
        String coords = StreamerMode.mask(wp.coords());
        if (!waypoints.fromHere(wp)) coords += "  " + Waypoints.dimensionName(wp.dimension);
        Text.draw(g, fit(coords, 120), nameX + 116, ty, Theme.fade(Theme.TEXT_FAINT, a));

        if (picked) {
            // Hide (or Show) and Delete, as little pills on the right.
            String hide = wp.hidden ? "Show" : "Hide";
            buttonW = Text.width(hide) + 8;
            deleteW = Text.width("Delete") + 8;
            deleteX = x + W - PAD - deleteW;
            hideX = deleteX - 4 - buttonW;
            int by = ry + (ROW - BUTTON_H) / 2;
            pill(g, hideX, by, buttonW, hide, Theme.accentText(), Page.inside(mouseX, mouseY, hideX, by, buttonW, BUTTON_H));
            pill(g, deleteX, by, deleteW, "Delete", Theme.DANGER, Page.inside(mouseX, mouseY, deleteX, by, deleteW, BUTTON_H));
        } else {
            Vec3 at = waypoints.where(wp);
            String far = at == null ? "elsewhere" : Waypoints.distance(at.distanceTo(minecraft.player.position()));
            Text.draw(g, far, x + W - PAD - Text.width(far), ty, Theme.fade(wp.hidden ? Theme.TEXT_FAINT : Theme.mix(rgb, 0xFFFFFFFF, 0.25f), a));
        }
    }

    private void pill(GuiGraphics g, int px, int py, int w, String label, int color, boolean over) {
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, px * s, py * s, w * s, BUTTON_H * s, BUTTON_H * s / 2, Theme.alpha(color, over ? 70 : 34));
        Render2D.end(g);
        Text.draw(g, label, px + 4, py + (BUTTON_H - Text.CAP) / 2, color);
    }

    private static String fit(String text, int room) {
        if (Text.width(text) <= room) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "...") > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    // ---- Doing things

    private void pick(int index) {
        List<Waypoint> list = list();
        selected = index < 0 || index >= list.size() ? -1 : index;
        if (selected < 0) {
            field.clear();
        } else {
            field.setText(list.get(selected).name);
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + ROWS) scroll = selected - ROWS + 1;
        }
    }

    private void setColor(int rgb) {
        Waypoint editing = editing();
        if (editing == null) {
            color = rgb;
        } else {
            editing.color = rgb;
            waypoints.changed();
        }
    }

    private void cycleColor(int step) {
        Waypoint editing = editing();
        int current = editing == null ? color : editing.color;
        int at = 0;
        for (int i = 0; i < Waypoints.PALETTE.length; i++) if (Waypoints.PALETTE[i] == current) at = i;
        setColor(Waypoints.PALETTE[Math.floorMod(at + step, Waypoints.PALETTE.length)]);
    }

    private void enter() {
        Waypoint editing = editing();
        if (editing == null) {
            waypoints.add(field.text(), color);
            field.clear();
            color = waypoints.nextColor();
            scroll = Math.max(0, list().size() - ROWS);
        } else {
            if (!field.text().isBlank()) {
                editing.name = field.text().strip();
                waypoints.changed();
            }
            pick(-1);
        }
    }

    private void toggleHidden() {
        Waypoint editing = editing();
        if (editing == null) return;
        editing.hidden = !editing.hidden;
        waypoints.changed();
    }

    private void delete() {
        Waypoint editing = editing();
        if (editing == null) return;
        waypoints.remove(editing);
        pick(-1);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean ctrl = event.hasControlDown() || minecraft.hasControlDown();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (selected >= 0) pick(-1);
            else onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            enter();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            cycleColor((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            pick(Math.min(list().size() - 1, selected + 1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            pick(selected - 1);
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_H) {
            toggleHidden();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_D) {
            delete();
            return true;
        }
        return field.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // The key that opened this would otherwise be typed straight into the box.
        if (!skippedOpenKey) {
            skippedOpenKey = true;
            String typed = event.codepointAsString().toUpperCase(Locale.ROOT);
            if (typed.length() == 1 && typed.charAt(0) == openKey) return true;
        }
        return field.charTyped(event) || super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = toUi(event.x());
        double my = toUi(event.y());
        for (int i = 0; i < Waypoints.PALETTE.length; i++) {
            if (Page.inside(mx, my, x + PAD + i * (SWATCH + 4), swatchY, SWATCH, SWATCH)) {
                setColor(Waypoints.PALETTE[i]);
                return true;
            }
        }
        List<Waypoint> list = list();
        for (int i = scroll; i < Math.min(list.size(), scroll + ROWS); i++) {
            int ry = listY + (i - scroll) * ROW;
            if (!Page.inside(mx, my, x, ry, W, ROW)) continue;
            int by = ry + (ROW - BUTTON_H) / 2;
            if (i == selected && Page.inside(mx, my, hideX, by, buttonW, BUTTON_H)) toggleHidden();
            else if (i == selected && Page.inside(mx, my, deleteX, by, deleteW, BUTTON_H)) delete();
            else pick(i == selected ? -1 : i);
            return true;
        }
        field.mouseClicked(mx, my);
        field.setFocused(true);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(list().size() - ROWS, scroll - (int) Math.signum(vertical)));
        return true;
    }
}

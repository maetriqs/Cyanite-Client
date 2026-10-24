package com.cyanite.client.gui;

import com.cyanite.client.config.Profiles;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ProfilesPage extends Page {
    private static final int W = 236;
    private static final int HEADER = 18;
    private static final int PAD = 6;
    private static final int FIELD = 14;
    private static final int ROW = 16;
    private static final int SAVE_W = 44;
    private static final float CONFIRM_TIME = 3;
    private static final float STATUS_TIME = 3;

    private final TextField name = new TextField(Profiles.MAX_NAME, Profiles::isNameChar);
    private final List<Button> buttons = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();

    private List<Profiles.Entry> entries = List.of();
    private int scroll;
    private int visibleRows;
    private int x;
    private int y;

    private String confirming;
    private float confirmLeft;
    private String status = "";
    private boolean statusError;
    private float statusLeft;

    private record Button(String key, int x, int y, int w, int h, Runnable action) {
    }

    @Override
    void onShow() {
        refresh();
    }

    private void refresh() {
        entries = Profiles.list();
        scroll = Math.max(0, Math.min(scroll, entries.size() - visibleRows));
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        x = (width - W) / 2;
        y = TOP + Math.round((1 - entrance()) * -6);
        buttons.clear();

        confirmLeft = Math.max(0, confirmLeft - dt);
        if (confirmLeft == 0) confirming = null;
        statusLeft = Math.max(0, statusLeft - dt);

        visibleRows = Math.max(3, (height - y - HEADER - PAD * 3 - FIELD - 40) / ROW);
        int rows = Math.max(1, Math.min(entries.size(), visibleRows));
        int body = PAD + FIELD + PAD + rows * ROW + PAD + 12;
        boolean hovered = inside(mouseX, mouseY, x, y, W, HEADER + body);

        Panel.draw(g, x, y, W, HEADER, body, a, hovered || name.isFocused() ? 0.6f : 0.35f, 0, 1);
        bounds = new Rect(x, y, W, HEADER + body);
        Text.draw(g, "Profiles", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        String open = "Open folder";
        int openX = x + W - 8 - Text.width(open);
        textButton(g, "open", open, openX, y + (HEADER - Text.CAP) / 2, mouseX, mouseY, dt, a,
            Theme.TEXT_FAINT, Theme.accentText(), this::openFolder);

        int fieldY = y + HEADER + PAD;
        int fieldW = W - PAD * 2 - SAVE_W - 4;
        name.render(g, x + PAD, fieldY, fieldW, FIELD, "New profile name", mouseX, mouseY, dt, a);
        saveButton(g, x + W - PAD - SAVE_W, fieldY, mouseX, mouseY, dt, a);

        int listY = fieldY + FIELD + PAD;
        if (entries.isEmpty()) {
            String empty = "No profiles yet";
            Text.draw(g, empty, x + (W - Text.width(empty)) / 2, listY + (ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        } else {
            g.enableScissor(x, listY, x + W, listY + rows * ROW);
            for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
                renderEntry(g, entries.get(scroll + i), listY + i * ROW, mouseX, mouseY, dt, a);
            }
            g.disableScissor();

            if (entries.size() > rows) renderScrollbar(g, listY, rows, a);
        }

        int statusY = listY + rows * ROW + PAD;
        if (statusLeft > 0) {
            float fade = Math.min(1, statusLeft / 0.4f);
            Text.draw(g, status, x + PAD + 2, statusY, Theme.fade(statusError ? Theme.DANGER : Theme.TEXT_DIM, a * fade));
        } else if (Profiles.active() != null) {
            Text.draw(g, "Active: " + Profiles.active(), x + PAD + 2, statusY, Theme.fade(Theme.TEXT_FAINT, a));
        }
    }

    private void renderEntry(GuiGraphics g, Profiles.Entry entry, int rowY, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        boolean active = entry.name().equals(Profiles.active());
        boolean over = inside(mouseX, mouseY, x + 3, rowY, W - 6, ROW);
        float h = hover("row:" + entry.name(), over, dt);

        Render2D.pixels(g);
        Render2D.rect(g, (x + 3) * s, rowY * s, (W - 6) * s, ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        if (active) {
            int accent = Theme.accent();
            int barW = Math.max(2, Math.round(1.5f * s));
            int barY = (rowY + 4) * s;
            int barH = (ROW - 8) * s;
            Render2D.fadeRight(g, (x + 3) * s, rowY * s, (W - 6) * s, ROW * s, Theme.alpha(accent, 50 * a));
            Render2D.glow(g, (x + 3) * s, barY, barW, barH, barW / 2, 5 * s, Theme.alpha(accent, 160 * Theme.glow() * a));
            Render2D.rect(g, (x + 3) * s, barY, barW, barH, barW / 2, Theme.fade(accent, a));
        }
        Render2D.end(g);

        int textY = rowY + (ROW - Text.CAP) / 2;
        int nameColor = active ? Theme.accentText() : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h);
        Text.draw(g, entry.name(), x + 10, textY, Theme.fade(nameColor, a));

        // Right to left: delete, save, load.
        String key = entry.name();
        boolean confirm = key.equals(confirming);
        int right = x + W - 10;

        String del = confirm ? "Sure?" : "Delete";
        right -= Text.width(del);
        textButton(g, "del:" + key, del, right, textY, mouseX, mouseY, dt, a,
            confirm ? Theme.DANGER : Theme.TEXT_FAINT, Theme.DANGER, () -> delete(key));

        right -= 10 + Text.width("Save");
        textButton(g, "save:" + key, "Save", right, textY, mouseX, mouseY, dt, a,
            Theme.TEXT_FAINT, Theme.accentText(), () -> save(key, true));

        right -= 10 + Text.width("Load");
        textButton(g, "load:" + key, "Load", right, textY, mouseX, mouseY, dt, a,
            Theme.TEXT_DIM, Theme.accentText(), () -> load(key));
    }

    private void renderScrollbar(GuiGraphics g, int listY, int rows, float a) {
        int s = Render2D.scale();
        int trackH = rows * ROW;
        int thumbH = Math.max(8, trackH * rows / entries.size());
        int maxScroll = entries.size() - rows;
        int thumbY = listY + (trackH - thumbH) * scroll / Math.max(1, maxScroll);
        int w = Math.max(2, s);

        Render2D.pixels(g);
        Render2D.rect(g, (x + W - 3) * s - w, thumbY * s, w, thumbH * s, w / 2, Theme.fade(Theme.TEXT_FAINT, a));
        Render2D.end(g);
    }

    private void saveButton(GuiGraphics g, int bx, int by, int mouseX, int mouseY, float dt, float a) {
        boolean over = inside(mouseX, mouseY, bx, by, SAVE_W, FIELD);
        float h = hover("savenew", over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();

        Render2D.pixels(g);
        Render2D.glow(g, bx * s, by * s, SAVE_W * s, FIELD * s, r, 6 * s, Theme.alpha(accent, (30 + h * 60) * Theme.glow() * a));
        Render2D.rect(g, bx * s, by * s, SAVE_W * s, FIELD * s, r, Theme.alpha(accent, (70 + h * 60) * a));
        Render2D.end(g);

        String label = "Save";
        Text.draw(g, label, bx + (SAVE_W - Text.width(label)) / 2, by + (FIELD - Text.CAP) / 2,
            Theme.fade(Theme.mix(Theme.accentText(), 0xFFFFFFFF, h), a));

        buttons.add(new Button("savenew", bx, by, SAVE_W, FIELD, this::saveNew));
    }

    private void textButton(GuiGraphics g, String key, String label, int bx, int by, int mouseX, int mouseY,
                            float dt, float a, int color, int hoverColor, Runnable action) {
        int w = Text.width(label);
        boolean over = inside(mouseX, mouseY, bx - 3, by - 3, w + 6, Text.CAP + 6);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        Text.draw(g, label, bx, by, Theme.fade(Theme.mix(color, hoverColor, h), a));
        buttons.add(new Button(key, bx - 3, by - 3, w + 6, Text.CAP + 6, action));
    }

    private float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    private void saveNew() {
        String clean = Profiles.sanitize(name.text());
        if (clean.isEmpty()) {
            setStatus("Type a name first", true);
            name.setFocused(true);
            return;
        }
        boolean exists = entries.stream().anyMatch(e -> e.name().equalsIgnoreCase(clean));
        if (save(clean, exists)) {
            name.clear();
            name.setFocused(false);
        }
    }

    private boolean save(String profile, boolean overwrite) {
        boolean ok = Profiles.save(profile);
        setStatus(ok ? (overwrite ? "Updated " : "Saved ") + profile : "Couldn't save " + profile, !ok);
        refresh();
        return ok;
    }

    private void load(String profile) {
        boolean ok = Profiles.load(profile);
        setStatus(ok ? "Loaded " + profile : "Couldn't read " + profile, !ok);
    }

    private void delete(String profile) {
        if (!profile.equals(confirming)) {
            confirming = profile;
            confirmLeft = CONFIRM_TIME;
            return;
        }
        confirming = null;
        boolean ok = Profiles.delete(profile);
        setStatus(ok ? "Deleted " + profile : "Couldn't delete " + profile, !ok);
        refresh();
    }

    private void openFolder() {
        try {
            Files.createDirectories(Profiles.dir());
            Util.getPlatform().openPath(Profiles.dir());
        } catch (IOException e) {
            setStatus("Couldn't open the folder", true);
        }
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusError = error;
        statusLeft = STATUS_TIME;
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean focused = name.mouseClicked(mouseX, mouseY);
        if (button != 0) return focused;

        for (Button b : List.copyOf(buttons)) {
            if (inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) {
                if (!b.key.startsWith("del:")) confirming = null;
                b.action.run();
                return true;
            }
        }
        confirming = null;
        return focused || inside(mouseX, mouseY, x, y, W, height);
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int max = Math.max(0, entries.size() - visibleRows);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        if (name.isFocused() && enter) {
            saveNew();
            return true;
        }
        return name.keyPressed(event);
    }

    @Override
    boolean isTyping() {
        return name.isFocused();
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return name.charTyped(event);
    }
}

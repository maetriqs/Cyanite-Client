package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.setting.BlockListSetting;
import com.cyanite.client.system.setting.ModListSetting;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders and edits a list of settings. Left click changes a value; right click
 * steps a mode backwards, or resets anything else to its default.
 */
final class SettingsList {
    private static final int SHORT = 14;
    private static final int TALL = 21;
    private static final int INSET = 4;

    // Only one bind can be waiting for a key, and one text value being typed into, across every list.
    private static KeybindSetting listening;
    private static StringSetting editing;

    private final List<Setting<?>> settings;
    private final Map<Setting<?>, float[]> anim = new IdentityHashMap<>();
    private Setting<?> dragging;
    private int x;
    private int y;
    private int w;

    SettingsList(List<Setting<?>> settings) {
        this.settings = settings;
    }

    boolean isEmpty() {
        return settings.isEmpty();
    }

    static int heightOf(Setting<?> setting) {
        return setting instanceof SliderSetting || setting instanceof ColorSetting ? TALL : SHORT;
    }

    int height() {
        int h = 0;
        for (Setting<?> setting : settings) h += heightOf(setting);
        return h;
    }

    void render(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, float dt, float alpha, boolean interactive) {
        this.x = x;
        this.y = y;
        this.w = w;

        int s = Render2D.scale();
        int rowY = y;

        for (Setting<?> setting : settings) {
            int h = heightOf(setting);
            float[] a = anim.computeIfAbsent(setting, k -> new float[]{0, Float.NaN});

            boolean over = interactive && (dragging == null || dragging == setting) && Page.inside(mouseX, mouseY, x, rowY, w, h);
            a[0] = Anim.approach(a[0], over ? 1 : 0, dt, 16);
            if (over) {
                if (setting instanceof SliderSetting || setting instanceof ColorSetting) g.requestCursor(CursorTypes.RESIZE_EW);
                else if (setting instanceof StringSetting) g.requestCursor(CursorTypes.IBEAM);
                else g.requestCursor(CursorTypes.POINTING_HAND);
            }

            Render2D.pixels(g);
            Render2D.rect(g, x * s, rowY * s, w * s, h * s, 2 * s, Theme.alpha(0xFFFFFF, a[0] * 10 * alpha));
            Render2D.end(g);

            int labelColor = setting.isRisky() ? Theme.DANGER : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, a[0]);
            Text.draw(g, setting.name, x + INSET, rowY + (SHORT - Text.CAP) / 2, Theme.fade(labelColor, alpha));

            if (setting instanceof BoolSetting bool) drawBool(g, bool, a, rowY, dt, alpha);
            else if (setting instanceof ModeSetting mode) drawMode(g, mode, a, rowY, alpha);
            else if (setting instanceof SliderSetting slider) drawSlider(g, slider, a, rowY, dt, alpha);
            else if (setting instanceof ColorSetting color) drawColor(g, color, rowY, alpha);
            else if (setting instanceof KeybindSetting bind) drawBind(g, bind, rowY, alpha);
            else if (setting instanceof StringSetting text) drawText(g, text, rowY, alpha);
            else if (setting instanceof BlockListSetting blocks) drawPicker(g, count(blocks.get().size(), "block", "None"), blocks.get().isEmpty(), a, rowY, alpha);
            else if (setting instanceof ModListSetting mods) drawPicker(g, count(mods.get().size(), "mod", "All hidden"), mods.get().isEmpty(), a, rowY, alpha);

            rowY += h;
        }
    }

    private void drawBool(GuiGraphics g, BoolSetting bool, float[] a, int rowY, float dt, float alpha) {
        float target = bool.get() ? 1 : 0;
        a[1] = Float.isNaN(a[1]) ? target : Anim.approach(a[1], target, dt, 14);
        Widgets.toggle(g, x + w - INSET - Widgets.TOGGLE_W, rowY + 3, a[1], alpha);
    }

    private void drawMode(GuiGraphics g, ModeSetting mode, float[] a, int rowY, float alpha) {
        int s = Render2D.scale();
        String value = mode.get();
        int arrowX = x + w - INSET - 3;
        int valueX = arrowX - 5 - Text.width(value);

        Text.draw(g, value, valueX, rowY + (SHORT - Text.CAP) / 2, Theme.fade(Theme.accentText(), alpha));

        Render2D.pixels(g);
        Render2D.chevron(g, arrowX * s, rowY * s + SHORT * s / 2, (float) (-Math.PI / 2),
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, a[0]), alpha));
        Render2D.end(g);
    }

    private void drawSlider(GuiGraphics g, SliderSetting slider, float[] a, int rowY, float dt, float alpha) {
        float target = (float) slider.fraction();
        a[1] = Float.isNaN(a[1]) || dragging == slider ? target : Anim.approach(a[1], target, dt, 22);

        String value = slider.display();
        Text.draw(g, value, x + w - INSET - Text.width(value), rowY + (SHORT - Text.CAP) / 2, Theme.fade(Theme.TEXT_DIM, alpha));

        int s = Render2D.scale();
        int accent = Theme.accent();
        int tx = (x + INSET) * s;
        int tw = (w - INSET * 2) * s;
        int th = Math.max(2, Math.round(1.5f * s));
        int ty = (rowY + 15) * s - th / 2;
        int filled = Math.round(tw * a[1]);
        int knob = 6 * s;
        int kx = Math.max(tx, Math.min(tx + tw - knob, tx + filled - knob / 2));
        int ky = (rowY + 15) * s - knob / 2;

        Render2D.pixels(g);
        Render2D.rect(g, tx, ty, tw, th, th / 2, Theme.fade(Theme.TRACK, alpha));
        Render2D.glow(g, tx, ty, filled, th, th / 2, 4 * s, Theme.alpha(accent, 70 * Theme.glow() * alpha));
        Render2D.rect(g, tx, ty, filled, th, th / 2, Theme.fade(accent, alpha));
        Render2D.glow(g, kx, ky, knob, knob, knob / 2, 4 * s, Theme.alpha(accent, (40 + a[0] * 60) * Theme.glow() * alpha));
        Render2D.rect(g, kx, ky, knob, knob, knob / 2, Theme.fade(0xFFFFFFFF, alpha));
        Render2D.end(g);
    }

    private static String count(int n, String one, String none) {
        return n == 0 ? none : n + " " + one + (n == 1 ? "" : "s");
    }

    /** "3 blocks" and an arrow: a click opens the picker. */
    private void drawPicker(GuiGraphics g, String value, boolean none, float[] a, int rowY, float alpha) {
        int s = Render2D.scale();
        int arrowX = x + w - INSET - 3;
        Text.draw(g, value, arrowX - 5 - Text.width(value), rowY + (SHORT - Text.CAP) / 2,
            Theme.fade(none ? Theme.TEXT_FAINT : Theme.accentText(), alpha));
        Render2D.pixels(g);
        Render2D.chevron(g, arrowX * s, rowY * s + SHORT * s / 2, (float) (-Math.PI / 2),
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, a[0]), alpha));
        Render2D.end(g);
    }

    private void drawBind(GuiGraphics g, KeybindSetting bind, int rowY, float alpha) {
        String text;
        int color;
        if (bind == listening) {
            text = "Press a key";
            float pulse = 0.55f + 0.45f * (float) Math.sin(System.nanoTime() / 1e9 * 6);
            color = Theme.fade(Theme.accentText(), pulse);
        } else {
            text = bind.display();
            color = bind.isBound() ? Theme.accentText() : Theme.TEXT_FAINT;
        }
        Text.draw(g, text, x + w - INSET - Text.width(text), rowY + (SHORT - Text.CAP) / 2, Theme.fade(color, alpha));
    }

    private void drawText(GuiGraphics g, StringSetting text, int rowY, float alpha) {
        boolean focused = text == editing;
        int left = x + INSET + Text.width(text.name) + 6;
        int right = x + w - INSET;
        int textY = rowY + (SHORT - Text.CAP) / 2;
        int end = focused ? right - 3 : right;

        String value = text.get();
        boolean showPlaceholder = value.isEmpty() && !focused;
        // Secret ones (webhooks) never show: dots while typing, then just that it's set.
        if (text.isSecret() && !value.isEmpty()) value = focused ? "•".repeat(Math.min(value.length(), 12)) : "set, hidden";
        String shown = fitTail(showPlaceholder ? text.placeholder() : value, Math.max(8, end - left));
        int color = showPlaceholder ? Theme.TEXT_FAINT : focused ? Theme.TEXT : Theme.accentText();
        Text.draw(g, shown, end - Text.width(shown), textY, Theme.fade(color, alpha));

        if (!focused) return;

        int s = Render2D.scale();
        int thickness = Math.max(1, s / 2);
        Render2D.pixels(g);
        g.fill(left * s, (rowY + SHORT - 2) * s, right * s, (rowY + SHORT - 2) * s + thickness,
            Theme.alpha(Theme.accent(), 150 * alpha));
        if (System.nanoTime() / 500_000_000L % 2 == 0) {
            int caret = end * s + s;
            g.fill(caret, (textY - 1) * s, caret + thickness, (textY + Text.CAP + 1) * s, Theme.fade(Theme.accentText(), alpha));
        }
        Render2D.end(g);
    }

    /** Keeps the end of the text visible, so you can see what you're typing. */
    private static String fitTail(String text, int maxWidth) {
        if (Text.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width("\u2026" + cut) > maxWidth) cut = cut.substring(1);
        return "\u2026" + cut;
    }

    private void drawColor(GuiGraphics g, ColorSetting color, int rowY, float alpha) {
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        int sw = 12 * s;
        int sh = 7 * s;
        int sx = (x + w - INSET - 12) * s;
        int sy = rowY * s + (SHORT * s - sh) / 2;

        int bx = (x + INSET) * s;
        int bw = (w - INSET * 2) * s;
        int by = (rowY + 13) * s;
        int bh = 4 * s;
        int marker = Math.max(2, s);
        int mx = bx + Math.round(color.hue() * bw) - marker / 2;

        Render2D.pixels(g);
        Render2D.glow(g, sx, sy, sw, sh, 2 * s, 5 * s, Theme.alpha(rgb, 80 * Theme.glow() * alpha));
        Render2D.rect(g, sx, sy, sw, sh, 2 * s, Theme.fade(rgb, alpha));

        Render2D.hueBar(g, bx, by, bw, bh, Theme.fade(0xFFFFFFFF, alpha));
        g.fill(mx - 1, by - s, mx + marker + 1, by + bh + s, Theme.fade(0xFF000000, alpha * 0.8f));
        g.fill(mx, by - s + 1, mx + marker, by + bh + s - 1, Theme.fade(0xFFFFFFFF, alpha));
        Render2D.end(g);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        int rowY = y;
        for (Setting<?> setting : settings) {
            int h = heightOf(setting);
            if (Page.inside(mouseX, mouseY, x, rowY, w, h)) {
                click(setting, mouseX, button);
                return true;
            }
            rowY += h;
        }
        return false;
    }

    private void click(Setting<?> setting, double mouseX, int button) {
        if (setting instanceof ModeSetting mode) {
            if (button == 0) mode.cycle(1);
            else if (button == 1) mode.cycle(-1);
            return;
        }
        if (button == 1) {
            setting.reset();
            if (setting == listening) listening = null;
            if (setting == editing) editing = null;
            return;
        }
        if (button != 0) return;

        if (setting instanceof BlockListSetting blocks) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new BlockPickerScreen(blocks, mc.screen));
        } else if (setting instanceof ModListSetting mods) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new ModPickerScreen(mods, mc.screen));
        } else if (setting instanceof KeybindSetting bind) {
            listening = bind == listening ? null : bind;
        } else if (setting instanceof StringSetting text) {
            editing = text == editing ? null : text;
        } else if (setting instanceof BoolSetting bool) {
            bool.toggle();
        } else if (setting instanceof SliderSetting || setting instanceof ColorSetting) {
            dragging = setting;
            mouseDragged(mouseX);
        }
    }

    void mouseDragged(double mouseX) {
        double fraction = (mouseX - (x + INSET)) / (w - INSET * 2);
        if (dragging instanceof SliderSetting slider) slider.setFraction(fraction);
        else if (dragging instanceof ColorSetting color) color.setHue((float) Math.max(0, Math.min(0.999, fraction)));
    }

    /** Feeds a key press to whatever is being edited. @return true if it was used. */
    static boolean captureKey(KeyEvent event) {
        if (editing != null) {
            int key = event.key();
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                editing = null;
            } else if (event.isPaste()) {
                editing.set(editing.get() + Minecraft.getInstance().keyboardHandler.getClipboard());
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !editing.get().isEmpty()) {
                String value = editing.get();
                editing.set(event.hasControlDown() ? value.replaceAll("\\S*\\s*$", "") : value.substring(0, value.length() - 1));
            }
            // Swallow the rest, so typing can't fire keybinds or close the menu.
            return true;
        }
        if (listening == null) return false;

        int key = event.key();
        if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) listening.set(KeybindSetting.NONE);
        else if (key != GLFW.GLFW_KEY_ESCAPE) listening.set(key);

        listening = null;
        return true;
    }

    /** Feeds a typed character to the text value being edited. */
    static boolean captureChar(CharacterEvent event) {
        if (editing == null) return false;
        editing.set(editing.get() + event.codepointAsString());
        return true;
    }

    static boolean isEditing() {
        return editing != null;
    }

    /** A Bind setting waiting for its key. */
    static boolean isListening() {
        return listening != null;
    }

    static void stopListening() {
        listening = null;
        editing = null;
    }

    void mouseReleased() {
        dragging = null;
    }

    boolean isDragging() {
        return dragging != null;
    }

    void resetAll() {
        for (Setting<?> setting : settings) setting.reset();
    }
}

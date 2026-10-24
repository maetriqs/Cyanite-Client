package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntPredicate;

/** Single line input. Only appends and deletes at the end, which is all a name box needs. */
final class TextField {
    private final int maxLength;
    private final IntPredicate allowed;
    private String text = "";
    private boolean focused;
    private float focus;
    private float caretTime;
    /** Where the text starts inside the box, leaving room for an icon. */
    private int padLeft = 5;
    private int x;
    private int y;
    private int w;
    private int h;

    TextField(int maxLength, IntPredicate allowed) {
        this.maxLength = maxLength;
        this.allowed = allowed;
    }

    String text() {
        return text;
    }

    void clear() {
        text = "";
    }

    void setText(String value) {
        StringBuilder sb = new StringBuilder();
        value.codePoints().filter(allowed).limit(maxLength).forEach(sb::appendCodePoint);
        text = sb.toString();
    }

    void setPadLeft(int pad) {
        padLeft = pad;
    }

    boolean isFocused() {
        return focused;
    }

    void setFocused(boolean focused) {
        this.focused = focused;
        caretTime = 0;
    }

    void render(GuiGraphics g, int x, int y, int w, int h, String placeholder, int mouseX, int mouseY, float dt, float alpha) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        boolean over = Page.inside(mouseX, mouseY, x, y, w, h);
        if (over) g.requestCursor(CursorTypes.IBEAM);
        focus = Anim.approach(focus, focused ? 1 : over ? 0.35f : 0, dt, 14);
        caretTime += dt;

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, r, 6 * s, Theme.alpha(accent, focus * 50 * Theme.glow() * alpha));
        Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.fade(Theme.mix(Theme.BORDER, accent, focus * 0.7f), alpha));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, r - 1), Theme.fade(Theme.FIELD, alpha));
        Render2D.end(g);

        int textY = y + (h - Text.CAP) / 2;
        int room = w - padLeft - 5;
        String shown = text;
        if (Text.width(shown) > room) shown = focused ? tail(text, room) : head(text, room);
        if (text.isEmpty() && !focused) {
            Text.draw(g, head(placeholder, room), x + padLeft, textY, Theme.fade(Theme.TEXT_FAINT, alpha));
        } else {
            Text.draw(g, shown, x + padLeft, textY, Theme.fade(Theme.TEXT, alpha));
        }

        if (focused && (caretTime % 1.0f) < 0.55f) {
            int cx = (x + padLeft + Text.width(shown)) * s + s;
            Render2D.pixels(g);
            g.fill(cx, (textY - 1) * s, cx + Math.max(1, s / 2), (textY + Text.CAP + 1) * s, Theme.fade(Theme.accentText(), alpha));
            Render2D.end(g);
        }
    }

    /** The end of the text that fits, so you see what you're typing. */
    private static String tail(String text, int room) {
        String cut = text;
        while (!cut.isEmpty() && Text.width("…" + cut) > room) cut = cut.substring(1);
        return "…" + cut;
    }

    private static String head(String text, int room) {
        if (Text.width(text) <= room) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "…") > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    boolean mouseClicked(double mouseX, double mouseY) {
        setFocused(Page.inside(mouseX, mouseY, x, y, w, h));
        return focused;
    }

    boolean keyPressed(KeyEvent event) {
        if (!focused) return false;

        if (event.isEscape()) {
            setFocused(false);
        } else if (event.isPaste()) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard());
        } else if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !text.isEmpty()) {
            text = event.hasControlDown() ? text.replaceAll("\\S*\\s*$", "") : text.substring(0, text.length() - 1);
            caretTime = 0;
        }
        // Swallow everything else so typing never triggers keybinds behind the field.
        return true;
    }

    boolean charTyped(CharacterEvent event) {
        if (!focused) return false;
        insert(event.codepointAsString());
        return true;
    }

    private void insert(String input) {
        StringBuilder sb = new StringBuilder(text);
        input.codePoints().filter(allowed).forEach(cp -> {
            if (sb.length() < maxLength) sb.appendCodePoint(cp);
        });
        text = sb.toString();
        caretTime = 0;
    }
}

package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The guided tour. Dims everything except the part being explained, outlines it, and shows a
 * card beside it. The tour always starts with {@link #chooser}: pick the classic menu or the Beta
 * UI, and the menu you pick gives its own tour.
 */
final class Tutorial {
    record Step(int tab, String title, String body, Supplier<Rect> target) {
    }

    private static final int W = 236;
    /** The chooser's card, wide enough for the two menus side by side. */
    private static final int CW = 372;
    private static final int OPTION_H = 132;
    private static final int HEADER = 18;
    private static final int PAD = 10;
    private static final int LINE = 11;
    private static final int BUTTON_H = 15;
    private static final int GAP = 10;
    private static final int DIM = 0xA8000000;

    private final List<Step> steps;
    private final IntConsumer showTab;
    private final Runnable onDone;
    /** Set for the chooser: told true for the Beta UI, false for the classic menu. */
    private final Consumer<Boolean> onChoose;
    private final boolean currentBeta;
    private final List<Button> buttons = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();

    /** -1 is the chooser. */
    private int index;
    /** The chooser's keyboard pick: 0 classic, 1 Beta. */
    private int pick;
    private boolean closing;
    private float shown;
    private float stepShown;
    private float spot;
    private float sx = Float.NaN;
    private float sy;
    private float sw;
    private float sh;
    private float cardX = Float.NaN;
    private float cardY;

    private record Button(int x, int y, int w, int h, Runnable action) {
    }

    /** A tour of one menu, from its first step. */
    Tutorial(List<Step> steps, IntConsumer showTab, Runnable onDone) {
        this(steps, showTab, onDone, null, false);
        showTab.accept(steps.getFirst().tab());
    }

    private Tutorial(List<Step> steps, IntConsumer showTab, Runnable onDone, Consumer<Boolean> onChoose, boolean currentBeta) {
        this.steps = steps;
        this.showTab = showTab;
        this.onDone = onDone;
        this.onChoose = onChoose;
        this.currentBeta = currentBeta;
        this.index = onChoose == null ? 0 : -1;
        this.pick = currentBeta ? 1 : 0;
    }

    /**
     * Where every tour starts: pick a menu. Picking calls {@code onChoose} (true for the Beta UI),
     * whose job is to switch to that menu and start its tour; "Not now" calls {@code onSkip}.
     */
    static Tutorial chooser(boolean currentBeta, Consumer<Boolean> onChoose, Runnable onSkip) {
        return new Tutorial(List.of(), tab -> {
        }, onSkip, onChoose, currentBeta);
    }

    boolean isFinished() {
        return closing && shown < 0.02f;
    }

    private void choose(int option) {
        if (closing) return;
        closing = true;
        onChoose.accept(option == 1);
    }

    private void go(int to) {
        if (to < 0) return;
        if (to >= steps.size()) {
            finish();
            return;
        }
        index = to;
        stepShown = 0;
        showTab.accept(steps.get(index).tab());
    }

    private void finish() {
        if (!closing) onDone.run();
        closing = true;
    }

    void render(GuiGraphics g, int width, int height, int mouseX, int mouseY, float dt, float alpha) {
        buttons.clear();
        shown = Anim.approach(shown, closing ? 0 : 1, dt, closing ? 20 : 12);
        stepShown = Anim.approach(stepShown, 1, dt, 14);
        float a = alpha * shown;
        if (index < 0) {
            renderChooser(g, width, height, mouseX, mouseY, dt, a);
            return;
        }

        Step step = steps.get(index);
        Rect target = step.target().get();
        boolean hasTarget = !target.isEmpty();
        spot = Anim.approach(spot, hasTarget ? 1 : 0, dt, 12);

        if (hasTarget) {
            Rect t = target.grow(4);
            // The highlight glides from one target to the next rather than jumping.
            if (Float.isNaN(sx)) {
                sx = t.x();
                sy = t.y();
                sw = t.w();
                sh = t.h();
            }
            sx = Anim.approach(sx, t.x(), dt, 12);
            sy = Anim.approach(sy, t.y(), dt, 12);
            sw = Anim.approach(sw, t.w(), dt, 12);
            sh = Anim.approach(sh, t.h(), dt, 12);
        }

        drawDim(g, width, height, a);

        List<String> lines = wrap(step.body(), W - PAD * 2);
        int bodyH = PAD + lines.size() * LINE + 14 + BUTTON_H + PAD;
        int cardH = HEADER + bodyH;
        float[] spotXY = placeCard(hasTarget, width, height, cardH);
        if (Float.isNaN(cardX)) {
            cardX = spotXY[0];
            cardY = spotXY[1];
        }
        cardX = Anim.approach(cardX, spotXY[0], dt, 12);
        cardY = Anim.approach(cardY, spotXY[1], dt, 12);

        int x = Math.round(cardX);
        int y = Math.round(cardY + (1 - Anim.easeOut(stepShown)) * 6);
        float ca = a * (0.4f + 0.6f * Anim.easeOut(stepShown));

        Panel.draw(g, x, y, W, HEADER, bodyH, ca, 0.85f, 0, 1);
        int textY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, step.title(), x + 9, textY, Theme.fade(Theme.TEXT, ca));
        String count = (index + 1) + " of " + steps.size();
        Text.draw(g, count, x + W - 9 - Text.width(count), textY, Theme.fade(Theme.TEXT_FAINT, ca));

        int cursor = y + HEADER + PAD;
        for (String line : lines) {
            Text.draw(g, line, x + PAD, cursor, Theme.fade(Theme.TEXT_DIM, ca));
            cursor += LINE;
        }
        drawDots(g, x + PAD, cursor + 3, ca);
        cursor += 14;

        boolean last = index == steps.size() - 1;
        String next = last ? "Finish" : "Next";
        int nextW = Math.max(46, Text.width(next) + 18);
        button(g, "next", x + W - PAD - nextW, cursor, nextW, next, true, mouseX, mouseY, dt, ca, () -> go(index + 1));
        if (index > 0) {
            int backW = Math.max(46, Text.width("Back") + 18);
            button(g, "back", x + W - PAD - nextW - 6 - backW, cursor, backW, "Back", false, mouseX, mouseY, dt, ca, () -> go(index - 1));
        }
        if (!last) textButton(g, "skip", "Skip tour", x + PAD, cursor, mouseX, mouseY, dt, ca, this::finish);
    }

    // ---- The chooser: which menu, then its tour

    private void renderChooser(GuiGraphics g, int width, int height, int mouseX, int mouseY, float dt, float a) {
        g.fill(0, 0, width, height, Theme.fade(DIM, a));
        List<String> lines = wrap("Two menus with the same features. Pick the one you like and a short tour shows you round it. "
            + "You can switch any time from the menu's settings.", CW - PAD * 2);
        int bodyH = PAD + lines.size() * LINE + 8 + OPTION_H + 12 + BUTTON_H + PAD;
        int x = (width - CW) / 2;
        int y = (height - HEADER - bodyH) / 2 + Math.round((1 - Anim.easeOut(shown)) * 8);

        Panel.draw(g, x, y, CW, HEADER, bodyH, a, 0.85f, 0, 1);
        Text.draw(g, "Welcome to Cyanite", x + 9, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        int cursor = y + HEADER + PAD;
        for (String line : lines) {
            Text.draw(g, line, x + PAD, cursor, Theme.fade(Theme.TEXT_DIM, a));
            cursor += LINE;
        }
        cursor += 8;

        int optionW = (CW - PAD * 2 - 8) / 2;
        boolean overAny = Page.inside(mouseX, mouseY, x + PAD, cursor, CW - PAD * 2, OPTION_H);
        option(g, 0, x + PAD, cursor, optionW, mouseX, mouseY, overAny, dt, a);
        option(g, 1, x + PAD + optionW + 8, cursor, optionW, mouseX, mouseY, overAny, dt, a);
        cursor += OPTION_H + 12;

        textButton(g, "later", "Not now", x + PAD, cursor, mouseX, mouseY, dt, a, this::finish);
        String keys = "Left/Right and Enter work too";
        Text.draw(g, keys, x + CW - PAD - Text.width(keys), cursor + (BUTTON_H - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
    }

    /** One menu to pick: a little drawing of it, its name, and what it's like. */
    private void option(GuiGraphics g, int which, int x, int y, int w, int mouseX, int mouseY, boolean overAny, float dt, float a) {
        boolean over = Page.inside(mouseX, mouseY, x, y, w, OPTION_H);
        float h = hover("option" + which, over || !overAny && pick == which, dt);
        if (over) {
            pick = which;
            g.requestCursor(CursorTypes.POINTING_HAND);
        }
        int s = Render2D.scale();
        int accent = Theme.accent();
        int r = Math.round(Math.max(3, Theme.radius()) * s);
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, OPTION_H * s, r, 7 * s, Theme.alpha(accent, 70 * h * Theme.glow() * a));
        Render2D.rect(g, x * s, y * s, w * s, OPTION_H * s, r, Theme.fade(Theme.mix(Theme.BORDER, accent, h), a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, OPTION_H * s - 2, Math.max(0, r - 1), Theme.fade(Theme.mix(Theme.FIELD, 0xFF151A24, h), a));
        Render2D.end(g);

        int px = x + 6;
        int py = y + 6;
        int pw = w - 12;
        int ph = 62;
        if (which == 0) drawClassic(g, px, py, pw, ph, a);
        else drawBeta(g, px, py, pw, ph, a);

        int ty = py + ph + 8;
        String name = which == 0 ? "Classic" : "Beta UI";
        Text.draw(g, name, x + 8, ty, Theme.fade(Theme.mix(Theme.TEXT, Theme.accentText(), h), a));
        if ((which == 1) == currentBeta) {
            String tag = "Current";
            int tw = Text.width(tag) + 8;
            int tx = x + w - 8 - tw;
            Render2D.pixels(g);
            Render2D.rect(g, tx * s, (ty - 2) * s, tw * s, (Text.CAP + 4) * s, 3 * s, Theme.alpha(accent, 50 * a));
            Render2D.end(g);
            Text.draw(g, tag, tx + 4, ty, Theme.fade(Theme.accentText(), a));
        }
        String about = which == 0
            ? "A window for each category. Drag them anywhere, right click a module for its settings."
            : "One window with search. Type to find any module or setting; favourites and what's on up top.";
        int ly = ty + LINE + 2;
        for (String line : wrap(about, w - 16)) {
            if (ly + Text.CAP > y + OPTION_H - 4) break;
            Text.draw(g, line, x + 8, ly, Theme.fade(Theme.TEXT_FAINT, a));
            ly += LINE - 1;
        }
        buttons.add(new Button(x, y, w, OPTION_H, () -> choose(which)));
    }

    /** The classic menu in miniature: three category windows side by side. */
    private static void drawClassic(GuiGraphics g, int x, int y, int w, int h, float a) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, Theme.fade(0xFF0A0C10, a));
        int ww = (w - 16) / 3;
        int[] tops = {6, 6, 6};
        int[] rows = {6, 4, 5};
        for (int i = 0; i < 3; i++) {
            int wx = x + 4 + i * (ww + 4);
            int wy = y + tops[i];
            int wh = 8 + rows[i] * 6;
            Render2D.rect(g, wx * s, wy * s, ww * s, wh * s, 2 * s, Theme.fade(Theme.BORDER, a));
            Render2D.rect(g, wx * s + 1, wy * s + 1, ww * s - 2, wh * s - 2, Math.max(0, 2 * s - 1), Theme.fade(Theme.BODY, a));
            g.fill(wx * s + 1, wy * s + 1, (wx + ww) * s - 1, (wy + 5) * s, Theme.fade(Theme.HEADER_HOVER, a));
            g.fill((wx + 2) * s, (wy + 5) * s - Math.max(1, s / 2), (wx + ww / 2) * s, (wy + 5) * s, Theme.alpha(accent, 220 * a));
            for (int r = 0; r < rows[i]; r++) {
                int ry = wy + 8 + r * 6;
                int len = ww - 6 - (r * 7 + i * 5) % 9;
                boolean on = (r + i) % 3 == 0;
                g.fill((wx + 3) * s, ry * s, (wx + 3 + len) * s, (ry + 2) * s, on ? Theme.alpha(accent, 200 * a) : Theme.alpha(0xFFFFFF, 45 * a));
            }
        }
        Render2D.end(g);
    }

    /** The Beta UI in miniature: search across the top, sidebar, module list, settings with their card. */
    private static void drawBeta(GuiGraphics g, int x, int y, int w, int h, float a) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, Theme.fade(0xFF0A0C10, a));
        int wx = x + 4;
        int wy = y + 4;
        int ww = w - 8;
        int wh = h - 8;
        Render2D.rect(g, wx * s, wy * s, ww * s, wh * s, 2 * s, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, wx * s + 1, wy * s + 1, ww * s - 2, wh * s - 2, Math.max(0, 2 * s - 1), Theme.fade(0xFF0E1015, a));
        // Top bar with the search box.
        Render2D.rect(g, (wx + ww / 3) * s, (wy + 2) * s, (ww / 2) * s, 4 * s, 2 * s, Theme.alpha(0xFFFFFF, 30 * a));
        g.fill(wx * s + 1, (wy + 8) * s, (wx + ww) * s - 1, (wy + 8) * s + Math.max(1, s / 2), Theme.alpha(accent, 160 * a));
        // Sidebar.
        int side = ww / 5;
        g.fill(wx * s + 1, (wy + 9) * s, (wx + side) * s, (wy + wh) * s - 1, Theme.fade(0xFF13161C, a));
        for (int i = 0; i < 6; i++) {
            int iy = wy + 12 + i * 6;
            boolean current = i == 2;
            if (current) Render2D.rect(g, (wx + 1) * s, (iy - 1) * s, (side - 2) * s, 4 * s, s, Theme.alpha(accent, 70 * a));
            g.fill((wx + 3) * s, iy * s, (wx + 3 + side / 2 + (i % 3)) * s, (iy + 2) * s, Theme.alpha(0xFFFFFF, (current ? 120 : 45) * a));
        }
        // Module list, one picked.
        int lx = wx + side + 3;
        int lw = (ww - side) / 2 - 4;
        for (int i = 0; i < 5; i++) {
            int ry = wy + 12 + i * 8;
            if (i == 1) Render2D.rect(g, (lx - 1) * s, (ry - 2) * s, (lw + 2) * s, 7 * s, s, Theme.alpha(accent, 55 * a));
            g.fill(lx * s, ry * s, (lx + lw - 10 - (i * 5) % 8) * s, (ry + 2) * s, Theme.alpha(0xFFFFFF, 70 * a));
            boolean on = i == 0 || i == 3;
            Render2D.rect(g, (lx + lw - 6) * s, ry * s, 5 * s, 3 * s, s, on ? Theme.alpha(accent, 230 * a) : Theme.alpha(0xFFFFFF, 40 * a));
        }
        // Settings: the module's card, then rows.
        int sx = lx + lw + 4;
        int sw = wx + ww - 3 - sx;
        Render2D.rect(g, sx * s, (wy + 11) * s, sw * s, 12 * s, 2 * s, Theme.alpha(0xFFB45CFF, 40 * a));
        g.fill(sx * s, (wy + 12) * s, sx * s + Math.max(1, s), (wy + 22) * s, Theme.alpha(0xFFB45CFF, 230 * a));
        g.fill((sx + 3) * s, (wy + 14) * s, (sx + sw / 2) * s, (wy + 16) * s, Theme.alpha(0xFFFFFF, 140 * a));
        for (int i = 0; i < 4; i++) {
            int ry = wy + 27 + i * 6;
            g.fill(sx * s, ry * s, (sx + sw / 2 - i) * s, (ry + 2) * s, Theme.alpha(0xFFFFFF, 45 * a));
            if (i == 1) {
                g.fill(sx * s, (ry + 3) * s, (sx + sw) * s, (ry + 3) * s + Math.max(1, s / 2), Theme.alpha(0xFFFFFF, 30 * a));
                g.fill(sx * s, (ry + 3) * s, (sx + sw * 3 / 5) * s, (ry + 3) * s + Math.max(1, s / 2), Theme.alpha(accent, 220 * a));
            }
        }
        Render2D.end(g);
    }

    /** Below the target if there's room, otherwise above, otherwise beside it. Centred with no target. */
    private float[] placeCard(boolean hasTarget, int width, int height, int cardH) {
        if (!hasTarget || spot < 0.05f) return new float[]{(width - W) / 2f, (height - cardH) / 2f};

        float x = sx + sw / 2 - W / 2f;
        float y = sy + sh + GAP;
        if (y + cardH > height - 8) y = sy - cardH - GAP;
        if (y < 8) {
            x = sx + sw + GAP;
            if (x + W > width - 8) x = sx - W - GAP;
            y = sy;
        }
        x = Math.max(8, Math.min(x, width - W - 8));
        y = Math.max(8, Math.min(y, height - cardH - 8));
        return new float[]{x, y};
    }

    private void drawDim(GuiGraphics g, int width, int height, float a) {
        int dim = Theme.fade(DIM, a);
        if (spot < 0.02f || Float.isNaN(sx)) {
            g.fill(0, 0, width, height, dim);
            return;
        }

        // Darken around the target, leaving it lit; the hole fades in and out with the spotlight.
        int left = Math.round(sx);
        int top = Math.round(sy);
        int right = Math.round(sx + sw);
        int bottom = Math.round(sy + sh);
        g.fill(0, 0, width, top, dim);
        g.fill(0, bottom, width, height, dim);
        g.fill(0, top, left, bottom, dim);
        g.fill(right, top, width, bottom, dim);
        g.fill(left, top, right, bottom, Theme.fade(DIM, a * (1 - spot)));

        int s = Render2D.scale();
        int accent = Theme.accent();
        float pulse = 0.75f + 0.25f * (float) Math.sin(System.nanoTime() / 1e9 * 3.2);
        int t = Math.max(1, s / 2);
        int px = left * s;
        int py = top * s;
        int pw = (right - left) * s;
        int ph = (bottom - top) * s;
        int line = Theme.alpha(accent, 230 * a * spot);

        Render2D.pixels(g);
        // Glow along the edges only: a glow over the whole target washed big ones (a whole column) in blue.
        int glow = Theme.alpha(accent, 85 * pulse * Theme.glow() * a * spot);
        int blur = 7 * s;
        Render2D.glow(g, px, py, pw, t, 0, blur, glow);
        Render2D.glow(g, px, py + ph - t, pw, t, 0, blur, glow);
        Render2D.glow(g, px, py + t, t, ph - 2 * t, 0, blur, glow);
        Render2D.glow(g, px + pw - t, py + t, t, ph - 2 * t, 0, blur, glow);
        g.fill(px, py, px + pw, py + t, line);
        g.fill(px, py + ph - t, px + pw, py + ph, line);
        g.fill(px, py + t, px + t, py + ph - t, line);
        g.fill(px + pw - t, py + t, px + pw, py + ph - t, line);
        Render2D.end(g);
    }

    private void drawDots(GuiGraphics g, int x, int y, float a) {
        int s = Render2D.scale();
        int d = Math.max(2, Math.round(2.5f * s));
        Render2D.pixels(g);
        for (int i = 0; i < steps.size(); i++) {
            int color = i == index ? Theme.accent() : i < index ? Theme.TEXT_DIM : Theme.TEXT_FAINT;
            int w = i == index ? d * 3 : d;
            int dx = x * s + i * (d * 2) + (i > index ? d * 2 : 0);
            Render2D.rect(g, dx, y * s, w, d, d / 2, Theme.fade(color, a));
        }
        Render2D.end(g);
    }

    private void button(GuiGraphics g, String key, int x, int y, int w, String label, boolean primary,
                        int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = Page.inside(mouseX, mouseY, x, y, w, BUTTON_H);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        if (primary) {
            Widgets.button(g, x, y, w, BUTTON_H, label, h, true, a);
        } else {
            int s = Render2D.scale();
            int r = Math.round(Math.min(Theme.radius(), 3) * s);
            Render2D.pixels(g);
            Render2D.rect(g, x * s, y * s, w * s, BUTTON_H * s, r, Theme.fade(Theme.mix(Theme.BORDER, Theme.accent(), h * 0.6f), a));
            Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, BUTTON_H * s - 2, Math.max(0, r - 1), Theme.fade(Theme.FIELD, a));
            Render2D.end(g);
            Text.draw(g, label, x + (w - Text.width(label)) / 2, y + (BUTTON_H - Text.CAP) / 2,
                Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        }
        buttons.add(new Button(x, y, w, BUTTON_H, action));
    }

    private void textButton(GuiGraphics g, String key, String label, int x, int y, int mouseX, int mouseY,
                            float dt, float a, Runnable action) {
        int w = Text.width(label);
        boolean over = Page.inside(mouseX, mouseY, x - 3, y, w + 6, BUTTON_H);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, label, x, y + (BUTTON_H - Text.CAP) / 2, Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, h), a));
        buttons.add(new Button(x - 3, y, w + 6, BUTTON_H, action));
    }

    private float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    /** Clicks never reach the page behind while the tour is up. */
    void mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || closing) return;
        for (Button b : List.copyOf(buttons)) {
            if (Page.inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) {
                b.action.run();
                return;
            }
        }
    }

    /** @return true if the key was a tour control. */
    boolean keyPressed(KeyEvent event) {
        if (closing) return false;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            finish();
            return true;
        }
        if (index < 0) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_TAB) pick = 1 - pick;
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) choose(pick);
            return true;
        }
        boolean forward = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_SPACE;
        if (forward) {
            go(index + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT && index > 0) {
            go(index - 1);
            return true;
        }
        return false;
    }

    private static List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (Text.width(candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}

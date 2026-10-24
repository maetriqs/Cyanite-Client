package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A searchable list of things, each switched on or off with a click, the way Meteor picks blocks.
 * With nothing typed, the ones switched on sit at the top. Block ESP's blocks and Anti Mod Check's
 * mods are both picked with it.
 */
public abstract class PickerScreen<E> extends Screen {
    private static final int W = 300;
    private static final int PAD = 6;
    private static final int FIELD = 18;
    private static final int ROW = 15;
    private static final int HEADER = 12;

    private final Screen parent;
    private final TextField field = new TextField(40, cp -> cp >= 32 && cp != 127);
    private String query;
    private List<E> shown = List.of();
    private int onShown;
    private float scroll;
    private int x;
    private int y;
    private int h;
    private int listTop;
    private int listH;

    protected PickerScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
        field.setFocused(true);
    }

    /** Everything that can be picked, in the order to show it. */
    protected abstract List<E> entries();

    protected abstract String name(E e);

    /** Also matched when searching (an id, say), besides the name. */
    protected abstract String searchText(E e);

    protected abstract boolean isOn(E e);

    protected abstract void toggle(E e);

    /** How many are on. */
    protected abstract int onCount();

    /** "3 blocks picked". */
    protected abstract String countText(int count);

    /** Switches them all off. */
    protected abstract void clearAll();

    protected String clearText() {
        return "Clear all";
    }

    protected abstract String placeholder();

    protected abstract String emptyText();

    protected abstract String hint();

    protected String onHeading() {
        return "Picked";
    }

    protected abstract String restHeading();

    /** Room left for an icon before the name; 0 for none. */
    protected int iconWidth() {
        return 0;
    }

    protected void drawIcon(GuiGraphics g, E e, int x, int y) {
    }

    /** Faint text by the switch, or null. */
    protected String note(E e) {
        return null;
    }

    /** What the list shows: matches for what's typed, or the ones on first and then the rest. */
    private void refilter() {
        query = field.text();
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<E> on = new ArrayList<>();
        List<E> rest = new ArrayList<>();
        for (E e : entries()) {
            if (!q.isEmpty() && !name(e).toLowerCase(Locale.ROOT).contains(q) && !searchText(e).toLowerCase(Locale.ROOT).contains(q)) continue;
            (q.isEmpty() && isOn(e) ? on : rest).add(e);
        }
        onShown = on.size();
        on.addAll(rest);
        shown = on;
        scroll = 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, Theme.TINT_TOP, Theme.TINT_BOTTOM);
    }

    private double toUi(double gui) {
        return gui * minecraft.getWindow().getGuiScale() / Hud.unit();
    }

    private boolean headings() {
        return query.isBlank() && onShown > 0;
    }

    /** Height of the rows, with the two headings when nothing's typed. */
    private int contentHeight() {
        return shown.size() * ROW + (headings() ? HEADER * 2 : 0);
    }

    /** With the Beta UI on, this matches it: a smooth system font instead of Minecraft's pixel one. */
    private <T> T styled(java.util.function.Supplier<T> action) {
        if (!GuiSettings.BETA.get()) return action.get();
        String previous = Text.using(Text.smoothFamily());
        try {
            return action.get();
        } finally {
            Text.using(previous);
        }
    }

    @Override
    public void render(GuiGraphics g, int guiMouseX, int guiMouseY, float partialTick) {
        styled(() -> {
            draw(g, guiMouseX, guiMouseY);
            return null;
        });
    }

    private void draw(GuiGraphics g, int guiMouseX, int guiMouseY) {
        if (!field.text().equals(query)) refilter();

        Window window = minecraft.getWindow();
        int unit = Hud.unit();
        int uiW = window.getWidth() / unit;
        int uiH = window.getHeight() / unit;
        int mouseX = (int) toUi(guiMouseX);
        int mouseY = (int) toUi(guiMouseY);
        h = Math.max(160, Math.min(uiH - 40, 360));
        x = (uiW - W) / 2;
        y = (uiH - h) / 2;
        listTop = y + PAD + HEADER + FIELD + 6;
        listH = y + h - PAD - HEADER - listTop;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight() - listH)));

        Hud.begin(g);
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, W * s, h * s, 4 * s, 8 * s, Theme.alpha(accent, 50 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, W * s, h * s, 4 * s, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, W * s - 2, h * s - 2, 4 * s - 1, 0xF0101217);
        Render2D.end(g);

        // Title, how many are on, and the switch-all-off link.
        int ty = y + PAD + (HEADER - Text.CAP) / 2 - 1;
        Text.draw(g, title.getString(), x + PAD + 2, ty, Theme.accentText());
        int count = onCount();
        String counted = countText(count);
        String clear = clearText();
        boolean overClear = count > 0 && Page.inside(mouseX, mouseY, clearX(), y + PAD - 2, Text.width(clear), HEADER);
        if (count > 0) {
            Text.draw(g, clear, clearX(), ty, overClear ? Theme.DANGER : Theme.TEXT_DIM);
            if (overClear) g.requestCursor(CursorTypes.POINTING_HAND);
        }
        Text.draw(g, counted, clearX() - (count > 0 ? 10 : 0) - Text.width(counted) + (count > 0 ? 0 : Text.width(clear)), ty, Theme.TEXT_FAINT);

        field.render(g, x + PAD, y + PAD + HEADER, W - PAD * 2, FIELD, placeholder(), mouseX, mouseY, 0.016f, 1);

        g.enableScissor(x, listTop, x + W, listTop + listH);
        int ry = listTop - Math.round(scroll);
        boolean headings = headings();
        for (int i = 0; i < shown.size(); i++) {
            if (headings && (i == 0 || i == onShown)) {
                if (ry + HEADER > listTop && ry < listTop + listH) {
                    Text.draw(g, i == 0 ? onHeading() : restHeading(), x + PAD + 2, ry + (HEADER - Text.CAP) / 2, Theme.TEXT_FAINT);
                }
                ry += HEADER;
            }
            if (ry + ROW > listTop && ry < listTop + listH) row(g, shown.get(i), ry, mouseX, mouseY);
            ry += ROW;
        }
        if (shown.isEmpty()) Text.draw(g, emptyText(), x + PAD + 2, listTop + 4, Theme.TEXT_FAINT);
        g.disableScissor();

        // A thin scrollbar when the list runs past the panel.
        int content = contentHeight();
        if (content > listH) {
            int barH = Math.max(12, listH * listH / content);
            int barY = listTop + Math.round((listH - barH) * scroll / (content - listH));
            Render2D.pixels(g);
            Render2D.rect(g, (x + W - 4) * s, barY * s, 2 * s, barH * s, s, Theme.alpha(0xFFFFFF, 40));
            Render2D.end(g);
        }

        Text.draw(g, hint(), x + PAD + 2, y + h - PAD - Text.CAP - 1, Theme.TEXT_FAINT);
        Hud.end(g);
    }

    private int clearX() {
        return x + W - PAD - 2 - Text.width(clearText());
    }

    private void row(GuiGraphics g, E e, int ry, int mouseX, int mouseY) {
        int s = Render2D.scale();
        boolean on = isOn(e);
        boolean over = mouseY >= listTop && mouseY < listTop + listH && Page.inside(mouseX, mouseY, x, ry, W, ROW);
        if (on || over) {
            Render2D.pixels(g);
            Render2D.rect(g, (x + 3) * s, ry * s, (W - 9) * s, ROW * s, 2 * s, Theme.alpha(Theme.accent(), on ? (over ? 46 : 30) : 16));
            Render2D.end(g);
        }
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        drawIcon(g, e, x + PAD + 1, ry);
        int textY = ry + (ROW - Text.CAP) / 2;
        int nameX = x + PAD + 2 + iconWidth();
        Text.draw(g, name(e), nameX, textY, on ? Theme.TEXT : Theme.TEXT_DIM);
        int toggleX = x + W - PAD - 4 - Widgets.TOGGLE_W;
        String note = note(e);
        if (note != null) Text.draw(g, note, toggleX - 6 - Text.width(note), textY, Theme.TEXT_FAINT);
        Widgets.toggle(g, toggleX, ry + (ROW - Widgets.TOGGLE_H) / 2, on ? 1 : 0, 1);
    }

    /** The entry under the mouse, or null. */
    private E at(double mx, double my) {
        if (my < listTop || my >= listTop + listH || mx < x || mx >= x + W) return null;
        int ry = listTop - Math.round(scroll);
        boolean headings = headings();
        for (int i = 0; i < shown.size(); i++) {
            if (headings && (i == 0 || i == onShown)) ry += HEADER;
            if (my >= ry && my < ry + ROW) return shown.get(i);
            ry += ROW;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return styled(() -> click(event));
    }

    private boolean click(MouseButtonEvent event) {
        double mx = toUi(event.x());
        double my = toUi(event.y());
        if (event.button() == 0 && onCount() > 0 && Page.inside(mx, my, clearX(), y + PAD - 2, Text.width(clearText()), HEADER)) {
            clearAll();
            return true;
        }
        E e = at(mx, my);
        if (e != null && event.button() == 0) {
            toggle(e);
            return true;
        }
        field.mouseClicked(mx, my);
        field.setFocused(true);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (float) (scrollY * ROW * 3);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return field.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return field.charTyped(event) || super.charTyped(event);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}

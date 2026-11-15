package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.mixin.AbstractContainerScreenAccessor;
import com.cyanite.client.modules.donut.AuctionSniper;
import com.cyanite.client.system.Stealth;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auction Sniper's panel, beside the auction house menu: the most you'll pay, what to look for, a
 * start button and how it's going. Typing in it never reaches the menu behind (E doesn't close it,
 * the number keys don't swap). Laid out in GUI units, drawn in framebuffer pixels.
 */
public final class SniperPanel extends AbstractWidget {
    private static final int PAD = 6;
    private static final int HEADER = 19;
    private static final int FIELD_H = 15;
    private static final int BUTTON_H = 17;
    private static final TextField PRICE = new TextField(16, cp -> cp < 128 && (Character.isDigit(cp) || ".,$kKmMbB".indexOf(cp) >= 0));
    private static final TextField ITEM = new TextField(64, cp -> cp >= 32 && cp != 127);
    private static SniperPanel current;

    static {
        PRICE.setPadLeft(12);
    }

    // Shared, not per panel: each refresh opens a new menu (and a new panel), and the look carries straight on.
    private static long lastFrame;
    private static float live;
    private static float hover;

    private final AbstractContainerScreen<?> screen;
    private int priceY;
    private int itemY;
    private int buttonY;

    private SniperPanel(AbstractContainerScreen<?> screen) {
        super(0, 0, 0, 0, Component.literal("Auction Sniper"));
        this.screen = screen;
    }

    /** An auction house menu opened, or was laid out again: the panel goes beside it. */
    public static void attach(AbstractContainerScreen<?> screen) {
        AuctionSniper sniper = AuctionSniper.get();
        if (sniper == null) return;
        SniperPanel panel = new SniperPanel(screen);
        current = panel;
        if (!PRICE.isFocused()) PRICE.setText(sniper.maxPrice.get());
        if (!ITEM.isFocused()) ITEM.setText(sniper.item.get());
        panel.layout();
        Screens.getButtons(screen).add(panel);
        ScreenEvents.beforeRender(screen).register((s, g, mouseX, mouseY, pt) -> panel.layout());
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> panel.clickedAnywhere(event));
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> panel.keyAnywhere(event));
        ScreenEvents.remove(screen).register(s -> {
            PRICE.setFocused(false);
            ITEM.setFocused(false);
            if (current == panel) current = null;
        });
    }

    /** Text is going into the panel: the movement keys are letters in it, not walking. */
    public static boolean typing() {
        SniperPanel panel = current;
        return panel != null && panel.visible && panel.screen == Minecraft.getInstance().screen && editing() != null;
    }

    private static TextField editing() {
        return PRICE.isFocused() ? PRICE : ITEM.isFocused() ? ITEM : null;
    }

    private static void blur() {
        PRICE.setFocused(false);
        ITEM.setFocused(false);
    }

    // ---- Where it goes

    /** Shown while the module's on (and not hidden by Stealth), left of the menu, as wide as there's room for. */
    private void layout() {
        AuctionSniper sniper = AuctionSniper.get();
        boolean shown = sniper.isEnabled() && !Stealth.active();
        visible = shown;
        active = shown;
        if (!shown) {
            if (current == this) blur();
            return;
        }
        AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) screen;
        int left = at.cyanite$leftPos();
        int w = Math.max(104, Math.min(136, left - 10));
        setX(Math.max(4, left - w - 6));
        setY(at.cyanite$topPos());
        setWidth(w);
        priceY = HEADER + 13;
        itemY = priceY + FIELD_H + 12;
        buttonY = itemY + FIELD_H + 8;
        setHeight(contentHeight(sniper, w));
    }

    private int contentHeight(AuctionSniper sniper, int w) {
        int y = buttonY + BUTTON_H + 7 + 34 + 6;
        y += Math.round(statusLines(sniper, w).size() * 7.5f);
        int recent = sniper.recent().size();
        if (recent > 0) y += 9 + recent * 8;
        return y + PAD;
    }

    // ---- Input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!visible || !isMouseOver(event.x(), event.y())) return false;
        AuctionSniper sniper = AuctionSniper.get();
        if (!sniper.running()) {
            boolean price = PRICE.mouseClicked(event.x(), event.y());
            boolean item = ITEM.mouseClicked(event.x(), event.y());
            if (price || item) return true;
        }
        if (Page.inside(event.x(), event.y(), getX() + PAD, getY() + buttonY, getWidth() - 2 * PAD, BUTTON_H)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            press();
        }
        // The rest of the panel takes the click too, so nothing behind it gets it.
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        TextField field = editing();
        if (field == null) return false;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            blur();
            press();
        } else if (key == GLFW.GLFW_KEY_TAB) {
            boolean toItem = field == PRICE;
            PRICE.setFocused(!toItem);
            ITEM.setFocused(toItem);
        } else {
            field.keyPressed(event);
            store();
        }
        // Every key goes to the text while typing: E doesn't close the menu, 1-9 don't swap slots.
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        TextField field = editing();
        if (field == null) return false;
        field.charTyped(event);
        store();
        return true;
    }

    /** A click anywhere in the menu, before the menu has it: outside the panel, it stops a run (you've taken over). */
    private boolean clickedAnywhere(MouseButtonEvent event) {
        if (visible && isMouseOver(event.x(), event.y())) return true;
        blur();
        AuctionSniper sniper = AuctionSniper.get();
        if (sniper.running()) sniper.stop("Stopped: you clicked in the menu", false);
        return true;
    }

    /** A key in the menu before the menu has it: Enter starts, closing it stops. */
    private boolean keyAnywhere(KeyEvent event) {
        if (!visible || editing() != null) return true;
        AuctionSniper sniper = AuctionSniper.get();
        if (sniper.running() && (event.isEscape() || Minecraft.getInstance().options.keyInventory.matches(event))) {
            sniper.stop("Stopped: menu closed", false);
        } else if (!sniper.running() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            press();
            return false;
        }
        return true;
    }

    private void press() {
        AuctionSniper sniper = AuctionSniper.get();
        if (sniper.running()) {
            sniper.stop("Stopped", false);
        } else {
            store();
            sniper.start();
        }
    }

    private static void store() {
        AuctionSniper sniper = AuctionSniper.get();
        sniper.maxPrice.set(PRICE.text());
        sniper.item.set(ITEM.text());
    }

    // ---- Drawing

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        AuctionSniper sniper = AuctionSniper.get();
        long now = Util.getMillis();
        boolean running = sniper.running();
        // Not drawn for a while (the menu was shut): start from how things are, not from how they were.
        if (now - lastFrame > 500) {
            live = running ? 1 : 0;
            hover = 0;
        }
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        live = Anim.approach(live, running ? 1 : 0, dt, 10);

        int s = Render2D.scale();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        int accent = Theme.accent();
        int r = Math.round(Math.min(Theme.radius(), 4) * s);

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, r, 8 * s, Theme.alpha(accent, (20 + live * 50) * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.mix(Theme.BORDER, accent, live * 0.55f));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, r - 1), Theme.BODY);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, HEADER * s, Math.max(0, r - 1), Theme.HEADER);
        g.fill(x * s + 1, (y + HEADER / 2) * s, (x + w) * s - 1, (y + HEADER) * s, Theme.HEADER);
        g.fill(x * s + 1, (y + HEADER) * s, (x + w) * s - 1, (y + HEADER) * s + Math.max(1, s / 2), Theme.BORDER);

        // The title, and whether it's running.
        Text.Face title = Text.face(true, Math.round(4.6f * s), null);
        int titleTop = Math.round((y + (HEADER - 4.6f) / 2) * s);
        int dot = Math.round(2.4f * s);
        int dotX = (x + PAD) * s;
        int dotY = titleTop + Math.round(2.3f * s) - dot / 2;
        float pulse = running ? 0.55f + 0.45f * (float) Math.sin(now / 160.0) : 0;
        if (running) Render2D.glow(g, dotX, dotY, dot, dot, dot / 2, 3 * s, Theme.alpha(accent, 120 * pulse));
        Render2D.rect(g, dotX, dotY, dot, dot, dot / 2, running ? accent : Theme.TEXT_FAINT);
        title.draw(g, "Auction Sniper", dotX + dot + Math.round(3.5f * s), titleTop, Theme.TEXT, 0.02f * title.width("A"));
        Text.Face pillFace = Text.face(true, Math.round(3.0f * s), null);
        String pill = running ? "LIVE" : "IDLE";
        float tracking = 0.12f * 3.0f * s;
        int pillW = Math.round(pillFace.width(pill, tracking) + 6 * s);
        int pillH = Math.round(8.5f * s);
        int pillX = (x + w - PAD) * s - pillW;
        int pillY = Math.round((y + (HEADER - 8.5f) / 2) * s);
        Render2D.rect(g, pillX, pillY, pillW, pillH, pillH / 2, running ? Theme.alpha(accent, 60) : Theme.KEY);
        pillFace.draw(g, pill, pillX + 3 * s, pillY + Math.round(2.75f * s), running ? Theme.accentText() : Theme.TEXT_FAINT, tracking);

        // The fields' labels, the price read back beside its own.
        Text.Face label = Text.face(true, Math.round(3.2f * s), null);
        float labelTrack = 0.1f * 3.2f * s;
        String priceLabel = sniper.perItem() ? "MAX PRICE EACH" : "MAX PRICE";
        label.draw(g, priceLabel, (x + PAD) * s, (y + priceY - 7) * s, Theme.TEXT_FAINT, labelTrack);
        double max = AuctionSniper.parse(PRICE.text());
        String read = PRICE.text().isBlank() ? "" : Double.isNaN(max) ? "not a price" : "$" + String.format(Locale.ROOT, "%,.0f", max);
        if (!read.isEmpty()) {
            label.draw(g, read, Math.round((x + w - PAD) * s - label.width(read)), (y + priceY - 7) * s,
                Double.isNaN(max) ? Theme.DANGER : Theme.TEXT_DIM);
        }
        label.draw(g, "ITEM", (x + PAD) * s, (y + itemY - 7) * s, Theme.TEXT_FAINT, labelTrack);
        Render2D.end(g);

        float fieldAlpha = 1 - live * 0.45f;
        PRICE.render(g, x + PAD, y + priceY, w - 2 * PAD, FIELD_H, "e.g. 150k", running ? -1 : mouseX, running ? -1 : mouseY, dt, fieldAlpha);
        ITEM.render(g, x + PAD, y + itemY, w - 2 * PAD, FIELD_H, "Any item", running ? -1 : mouseX, running ? -1 : mouseY, dt, fieldAlpha);
        Render2D.pixels(g);
        Text.Face dollar = Text.face(false, Math.round(3.8f * s), null);
        dollar.draw(g, "$", (x + PAD + 5) * s, Math.round((y + priceY + (FIELD_H - 3.8f) / 2) * s), Theme.fade(Theme.TEXT_DIM, fieldAlpha));

        // Start / stop.
        int bx = x + PAD;
        int bw = w - 2 * PAD;
        boolean over = Page.inside(mouseX, mouseY, bx, y + buttonY, bw, BUTTON_H);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        hover = Anim.approach(hover, over ? 1 : 0, dt, 16);
        int idleFill = Theme.mix(accent, 0xFFFFFFFF, hover * 0.12f);
        int stopFill = Theme.mix(Theme.mix(Theme.HEADER_HOVER, Theme.DANGER, 0.22f), Theme.DANGER, hover * 0.25f);
        int fill = Theme.mix(idleFill, stopFill, live);
        Render2D.glow(g, bx * s, (y + buttonY) * s, bw * s, BUTTON_H * s, r, 6 * s, Theme.alpha(accent, (1 - live) * (25 + hover * 40) * Theme.glow()));
        Render2D.rect(g, bx * s, (y + buttonY) * s, bw * s, BUTTON_H * s, r, fill);
        Text.Face buttonFace = Text.face(true, Math.round(4.0f * s), null);
        String text = running ? "Stop" : "Start sniping";
        int textColor = running ? Theme.mix(Theme.TEXT, Theme.DANGER, 0.25f) : 0xFF06121A;
        buttonFace.draw(g, text, Math.round((bx + bw / 2f) * s - buttonFace.width(text) / 2f), Math.round((y + buttonY + (BUTTON_H - 4f) / 2) * s), textColor);

        // How it's going.
        int top = y + buttonY + BUTTON_H + 7;
        g.fill((x + PAD) * s, top * s, (x + w - PAD) * s, top * s + Math.max(1, s / 2), Theme.BORDER);
        top += 5;
        int half = (w - 2 * PAD) / 2;
        String rate = sniper.refreshes() == 0 ? "-" : String.format(Locale.ROOT, "%.1f/s", sniper.refreshRate());
        String page = sniper.pageMs() < 0 ? "-" : Math.round(sniper.pageMs()) + " ms";
        double left = sniper.budgetLeft();
        stat(g, s, x + PAD, top, "BOUGHT", sniper.bought() + (sniper.missed() > 0 ? "  (" + sniper.missed() + " missed)" : ""));
        stat(g, s, x + PAD + half, top, Double.isNaN(left) ? "SPENT" : "SPENT · " + money(left) + " LEFT", money(sniper.spent()));
        stat(g, s, x + PAD, top + 16, "REFRESHING", rate);
        stat(g, s, x + PAD + half, top + 16, "PAGE", page);
        top += 34 + 6;

        Text.Face statusFace = Text.face(false, Math.round(3.6f * s), null);
        int statusColor = sniper.statusBad() ? Theme.DANGER : running ? Theme.accentText() : Theme.TEXT_DIM;
        for (String line : statusLines(sniper, w)) {
            statusFace.draw(g, line, (x + PAD) * s, top * s, statusColor);
            top = Math.round(top + 7.5f);
        }

        List<AuctionSniper.Bought> recent = sniper.recent();
        if (!recent.isEmpty()) {
            top += 2;
            label.draw(g, "RECENT", (x + PAD) * s, top * s, Theme.TEXT_FAINT, labelTrack);
            top += 7;
            Text.Face row = Text.face(false, Math.round(3.4f * s), null);
            for (AuctionSniper.Bought b : recent) {
                String price = money(b.price());
                int priceW = Math.round(row.width(price));
                row.draw(g, fit(row, b.what(), (w - 2 * PAD) * s - priceW - 4 * s), (x + PAD) * s, top * s, Theme.TEXT);
                row.draw(g, price, (x + w - PAD) * s - priceW, top * s, Theme.accentText());
                top += 8;
            }
        }
        Render2D.end(g);
    }

    private static void stat(GuiGraphics g, int s, int x, int y, String name, String value) {
        Text.Face small = Text.face(true, Math.round(2.9f * s), null);
        small.draw(g, name, x * s, y * s, Theme.TEXT_FAINT, 0.1f * 2.9f * s);
        Text.Face big = Text.face(true, Math.round(4.4f * s), null);
        big.draw(g, value, x * s, (y + 5) * s, Theme.TEXT);
    }

    /** The status, wrapped to the panel (two lines at most). */
    private static List<String> statusLines(AuctionSniper sniper, int w) {
        String status = sniper.status().isEmpty() ? "Type a price, then Start or Enter" : sniper.status();
        int s = Render2D.scale();
        Text.Face face = Text.face(false, Math.round(3.6f * s), null);
        int room = (w - 2 * PAD) * s;
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : status.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (face.width(next) > room && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > 2) {
            String last = lines.get(1) + " " + String.join(" ", lines.subList(2, lines.size()));
            lines = new ArrayList<>(List.of(lines.get(0), fit(face, last, room)));
        }
        return lines;
    }

    private static String fit(Text.Face face, String text, int room) {
        if (face.width(text) <= room) return text;
        String cut = text;
        while (!cut.isEmpty() && face.width(cut + "…") > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    private static String money(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "$%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.ROOT, "$%.1fK", v / 1e3);
        return String.format(Locale.ROOT, "$%.0f", v);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
    }
}

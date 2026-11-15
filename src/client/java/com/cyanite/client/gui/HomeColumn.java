package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.gui.render.Quads;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.mixin.AbstractWidgetAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A screen's buttons in Cyanite's look, as a column. The screen's own buttons and other mods' stay on the screen,
 * hidden, and each gets a stand-in that presses it: whatever a button did, it still does. A screen's own class
 * picks the buttons and places anything beyond the column; the stand-ins, Tab's order through them, and how a
 * button looks are here.
 */
abstract class HomeColumn {
    static final int LABEL = 7;
    static final int LINK = 6;
    static final int KEY = 5;
    static final int INSET = 12;

    enum Kind {
        /** One of the screen's buttons. */
        BUTTON,
        /** One of the screen's buttons, shown as a link in the footer. */
        LINK,
        /** Cyanite Menu. */
        MENU,
        /** Back onto the last server. */
        SERVER,
        /** The note after an update: opens what's new. */
        NEWS,
        /** Closes that note. */
        DISMISS,
        /** Your account, top right: opens Accounts. */
        ACCOUNT,
        /** Calls off the disconnected screen's countdown. */
        STOP
    }

    /** One row of the column: a button across it, or two halves. */
    record Line(float height, StandIn left, StandIn right) {
    }

    final Screen screen;
    private final Consumer<AbstractButton> add;
    private final Consumer<GuiEventListener> remove;
    final List<Line> lines = new ArrayList<>();
    final List<StandIn> standIns = new ArrayList<>();
    private final List<AbstractWidget> hidden = new ArrayList<>();
    private boolean styled;
    private int children;
    /** The column's height, in units. */
    float rows;

    Home.Metrics m;
    float dt;
    private long lastFrame;
    private long shownAt;
    String menuKey;

    HomeColumn(Screen screen, Consumer<AbstractButton> add, Consumer<GuiEventListener> remove) {
        this.screen = screen;
        this.add = add;
        this.remove = remove;
    }

    // ---- Standing in for the screen's buttons

    /** Makes the stand-ins with {@link #line}, {@link #ours} and {@link #corner}, in the order they read (Tab's order). */
    abstract void style();

    /** Something the stand-ins were made from has changed, besides the screen's own buttons: they're made again. */
    boolean changed() {
        return false;
    }

    private void restyle() {
        style();
        rows = -Home.ROW_GAP;
        for (Line line : lines) rows += line.height + Home.ROW_GAP;
        GuiEventListener focused = screen.getFocused();
        for (StandIn s : standIns) {
            add.accept(s);
            if (s.original != null && s.original == focused) screen.setFocused(s);
        }
        children = screen.children().size();
        styled = true;
    }

    void line(float height, Button left, Button right) {
        if (left == null) {
            left = right;
            right = null;
        }
        if (left == null) return;
        int index = lines.size();
        lines.add(new Line(height, standIn(left, Kind.BUTTON, index), right != null ? standIn(right, Kind.BUTTON, index) : null));
    }

    /** A row of Cyanite's own in the column. */
    StandIn ours(Kind kind, Component label, float height) {
        StandIn s = new StandIn(null, label, kind);
        s.index = lines.size();
        standIns.add(s);
        lines.add(new Line(height, s, null));
        return s;
    }

    /** One of Cyanite's own outside the column, arriving after it. */
    StandIn corner(Kind kind, Component label) {
        StandIn s = new StandIn(null, label, kind);
        s.index = lines.size();
        standIns.add(s);
        return s;
    }

    StandIn standIn(Button original, Kind kind, int index) {
        StandIn s = new StandIn(original, original.getMessage(), kind);
        s.index = index;
        // Why multiplayer is switched off, say.
        s.setTooltip(((AbstractWidgetAccessor) original).cyanite$tooltip().get());
        hide(original);
        standIns.add(s);
        return s;
    }

    /** One of the screen's widgets that's drawn Cyanite's way instead (the pause menu's title, say). */
    void hide(AbstractWidget widget) {
        widget.visible = false;
        hidden.add(widget);
    }

    /** Back to the screen's own buttons. */
    public void restore() {
        drop(true);
    }

    /** The screen has made its buttons again: the stand-ins went with the old ones. */
    public void forget() {
        drop(false);
    }

    private void drop(boolean refocus) {
        if (!styled) return;
        StandIn focused = screen.getFocused() instanceof StandIn s ? s : null;
        for (StandIn s : standIns) remove.accept(s);
        for (AbstractWidget widget : hidden) widget.visible = true;
        // The keyboard's place stays on the same button.
        if (refocus && focused != null && focused.original != null) screen.setFocused(focused.original);
        lines.clear();
        standIns.clear();
        hidden.clear();
        dropped();
        styled = false;
    }

    /** The stand-ins have gone: let go of any kept outside the column. */
    void dropped() {
    }

    // ---- Each frame

    /** Stand-ins for whatever buttons the screen has now, each in its place. */
    public void arrange() {
        // Another mod adds its button after the screen's own, some a moment later.
        if (!styled || screen.children().size() != children || changed()) {
            restore();
            restyle();
        }

        long now = System.nanoTime();
        dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f);
        lastFrame = now;
        KeyMapping key = CyaniteClientMod.clickGuiKey();
        menuKey = key == null || key.isUnbound() ? null : key.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
        frame();

        m = metrics();
        if (m == null) return;
        for (StandIn s : standIns) s.sync();

        int width = m.u(Home.COLUMN);
        int gap = m.u(Home.ROW_GAP);
        int x = m.cx() - width / 2;
        int y = columnTop();
        for (Line line : lines) {
            int h = m.u(line.height);
            if (line.right == null) {
                line.left.place(x, y, width, h);
            } else {
                int half = (width - gap) / 2;
                line.left.place(x, y, half, h);
                line.right.place(x + width - half, y, half, h);
            }
            y += h + gap;
        }
        placeMore();
    }

    /** Each frame before the layout. */
    void frame() {
    }

    /** This frame's measurements; null while the window has no size. */
    abstract Home.Metrics metrics();

    /** Where the column starts. */
    abstract int columnTop();

    /** Places what's outside the column. */
    void placeMore() {
    }

    /** 0 to 1 as the {@code index}th piece arrives: quickly, each time the screen opens. */
    float entrance(int index) {
        if (!Anim.enabled()) return 1;
        long now = Util.getMillis();
        if (shownAt == 0) shownAt = now;
        return Anim.easeOut((now - shownAt - index * 20L) / 160f);
    }

    // ---- Drawing a stand-in

    private void draw(GuiGraphics g, StandIn b) {
        if (m == null) return;
        b.hover = Anim.approach(b.hover, b.active && b.isHoveredOrFocused() ? 1 : 0, dt, 16);
        float in = entrance(b.index);
        if (in <= 0.004f) return;
        Home.begin(g, m);
        if (b.kind == Kind.BUTTON || b.kind == Kind.MENU || b.kind == Kind.SERVER) button(g, b, in);
        else drawOwn(g, b, in);
        Home.end(g);
    }

    /** One of Cyanite's own that isn't a button in the column. */
    void drawOwn(GuiGraphics g, StandIn b, float in) {
        button(g, b, in);
    }

    /** The edge and fill every button and card has, lit by {@code t} (hover). */
    void plate(GuiGraphics g, int x, int y, int w, int h, float t, boolean active, float in) {
        int radius = Math.round(Theme.radius() * 1.4f * m.s());
        int edge = Math.max(1, Math.round(m.s() / 2f));
        int accent = Theme.accent();
        if (t > 0.01f) Render2D.glow(g, x, y, w, h, radius, m.u(10), Theme.fade(accent, 0.28f * Math.min(Theme.glow(), 1.5f) * t * in));
        Render2D.rect(g, x, y, w, h, radius, Theme.fade(Theme.mix(Theme.BORDER, accent, t), in));
        Render2D.rect(g, x + edge, y + edge, w - 2 * edge, h - 2 * edge, Math.max(0, radius - edge),
            Theme.fade(active ? Theme.mix(Theme.HEADER, Theme.HEADER_HOVER, t) : Theme.FIELD, in));
    }

    private void button(GuiGraphics g, StandIn b, float in) {
        int x = b.px;
        int y = b.py + Math.round((1 - in) * m.u(8));
        plate(g, x, y, b.pw + b.trail, b.ph, b.hover, b.active, in);
        inside(g, b, x, y, b.pw, b.ph, in);
    }

    /** What's on a button: its label, and for Cyanite Menu the crystal and the key that also opens it. */
    void inside(GuiGraphics g, StandIn b, int x, int y, int w, int h, float in) {
        int cap = m.u(LABEL);
        int side = m.u(INSET);
        Text.Face face = Text.face(false, cap, null);
        if (b.kind == Kind.MENU) {
            int crystal = m.u(16);
            Home.crystal(g, x + side, y + (h - crystal) / 2, crystal, in, false);
            side += Home.crystalWidth(crystal) + m.u(6);
            if (menuKey != null) side = Math.max(side, m.u(8) + keyChip(g, menuKey, x + w - m.u(8), y + h / 2, in) + m.u(6));
        }
        String label = fit(face, b.label, w - 2 * side);
        face.draw(g, label, x + Math.round((w - face.width(label)) / 2f), y + (h - cap) / 2, Theme.fade(b.active ? Theme.TEXT : Theme.TEXT_FAINT, in));
    }

    /** A small key cap ending at {@code right}, centred on {@code midY}; how wide it is. */
    int keyChip(GuiGraphics g, String text, int right, int midY, float in) {
        int edge = Math.max(1, Math.round(m.s() / 2f));
        int keyCap = m.u(KEY);
        Text.Face small = Text.face(false, keyCap, null);
        float tracking = 0.08f * keyCap;
        int keyW = (int) Math.ceil(small.width(text, tracking));
        int chipW = keyW + 2 * m.u(5);
        int chipH = m.u(14);
        int chipX = right - chipW;
        int chipY = midY - chipH / 2;
        int chipR = Math.round(Theme.radius() * m.s());
        Render2D.rect(g, chipX, chipY, chipW, chipH, chipR, Theme.fade(Theme.EDGE, in));
        Render2D.rect(g, chipX + edge, chipY + edge, chipW - 2 * edge, chipH - 2 * edge, Math.max(0, chipR - edge), Theme.fade(Theme.KEY, in));
        small.draw(g, text, chipX + (chipW - keyW) / 2, chipY + (chipH - keyCap) / 2, Theme.fade(Theme.TEXT_DIM, in), tracking);
        return chipW;
    }

    /**
     * A row back onto {@code server}: its icon (or first letter), {@code verb} and its name, and on the right
     * {@code note} when there is one, otherwise how the server is doing if it's the one the title screen asks.
     */
    void serverRow(GuiGraphics g, ServerData server, String verb, String note, int x, int y, int w, int h, float in) {
        boolean asked = HomeServer.tracks(server);
        int cap = m.u(LABEL);
        Text.Face face = Text.face(false, cap, null);
        int side = m.u(INSET);
        int size = m.u(18);
        int iconX = x + side - m.u(3);
        int iconY = y + (h - size) / 2;
        Identifier icon = asked ? HomeServer.icon() : null;
        if (icon != null) {
            Quads q = Quads.of(g, icon, Theme.fade(0xFFFFFFFF, in));
            q.add(iconX, iconY, iconX + size, iconY + size, 0, 0, 1, 1);
            q.submit(g);
        } else {
            // No icon: its first letter on a tile.
            Render2D.rect(g, iconX, iconY, size, size, Math.round(Theme.radius() * m.s()), Theme.fade(Theme.FIELD, in));
            String letter = server.name.isBlank() ? "?" : server.name.strip().substring(0, 1).toUpperCase(Locale.ROOT);
            Text.Face big = Text.face(true, m.u(8), null);
            big.draw(g, letter, iconX + Math.round((size - big.width(letter)) / 2f), iconY + (size - m.u(8)) / 2, Theme.fade(Theme.TEXT_DIM, in));
        }

        // How it's doing, from the right.
        int right = x + w - side;
        Text.Face small = Text.face(false, m.u(LINK), null);
        int smallY = y + (h - m.u(LINK)) / 2;
        String state = note;
        if (state == null && asked) {
            if (HomeServer.answered()) {
                if (server.state() == ServerData.State.INCOMPATIBLE) state = "Other version";
                else {
                    right -= bars(g, right, y + h / 2, server.ping, false, in) + m.u(7);
                    if (server.players != null) state = String.format(Locale.ROOT, "%,d online", server.players.online());
                }
            } else if (HomeServer.offline()) {
                state = "Offline";
            } else {
                right -= bars(g, right, y + h / 2, 0, true, in) + m.u(7);
            }
        }
        if (state != null) {
            int sw = (int) Math.ceil(small.width(state));
            small.draw(g, state, right - sw, smallY, Theme.fade(note != null ? Theme.TEXT_DIM : Theme.TEXT_FAINT, in));
            right -= sw + m.u(8);
        }

        int textX = iconX + size + m.u(8);
        float verbW = face.width(verb);
        face.draw(g, verb, textX, y + (h - cap) / 2, Theme.fade(Theme.TEXT_DIM, in));
        int nameX = Math.round(textX + verbW + m.u(5));
        String name = fit(face, server.name, right - nameX);
        face.draw(g, name, nameX, y + (h - cap) / 2, Theme.fade(Theme.TEXT, in));
    }

    /** Four signal bars ending at {@code right}, as many lit as the ping deserves (a slow wave while asking); how wide. */
    int bars(GuiGraphics g, int right, int midY, long ping, boolean asking, float in) {
        int bw = Math.max(1, m.u(2));
        int gap = Math.max(1, m.u(1.5f));
        int total = 4 * bw + 3 * gap;
        int lit = ping < 150 ? 4 : ping < 300 ? 3 : ping < 600 ? 2 : 1;
        int wave = (int) (Util.getMillis() / 150 % 6);
        int bottom = midY + m.u(5);
        for (int i = 0; i < 4; i++) {
            int bh = m.u(3 + 2.5f * i);
            int bx = right - total + i * (bw + gap);
            boolean on = asking ? i == wave || i == wave - 1 : i < lit;
            int color = on ? (asking ? Theme.fade(Theme.accent(), 0.6f) : Theme.accent()) : Theme.TRACK;
            g.fill(bx, bottom - bh, bx + bw, bottom, Theme.fade(color, in));
        }
        return total;
    }

    /** {@code text}, cut short with an ellipsis if it's wider than {@code room}. */
    static String fit(Text.Face face, String text, int room) {
        if (face.width(text) <= room) return text;
        int end = text.length();
        while (end > 1 && face.width(text.substring(0, end).stripTrailing() + "…") > room) end--;
        return text.substring(0, end).stripTrailing() + "…";
    }

    /** What one of Cyanite's own does when pressed; the screen's buttons press themselves. */
    void press(StandIn b, InputWithModifiers input) {
        if (b.kind == Kind.MENU) Minecraft.getInstance().setScreen(ClickGuiScreen.open(screen));
    }

    /** A button of the screen's or one of Cyanite's, redrawn. Its place is kept in framebuffer pixels; the game gets the GUI box around that. */
    final class StandIn extends AbstractButton {
        /** Null for Cyanite's own. */
        final Button original;
        final Kind kind;
        int index;
        int px, py, pw, ph;
        int textX, textW;
        float hover;
        /** How far the plate runs on past the stand-in, under a control of its own (the countdown's Stop). */
        int trail;
        String label = "";
        private Component labelled;

        StandIn(Button original, Component message, Kind kind) {
            super(0, 0, 0, 0, message);
            this.original = original;
            this.kind = kind;
            sync();
        }

        /** Follows the button it stands for: a mod can switch its own off or reword it. */
        void sync() {
            if (original != null) {
                active = original.active;
                if (original.getMessage() != getMessage()) setMessage(original.getMessage());
            }
            if (getMessage() != labelled) {
                labelled = getMessage();
                label = label(this, labelled.getString().strip());
            }
        }

        void show(boolean on) {
            visible = on;
            active = on;
        }

        void place(int x, int y, int w, int h) {
            px = x;
            py = y;
            pw = w;
            ph = h;
            int scale = m.guiScale();
            setX(Math.floorDiv(x, scale));
            setY(Math.floorDiv(y, scale));
            setWidth(Math.ceilDiv(x + w, scale) - getX());
            setHeight(Math.ceilDiv(y + h, scale) - getY());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (original != null) original.onPress(input);
            else press(this, input);
        }

        @Override
        protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            draw(g, this);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /** The text a stand-in shows for its message. */
    String label(StandIn b, String text) {
        // "Language..." reads as a link without its dots.
        return b.kind == Kind.LINK ? text.replaceAll("(\\.{3}|…)$", "") : text;
    }
}

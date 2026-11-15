package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.mixin.DisconnectedScreenAccessor;
import com.cyanite.client.system.Disconnects;
import com.cyanite.client.system.Rejoin;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The disconnected screen in Cyanite's look: why you were let go under the crystal, a row back onto the server
 * and the screen's own buttons. After a dropped connection, a restart or a full server the row counts down and
 * puts you back on, three tries with longer waits; never after a ban, a kick, or a module leaving on purpose.
 * Stop, Back or Stealth calls it off, and a module that rejoins by itself (Auto Mine) keeps that to itself.
 */
public final class HomeDisconnect extends HomeColumn {
    private static final int MARK = 26, GAP_TITLE = 10, TITLE = 11, GAP_REASON = 9, REASON = 6, REASON_GAP = 4, GAP_ROWS = 22;
    /** How wide the reason runs, in units (just inside the narrowest the column gets). */
    private static final int WRAP = 268;
    private static final int MOST_LINES = 8;
    /** The reason is broken into lines at this size, so the breaks don't move with the window. */
    private static final int WRAP_CAP = 24;
    private static final int TRIES = 3;
    /** The first wait after a dropped connection; a busy server gets twice that, one restarting six times. Doubled each try. */
    private static final long WAIT = 5000;
    private static final int STOP_W = 46;

    /** The server last joined or tried; null for singleplayer and Realms. */
    private static ServerData target;
    /** Countdowns run out since you were last in a world. */
    private static int tries;
    /** A countdown has just connected: if that fails too, the next screen carries on counting. */
    private static boolean retrying;
    private static HomeDisconnect current;

    private final Screen parent;
    private final ServerData server;
    private final boolean fromWorld;
    private final String reason;
    private List<String> said;
    private long countFrom, fireAt;
    private StandIn row, stop;

    private HomeDisconnect(DisconnectedScreen screen) {
        super(screen, b -> Screens.getButtons(screen).add(b), l -> Screens.getButtons(screen).remove(l));
        DisconnectedScreenAccessor access = (DisconnectedScreenAccessor) screen;
        Component why = access.cyanite$details().reason();
        parent = access.cyanite$parent();
        server = target;
        fromWorld = screen.getTitle().getContents() instanceof TranslatableContents t && t.getKey().equals("disconnect.lost");
        reason = why.getString().replaceAll("§.", "");

        boolean carryOn = retrying;
        retrying = false;
        if (!carryOn) tries = 0;
        Disconnects.Kind kind = Disconnects.judge(why);
        boolean waitable = kind == Disconnects.Kind.DROPPED || kind == Disconnects.Kind.BUSY || kind == Disconnects.Kind.DOWN;
        if (GuiSettings.REJOIN.get() && Home.themed() && server != null && (fromWorld || carryOn) && waitable && tries < TRIES
            && !Disconnects.leftOnPurpose()) {
            countFrom = Util.getMillis();
            fireAt = countFrom + (WAIT * kind.scale << tries);
        }
    }

    /** The game made the disconnected screen, or made its buttons again. */
    public static void init(DisconnectedScreen screen) {
        HomeDisconnect home = current != null && current.screen == screen ? current : new HomeDisconnect(screen);
        home.forget();
        current = home;
        ScreenEvents.afterBackground(screen).register((s, g, mouseX, mouseY, partialTick) -> home.render(g));
        ScreenEvents.afterTick(screen).register(s -> home.tick());
    }

    /** About to join {@code server}: the row goes back there if it doesn't work out. */
    public static void connecting(ServerData server) {
        target = server != null && !server.isRealm() ? server : null;
    }

    /** In a world: the tries start again, and singleplayer has no row. */
    static void joined(Minecraft mc) {
        tries = 0;
        retrying = false;
        ServerData server = mc.getCurrentServer();
        if (mc.isLocalServer() || server == null || server.isRealm()) target = null;
    }

    // ---- Standing in for the screen's buttons

    @Override
    void style() {
        List<Button> buttons = new ArrayList<>();
        String title = screen.getTitle().getString();
        String text = ((DisconnectedScreenAccessor) screen).cyanite$details().reason().getString();
        for (GuiEventListener child : List.copyOf(screen.children())) {
            // The title and the reason are drawn with the crystal.
            if (child instanceof StringWidget s && s.visible && s.getMessage().getString().equals(title)) hide(s);
            else if (child instanceof MultiLineTextWidget s && s.visible && s.getMessage().getString().equals(text)) hide(s);
            else if (child instanceof Button b && b.visible && !(b instanceof PlainTextButton) && !b.getMessage().getString().isBlank()) buttons.add(b);
        }
        buttons.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));

        row = stop = null;
        if (server != null) {
            row = ours(Kind.SERVER, Component.literal(verb() + " " + server.name), Home.BIG);
            // Right after the row in Tab's order: it sits at the row's end.
            stop = corner(Kind.STOP, Component.literal("Stop rejoining"));
        }
        for (Button b : buttons) line(lines.isEmpty() ? Home.BIG : Home.ROW, b, null);
    }

    @Override
    void dropped() {
        row = stop = null;
    }

    private String verb() {
        return fromWorld ? "Rejoin" : "Try again";
    }

    // ---- Each frame

    private void render(GuiGraphics g) {
        if (!Home.themed()) {
            restore();
            return;
        }
        arrange();
        header(g);
    }

    private void tick() {
        if (fireAt == 0) return;
        // Stealth, the setting, or a module rejoining by itself: nothing counts down behind its back.
        if (!Home.themed() || !GuiSettings.REJOIN.get() || Rejoin.claimed(screen) || Minecraft.getInstance().screen != screen) {
            stopCounting();
            return;
        }
        if (Util.getMillis() >= fireAt) {
            fireAt = 0;
            tries++;
            retrying = true;
            join();
        }
    }

    private void join() {
        ConnectScreen.startConnecting(parent, Minecraft.getInstance(), ServerAddress.parseString(server.ip), server, false, null);
    }

    private void stopCounting() {
        fireAt = 0;
        if (stop != null && screen.getFocused() == stop) screen.setFocused(row);
    }

    @Override
    void frame() {
        if (server != null && HomeServer.get() != null && HomeServer.tracks(server)) HomeServer.update();
    }

    @Override
    Home.Metrics metrics() {
        if (said == null) said = wrap(reason);
        return Home.metrics(header(), rows);
    }

    /** From the crystal to the column, in units. */
    private float header() {
        float h = MARK + GAP_TITLE + TITLE + GAP_ROWS;
        if (!said.isEmpty()) h += GAP_REASON + said.size() * REASON + (said.size() - 1) * REASON_GAP;
        return h;
    }

    @Override
    int columnTop() {
        return m.top() + m.u(header());
    }

    /** While it counts down, Stop sits at the row's end, on the row's plate. */
    @Override
    void placeMore() {
        if (row == null) return;
        boolean counting = fireAt > 0;
        stop.show(counting);
        row.trail = 0;
        if (!counting) return;
        int w = m.u(STOP_W);
        row.place(row.px, row.py, row.pw - w, row.ph);
        row.trail = w;
        stop.place(row.px + row.pw, row.py, w, row.ph);
    }

    @Override
    void press(StandIn b, InputWithModifiers input) {
        if (b.kind == Kind.SERVER) {
            // Straight away instead of waiting: it still counts as a try.
            if (fireAt > 0) {
                tries++;
                retrying = true;
                fireAt = 0;
            }
            join();
        } else if (b.kind == Kind.STOP) {
            stopCounting();
        } else {
            super.press(b, input);
        }
    }

    // ---- Drawing

    /** The crystal, the screen's title and why, over the backdrop. */
    private void header(GuiGraphics g) {
        if (m == null) return;
        Home.begin(g, m);
        float in = entrance(0);
        int h = m.u(MARK);
        int y = m.top() - Math.round((1 - in) * m.u(6));
        Home.crystal(g, m.cx() - Home.crystalWidth(h) / 2, y, h, in, true);

        int cap = m.u(TITLE);
        String title = screen.getTitle().getString();
        Text.Face bold = Text.face(true, cap, null);
        float tracking = 0.04f * cap;
        y += m.u(MARK + GAP_TITLE);
        bold.draw(g, title, Math.round(m.cx() - bold.width(title, tracking) / 2f), y, Theme.fade(Theme.TEXT, in), tracking);

        Text.Face face = Text.face(false, m.u(REASON), null);
        y += m.u(TITLE + GAP_REASON);
        for (String line : said) {
            face.draw(g, line, Math.round(m.cx() - face.width(line) / 2f), y, Theme.fade(Theme.TEXT_DIM, in));
            y += m.u(REASON + REASON_GAP);
        }
        Home.end(g);
    }

    @Override
    void inside(GuiGraphics g, StandIn b, int x, int y, int w, int h, float in) {
        if (b.kind != Kind.SERVER) {
            super.inside(g, b, x, y, w, h, in);
            return;
        }
        String note = null;
        if (fireAt > 0) {
            long now = Util.getMillis();
            note = "in " + Math.max(1, (fireAt - now + 999) / 1000) + " s";
            // Along the bottom of the plate, filling as the wait runs out.
            float done = Math.clamp((now - countFrom) / (float) (fireAt - countFrom), 0, 1);
            int edge = Math.max(1, Math.round(m.s() / 2f));
            int inset = Math.round(Theme.radius() * 1.4f * m.s());
            int thick = Math.max(1, m.u(1.5f));
            int full = b.pw + b.trail - 2 * inset;
            int barY = y + h - edge - thick;
            g.fill(x + inset, barY, x + inset + full, barY + thick, Theme.fade(Theme.TRACK, 0.6f * in));
            g.fill(x + inset, barY, x + inset + Math.round(full * done), barY + thick, Theme.fade(Theme.accent(), in));
        } else if (tries >= TRIES) {
            note = "Tried " + TRIES + " times";
        }
        serverRow(g, server, verb(), note, x, y, w, h, in);
    }

    @Override
    void drawOwn(GuiGraphics g, StandIn b, float in) {
        if (b.kind != Kind.STOP) {
            super.drawOwn(g, b, in);
            return;
        }
        float rowIn = entrance(row.index);
        int y = b.py + Math.round((1 - rowIn) * m.u(8));
        int line = Math.max(1, Math.round(m.s() / 2f));
        int pad = m.u(9);
        g.fill(b.px, y + pad, b.px + line, y + b.ph - pad, Theme.fade(Theme.BORDER, rowIn));
        Text.Face face = Text.face(false, m.u(LINK), null);
        String text = "Stop";
        float t = b.hover;
        face.draw(g, text, b.px + Math.round((b.pw - face.width(text)) / 2f), y + (b.ph - m.u(LINK)) / 2,
            Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, t), rowIn));
        if (t > 0.01f) {
            int tw = Math.round(face.width(text));
            int tx = b.px + (b.pw - tw) / 2;
            int ty = y + (b.ph + m.u(LINK)) / 2 + m.u(2);
            Render2D.rect(g, tx, ty, tw, line, 0, Theme.fade(Theme.accent(), t * rowIn));
        }
    }

    // ---- The reason, in lines

    /** The reason broken into centred lines no wider than {@link #WRAP}, its own line breaks kept; at most {@link #MOST_LINES}. */
    private static List<String> wrap(String text) {
        Text.Face face = Text.face(false, WRAP_CAP, null);
        float room = WRAP * WRAP_CAP / (float) REASON;
        List<String> out = new ArrayList<>();
        for (String paragraph : text.strip().split("\\R")) {
            paragraph = paragraph.strip().replaceAll("\\s+", " ");
            if (paragraph.isEmpty()) {
                // One blank line between paragraphs, however many the server sent.
                if (!out.isEmpty() && !out.getLast().isEmpty()) out.add("");
                continue;
            }
            StringBuilder lineSoFar = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String tried = lineSoFar.isEmpty() ? word : lineSoFar + " " + word;
                if (face.width(tried) <= room) {
                    lineSoFar.setLength(0);
                    lineSoFar.append(tried);
                    continue;
                }
                if (!lineSoFar.isEmpty()) out.add(lineSoFar.toString());
                // A word wider than the line on its own is cut where it has to be.
                while (face.width(word) > room && word.length() > 1) {
                    int end = word.length() - 1;
                    while (end > 1 && face.width(word.substring(0, end)) > room) end--;
                    out.add(word.substring(0, end));
                    word = word.substring(end);
                }
                lineSoFar.setLength(0);
                lineSoFar.append(word);
            }
            if (!lineSoFar.isEmpty()) out.add(lineSoFar.toString());
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
        if (out.size() > MOST_LINES) {
            List<String> cut = new ArrayList<>(out.subList(0, MOST_LINES));
            cut.set(MOST_LINES - 1, fit(face, cut.get(MOST_LINES - 1) + " …", Math.round(room)));
            return cut;
        }
        return out;
    }
}

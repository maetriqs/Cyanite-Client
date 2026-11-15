package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * The server list in Cyanite's look: each server on a card (lit when picked), a graph of its recent pings at the
 * row's end ({@link ServerPings}), and the screen's buttons and title redrawn where the game put them.
 */
public final class HomeServers extends HomeColumn {
    /** The ping graph, in the game's GUI units: how wide, the latest ping's text and the bars under it. */
    public static final int GRAPH_W = 46;
    private static final float GRAPH_CAP = 4.5f;
    private static final int BARS_TOP = 8, BARS_H = 9;
    private static final int TITLE = 11;
    /** Square buttons this narrow are another mod's icons (No Chat Reports puts two by the footer): left as they are. */
    private static final int ICON = 24;
    private static final int AMBER = 0xFFF2B33D;

    private static HomeServers current;

    private StringWidget title;

    private HomeServers(JoinMultiplayerScreen screen) {
        super(screen, b -> Screens.getButtons(screen).add(b), l -> Screens.getButtons(screen).remove(l));
    }

    /** The game made the server list, or made its buttons again. */
    public static void init(JoinMultiplayerScreen screen) {
        HomeServers home = current != null && current.screen == screen ? current : new HomeServers(screen);
        if (home != current) ServerPings.opened();
        home.forget();
        current = home;
        ScreenEvents.afterBackground(screen).register((s, g, mouseX, mouseY, partialTick) -> home.render(g));
        ScreenEvents.afterTick(screen).register(s -> ServerPings.tick(screen.getServers()));
        ScreenEvents.remove(screen).register(s -> ServerPings.closed());
    }

    /** {@code list} is the server list's, drawn Cyanite's way. */
    public static boolean styles(Object list) {
        return list instanceof ServerSelectionList && Home.themed();
    }

    // ---- The buttons and title, where the game put them

    @Override
    void style() {
        String text = screen.getTitle().getString();
        int index = 0;
        for (GuiEventListener child : List.copyOf(screen.children())) {
            if (child instanceof StringWidget s && s.visible && s.getMessage().getString().equals(text)) {
                title = s;
                hide(s);
            } else if (child instanceof Button b && b.visible && !(b instanceof PlainTextButton) && !b.getMessage().getString().isBlank()
                && b.getWidth() > ICON) {
                standIn(b, Kind.BUTTON, index++);
            }
        }
    }

    @Override
    void dropped() {
        title = null;
    }

    private void render(GuiGraphics g) {
        if (!Home.themed()) {
            restore();
            return;
        }
        arrange();
        if (m == null || title == null) return;
        Home.begin(g, m);
        int gs = m.guiScale();
        int cap = m.u(TITLE);
        String text = title.getMessage().getString();
        Text.Face bold = Text.face(true, cap, null);
        float tracking = 0.04f * cap;
        float in = entrance(0);
        int midY = (title.getY() * 2 + title.getHeight()) * gs / 2;
        bold.draw(g, text, Math.round(m.cx() - bold.width(text, tracking) / 2f), midY - cap / 2 - Math.round((1 - in) * m.u(4)),
            Theme.fade(Theme.TEXT, in), tracking);
        Home.end(g);
    }

    @Override
    Home.Metrics metrics() {
        return Home.metrics(0, 0);
    }

    @Override
    int columnTop() {
        return 0;
    }

    @Override
    void placeMore() {
        int gs = m.guiScale();
        for (StandIn s : standIns) {
            Button b = s.original;
            s.place(b.getX() * gs, b.getY() * gs, b.getWidth() * gs, b.getHeight() * gs);
        }
    }

    // ---- The list's rows (GUI units in, drawn in framebuffer pixels)

    /** A row's card, a little wider than the row so the icon doesn't touch its edge. */
    public static void card(GuiGraphics g, int x, int y, int w, int h, boolean hovered, boolean selected, boolean focused) {
        Home.Metrics m = Home.metrics(0, 0);
        if (m == null) return;
        int gs = m.guiScale();
        Home.begin(g, m);
        int px = (x - 3) * gs;
        int pw = (w + 6) * gs;
        int gap = Math.max(1, gs / 2);
        int py = y * gs + gap;
        int ph = h * gs - 2 * gap;
        int radius = Math.round(Theme.radius() * 1.4f * m.s());
        int edge = Math.max(1, Math.round(m.s() / 2f));
        float lit = selected ? (focused ? 1 : 0.7f) : hovered ? 0.35f : 0;
        Render2D.rect(g, px, py, pw, ph, radius, Theme.mix(Theme.BORDER, Theme.accent(), lit));
        Render2D.rect(g, px + edge, py + edge, pw - 2 * edge, ph - 2 * edge, Math.max(0, radius - edge),
            Theme.mix(Theme.HEADER, Theme.HEADER_HOVER, hovered || selected ? 1 : 0));
        Home.end(g);
    }

    /** The server's recent pings as bars ending at {@code right}, under the latest one in words. */
    public static void graph(GuiGraphics g, ServerData server, int right, int top) {
        Deque<Long> pings = ServerPings.of(server.ip);
        if (pings.isEmpty()) return;
        Home.Metrics m = Home.metrics(0, 0);
        if (m == null) return;
        int gs = m.guiScale();
        Home.begin(g, m);
        int r = right * gs;
        int w = GRAPH_W * gs;
        int t = top * gs;

        long last = pings.getLast();
        String said = last < 0 ? "no answer" : last + " ms";
        int cap = Math.round(GRAPH_CAP * gs);
        Text.Face face = Text.face(false, cap, null);
        face.draw(g, said, r - Math.round(face.width(said)), t, last < 0 ? Theme.DANGER : Theme.TEXT_DIM);

        int bottom = t + (BARS_TOP + BARS_H) * gs;
        int tall = BARS_H * gs;
        int line = Math.max(1, gs / 2);
        g.fill(r - w, bottom - line, r, bottom, Theme.fade(Theme.TRACK, 0.7f));
        long most = 200;
        for (long ping : pings) most = Math.max(most, ping);
        float slot = w / (float) ServerPings.KEEP;
        int bar = Math.max(1, Math.round(slot * 0.6f));
        int i = 0;
        for (Iterator<Long> it = pings.descendingIterator(); it.hasNext(); i++) {
            long ping = it.next();
            int bx = Math.round(r - (i + 1) * slot + (slot - bar));
            if (ping < 0) {
                g.fill(bx, bottom - tall, bx + bar, bottom, Theme.fade(Theme.DANGER, 0.55f));
                continue;
            }
            // Square root, so one slow answer doesn't flatten the rest into dots.
            int bh = Math.max(line * 2, Math.round((float) Math.sqrt(ping / (double) most) * tall));
            int color = ping < 150 ? Theme.accent() : ping < 300 ? Theme.mix(Theme.accent(), AMBER, 0.6f) : AMBER;
            g.fill(bx, bottom - bh, bx + bar, bottom, color);
        }
        Home.end(g);
    }
}

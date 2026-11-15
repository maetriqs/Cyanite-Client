package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.NameProtect;
import com.cyanite.client.system.Online;
import com.cyanite.client.system.Updater;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The title screen's buttons in Cyanite's look, under the crystal and wordmark. Other mods' text lines and
 * anything that isn't a button are left as they are. Cyanite adds its menu, a row back onto the server you last
 * played on, your account in the corner, and a note after it has updated itself.
 */
public final class HomeTitle extends HomeColumn {
    private static final int CARD_W = 200, CARD_H = 46;
    private static final String VERSION = FabricLoader.getInstance().getModContainer("cyanite_client")
        .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
    private static final String VERSIONS = "Cyanite " + VERSION + "  ·  Minecraft " + SharedConstants.getCurrentVersion().name();

    /** The version that ran before this one, when Cyanite has updated itself since; null once dismissed. */
    private static String updatedFrom;
    private static boolean versionSeen;
    private static String skinName;
    private static Supplier<PlayerSkin> skin;

    private final List<StandIn> links = new ArrayList<>();
    private StandIn news, dismiss, account;
    private ServerData server;

    private int footerY;
    private int footerBelow;
    private int linksLeft;

    public HomeTitle(Screen screen, Consumer<AbstractButton> add, Consumer<GuiEventListener> remove) {
        super(screen, add, remove);
    }

    // ---- Standing in for the screen's buttons

    @Override
    void style() {
        Button single = null, multi = null, realms = null, mods = null, options = null, quit = null;
        Button language = null, accessibility = null, credits = null;
        List<Button> others = new ArrayList<>();
        for (GuiEventListener child : List.copyOf(screen.children())) {
            if (!(child instanceof Button b) || !b.visible) continue;
            String key = b.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : "";
            if (key.equals("menu.singleplayer") && single == null) single = b;
            else if (key.equals("menu.multiplayer") && multi == null) multi = b;
            else if (key.equals("menu.online") && realms == null) realms = b;
            else if (key.startsWith("modmenu.") && mods == null) mods = b;
            else if (key.equals("menu.options") && options == null) options = b;
            else if (key.equals("menu.quit") && quit == null) quit = b;
            else if (key.equals("options.language") && language == null) language = b;
            else if (key.equals("options.accessibility") && accessibility == null) accessibility = b;
            else if (key.equals("title.credits") && credits == null) credits = b;
            else if (!(b instanceof PlainTextButton) && !b.getMessage().getString().isBlank()) others.add(b);
        }

        line(Home.BIG, single, null);
        line(Home.BIG, multi, null);
        server = HomeServer.get();
        if (server != null) ours(Kind.SERVER, Component.literal("Rejoin " + server.name), Home.ROW);
        ours(Kind.MENU, Component.literal("Cyanite Menu"), Home.ROW);
        line(Home.ROW, mods, realms);
        line(Home.ROW, options, quit);
        for (int i = 0; i < others.size(); i += 2) line(Home.ROW, others.get(i), i + 1 < others.size() ? others.get(i + 1) : null);
        for (Button link : new Button[] {language, accessibility, credits}) {
            if (link != null) links.add(standIn(link, Kind.LINK, lines.size()));
        }

        if (!versionSeen) {
            versionSeen = true;
            String last = HomeData.lastVersion();
            if (last != null && !last.equals(VERSION) && !VERSION.isEmpty()) updatedFrom = last;
            if (!VERSION.isEmpty()) HomeData.ran(VERSION);
        }
        news = corner(Kind.NEWS, Component.literal("What's new in Cyanite " + VERSION));
        dismiss = corner(Kind.DISMISS, Component.literal("Dismiss"));
        account = corner(Kind.ACCOUNT, Component.literal("Accounts: " + accountName()));
    }

    /** A server joined or forgotten since. */
    @Override
    boolean changed() {
        return server != HomeServer.get();
    }

    @Override
    void dropped() {
        links.clear();
        news = dismiss = account = null;
    }

    @Override
    String label(StandIn b, String text) {
        return b.kind == Kind.ACCOUNT ? accountName() : super.label(b, text);
    }

    // ---- Each frame

    @Override
    void frame() {
        if (server != null) HomeServer.update();
        // Name Protect switched on or off, or another account picked: the chip follows.
        String name = accountName();
        if (!name.equals(account.label)) account.setMessage(Component.literal("Accounts: " + name));
    }

    @Override
    Home.Metrics metrics() {
        Home.rows = rows;
        return Home.metrics(rows);
    }

    @Override
    int columnTop() {
        return m.rowsTop();
    }

    @Override
    float entrance(int index) {
        return Home.entrance(index);
    }

    @Override
    void placeMore() {
        // The footer keeps inside the game's bottom line of text: other mods put theirs above that.
        int cap = m.u(LINK);
        Text.Face face = Text.face(false, cap, null);
        footerBelow = Math.min(m.u(8), Math.max(m.u(3), (10 * m.guiScale() - cap) / 2));
        footerY = m.height() - footerBelow - cap;
        int right = m.width() - m.u(INSET);
        linksLeft = m.width();
        for (int i = links.size() - 1; i >= 0; i--) {
            StandIn link = links.get(i);
            link.textW = (int) Math.ceil(face.width(link.label));
            link.textX = right - link.textW;
            link.place(link.textX - m.u(3), footerY - m.u(4), link.textW + m.u(6), Math.min(cap + m.u(8), m.height() - footerY + m.u(4)));
            linksLeft = link.textX;
            right = link.textX - m.u(16);
        }

        // The update note, bottom left, while it's clear of the column.
        int columnLeft = m.cx() - m.u(Home.COLUMN) / 2;
        int cardW = m.u(CARD_W);
        int cardH = m.u(CARD_H);
        int cardX = m.u(INSET);
        int cardY = footerY - m.u(12) - cardH;
        boolean card = updatedFrom != null && cardX + cardW <= columnLeft - m.u(12) && cardY >= m.u(INSET);
        int close = m.u(26);
        news.show(card);
        dismiss.show(card);
        news.place(cardX, cardY, cardW - close, cardH);
        dismiss.place(cardX + cardW - close, cardY, close, cardH);

        // Your account, top right.
        Text.Face small = Text.face(false, m.u(LINK), null);
        int chipH = m.u(22);
        int chipW = m.u(4) + m.u(14) + m.u(7) + (int) Math.ceil(small.width(account.label)) + m.u(9);
        account.place(m.width() - m.u(INSET) - chipW, m.u(10), chipW, chipH);
    }

    /** Under the buttons: Cyanite's backdrop, then the crystal and wordmark. */
    public void backdrop(GuiGraphics g) {
        if (m == null) return;
        Home.begin(g, m);
        // With "Panorama" the screen has just drawn the game's own: it only needs tinting.
        if (Backdrop.panorama()) Backdrop.overPanorama(g, m);
        else Backdrop.draw(g, m, 1);
        if (!Home.lockupAbove) Home.lockup(g, m, 1, true);
        Home.end(g);
    }

    /** The versions, bottom left; the links on the right draw themselves. */
    public void footer(GuiGraphics g) {
        if (m == null) return;
        Home.begin(g, m);
        Text.Face face = Text.face(false, m.u(LINK), null);
        int x = m.u(INSET);
        String versions = Online.label() instanceof String online ? VERSIONS + "  ·  " + online : VERSIONS;
        if (x + face.width(versions) + m.u(24) > linksLeft) versions = VERSIONS;
        if (x + face.width(versions) + m.u(24) <= linksLeft) {
            face.draw(g, versions, x, footerY, Theme.fade(Theme.TEXT_DIM, Home.entrance(lines.size())));
        }
        Home.end(g);
    }

    private static String accountName() {
        return NameProtect.protect(Minecraft.getInstance().getUser().getName());
    }

    /** Your skin, looked up once per account (the default one until it's in, and while Name Protect hides who you are). */
    private static PlayerSkin skin() {
        User user = Minecraft.getInstance().getUser();
        if (!accountName().equals(user.getName())) return DefaultPlayerSkin.getDefaultSkin();
        if (skin == null || !user.getName().equals(skinName)) {
            skinName = user.getName();
            skin = Minecraft.getInstance().getSkinManager().createLookup(new GameProfile(user.getProfileId(), user.getName()), false);
        }
        return skin.get();
    }

    // ---- Cyanite's own

    @Override
    void press(StandIn b, InputWithModifiers input) {
        Minecraft mc = Minecraft.getInstance();
        switch (b.kind) {
            case SERVER -> HomeServer.join(screen);
            case ACCOUNT -> mc.setScreen(ClickGuiScreen.openAccounts(screen));
            case NEWS -> {
                String page = Updater.releasePage(VERSION);
                if (page != null) Util.getPlatform().openUri(URI.create(page));
            }
            case DISMISS -> {
                updatedFrom = null;
                // The keyboard was on the note: it goes back to the top.
                if (screen.getFocused() == b || screen.getFocused() == news) screen.setFocused(null);
            }
            default -> super.press(b, input);
        }
    }

    @Override
    void drawOwn(GuiGraphics g, StandIn b, float in) {
        switch (b.kind) {
            case LINK -> link(g, b, in);
            case NEWS -> news(g, b, in);
            case DISMISS -> dismiss(g, b, in);
            case ACCOUNT -> account(g, b, in);
            default -> super.drawOwn(g, b, in);
        }
    }

    @Override
    void inside(GuiGraphics g, StandIn b, int x, int y, int w, int h, float in) {
        if (b.kind == Kind.SERVER) serverRow(g, server, "Rejoin", null, x, y, w, h, in);
        else super.inside(g, b, x, y, w, h, in);
    }

    private void link(GuiGraphics g, StandIn b, float in) {
        float t = b.hover;
        int cap = m.u(LINK);
        Text.Face face = Text.face(false, cap, null);
        face.draw(g, b.label, b.textX, footerY, Theme.fade(b.active ? Theme.mix(Theme.TEXT_DIM, Theme.TEXT, t) : Theme.TEXT_FAINT, in));
        if (t > 0.01f) {
            int line = Math.max(1, Math.round(m.s() / 2f));
            int y = footerY + cap + Math.min(m.u(3), Math.max(1, footerBelow - line - 1));
            g.fill(b.textX, y, b.textX + b.textW, y + line, Theme.fade(Theme.accent(), t * in));
        }
    }

    /** The update note: what this is now and what it was, and a way to what's new. The close cross draws itself. */
    private void news(GuiGraphics g, StandIn b, float in) {
        int x = b.px;
        int y = b.py + Math.round((1 - in) * m.u(8));
        int w = b.pw + dismiss.pw;
        int h = b.ph;
        float t = Math.max(b.hover, 0.35f * dismiss.hover);
        plate(g, x, y, w, h, t, true, in);

        int cap = m.u(LABEL);
        int small = m.u(LINK);
        int pad = m.u(12);
        int lineGap = m.u(7);
        int top = y + (h - cap - lineGap - small) / 2;
        int dot = Math.max(2, m.u(4));
        Render2D.rect(g, x + pad, top + (cap - dot) / 2, dot, dot, dot, Theme.fade(Theme.accent(), in));
        Text.Face face = Text.face(false, cap, null);
        face.draw(g, fit(face, "Updated to Cyanite " + VERSION, b.pw - pad - dot - m.u(7)), x + pad + dot + m.u(7), top, Theme.fade(Theme.TEXT, in));

        Text.Face faceSmall = Text.face(false, small, null);
        String from = "From " + updatedFrom + "  ·  ";
        String more = "See what's new";
        int lineY = top + cap + lineGap;
        int fromX = x + pad + dot + m.u(7);
        faceSmall.draw(g, from, fromX, lineY, Theme.fade(Theme.TEXT_DIM, in));
        int moreX = Math.round(fromX + faceSmall.width(from));
        faceSmall.draw(g, more, moreX, lineY, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.accent(), b.hover), in));
        if (b.hover > 0.01f) {
            int line = Math.max(1, Math.round(m.s() / 2f));
            g.fill(moreX, lineY + small + m.u(2), Math.round(moreX + faceSmall.width(more)), lineY + small + m.u(2) + line, Theme.fade(Theme.accent(), b.hover * in));
        }
    }

    private void dismiss(GuiGraphics g, StandIn b, float in) {
        Text.Face face = Text.face(false, m.u(9), null);
        String cross = "×";
        int y = b.py + Math.round((1 - in) * m.u(8));
        face.draw(g, cross, b.px + Math.round((b.pw - m.u(4) - face.width(cross)) / 2f), y + (b.ph - m.u(9)) / 2,
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT, b.hover), in));
    }

    /** Your head and name, top right. */
    private void account(GuiGraphics g, StandIn b, float in) {
        int x = b.px;
        int y = b.py - Math.round((1 - in) * m.u(6));
        plate(g, x, y, b.pw, b.ph, b.hover, true, in);
        int head = m.u(14);
        int headX = x + m.u(4) + m.u(1);
        PlayerFaceRenderer.draw(g, skin(), headX, y + (b.ph - head) / 2, head, Theme.fade(0xFFFFFFFF, in));
        Text.Face face = Text.face(false, m.u(LINK), null);
        face.draw(g, b.label, headX + head + m.u(7), y + (b.ph - m.u(LINK)) / 2, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, b.hover), in));
    }
}

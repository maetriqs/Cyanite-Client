package com.cyanite.client.gui;

import com.cyanite.client.system.Disconnects;
import com.cyanite.client.system.Rejoin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;

/** In place of the disconnected screen while {@link Rejoin} waits to go back: why, a countdown, and Rejoin now / Stop. */
public class RejoinScreen extends Screen {
    private static final int REASON_LINES = 3;

    public RejoinScreen() {
        super(Component.literal("Rejoining"));
    }

    @Override
    protected void init() {
        int y = height / 2 + 30;
        addRenderableWidget(Button.builder(Component.literal("Rejoin now"), b -> Rejoin.now()).bounds(width / 2 - 102, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> onClose()).bounds(width / 2 + 2, y, 100, 20).build());
    }

    @Override
    public void tick() {
        // Called off or switched off some other way: nothing left to wait for.
        if (!Rejoin.counting()) minecraft.setScreen(serverList());
    }

    @Override
    public void onClose() {
        Rejoin.cancel();
        minecraft.setScreen(serverList());
    }

    private static Screen serverList() {
        return new JoinMultiplayerScreen(new TitleScreen());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = height / 2 - 46;
        String server = Rejoin.serverName();
        g.drawCenteredString(font, Rejoin.moduleName() + ": rejoining " + (server.isEmpty() ? "the server" : server), width / 2, y, Theme.accentText());
        long left = Rejoin.millisLeft();
        String status = left == 0 ? "Rejoining…" : String.format(Locale.ROOT, "In %.1fs, try %d of %d", left / 1000.0, Rejoin.tries(), Rejoin.maxTries());
        g.drawCenteredString(font, status, width / 2, y + 14, 0xFFB8C0CC);
        List<FormattedCharSequence> lines = font.split(Component.literal(Disconnects.brief(Rejoin.why(), 300)), Math.max(100, width - 60));
        for (int i = 0; i < Math.min(REASON_LINES, lines.size()); i++) {
            g.drawCenteredString(font, lines.get(i), width / 2, y + 34 + i * 11, 0xFF8A93A3);
        }
    }
}

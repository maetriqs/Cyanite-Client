package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The pause between leaving and rejoining: a countdown, with buttons to skip it or call it off. */
public class RelogScreen extends Screen {
    private final ServerData server;
    private final long connectAt;
    private boolean connecting;

    public RelogScreen(ServerData server, long waitMillis) {
        super(Component.literal("Relogging"));
        this.server = server;
        this.connectAt = System.currentTimeMillis() + waitMillis;
    }

    @Override
    protected void init() {
        int y = height / 2 + 14;
        addRenderableWidget(Button.builder(Component.literal("Rejoin now"), b -> connect()).bounds(width / 2 - 102, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 2, y, 100, 20).build());
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() >= connectAt) connect();
    }

    private void connect() {
        if (connecting) return;
        connecting = true;
        // Same parent as vanilla's Disconnect button, so backing out of a failed join lands on the server list.
        ConnectScreen.startConnecting(serverList(), minecraft, ServerAddress.parseString(server.ip), server, false, null);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(serverList());
    }

    private static Screen serverList() {
        return new JoinMultiplayerScreen(new TitleScreen());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        long left = Math.max(0, connectAt - System.currentTimeMillis());
        g.drawCenteredString(font, "Relogging to " + server.name, width / 2, height / 2 - 22, Theme.accentText());
        String status = left == 0 ? "Rejoining…" : String.format(Locale.ROOT, "Rejoining in %.1fs", left / 1000.0);
        g.drawCenteredString(font, status, width / 2, height / 2 - 8, 0xFFB8C0CC);
    }
}

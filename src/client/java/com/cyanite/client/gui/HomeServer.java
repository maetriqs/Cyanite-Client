package com.cyanite.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;

/**
 * The server you last played on, for the title screen's rejoin row: your server list's entry for it when there is
 * one (its name and icon), asked how it's doing the way the server list asks, now and then while the row shows.
 */
final class HomeServer {
    /** How often the row asks the server again while it's showing. */
    private static final long REPING = 60_000;
    /** A ping with no answer by now counts as no answer. */
    private static final long GIVE_UP = 8_000;
    private static final ServerStatusPinger PINGER = new ServerStatusPinger();

    private static ServerData data;
    private static String ip = "";
    private static FaviconTexture icon;
    private static byte[] iconBytes;
    private static long pingedAt, tickedAt;

    private HomeServer() {
    }

    /** Joined a world: a server is remembered (singleplayer and Realms aren't). */
    static void joined(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server == null || mc.isLocalServer() || server.isRealm()) return;
        HomeData.joined(server.name, server.ip);
    }

    /** The last server, or null if there isn't one. */
    static ServerData get() {
        HomeData.Server last = HomeData.lastServer();
        if (last == null) {
            forget();
            return null;
        }
        if (data == null || !last.ip().equals(ip)) {
            forget();
            ip = last.ip();
            data = find(last);
        }
        return data;
    }

    /**
     * Your server list's entry with the same address (read afresh, so its resource pack choice comes along when
     * you join), or one made up from what was remembered.
     */
    private static ServerData find(HomeData.Server last) {
        ServerList list = new ServerList(Minecraft.getInstance());
        list.load();
        for (int i = 0; i < list.size(); i++) {
            ServerData entry = list.get(i);
            if (entry.ip.equalsIgnoreCase(last.ip())) return entry;
        }
        return new ServerData(last.name(), last.ip(), ServerData.Type.OTHER);
    }

    private static void forget() {
        data = null;
        ip = "";
        iconBytes = null;
        pingedAt = 0;
        if (icon != null) {
            FaviconTexture old = icon;
            icon = null;
            // A frame late: this frame may have drawn with it.
            Minecraft.getInstance().schedule(old::close);
        }
    }

    /** Each frame the row shows: asks the server when it's time, and keeps its icon up to date. */
    static void update() {
        if (data == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        if (now - tickedAt >= 50) {
            tickedAt = now;
            PINGER.tick();
        }
        if (pingedAt == 0 || now - pingedAt > REPING) {
            pingedAt = now;
            ping(mc, data);
        }
        byte[] bytes = data.getIconBytes();
        if (bytes != iconBytes) {
            iconBytes = bytes;
            if (icon == null) icon = FaviconTexture.forServer(mc.getTextureManager(), data.ip);
            try {
                if (bytes == null) icon.clear();
                else icon.upload(NativeImage.read(bytes));
            } catch (Exception e) {
                icon.clear();
            }
        }
    }

    private static void ping(Minecraft mc, ServerData target) {
        target.setState(ServerData.State.PINGING);
        EventLoopGroupHolder group = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
        Util.nonCriticalIoPool().execute(() -> {
            try {
                PINGER.pingServer(target, () -> { }, () -> target.setState(target.protocol == SharedConstants.getCurrentVersion().protocolVersion()
                    ? ServerData.State.SUCCESSFUL : ServerData.State.INCOMPATIBLE), group);
            } catch (Exception e) {
                target.setState(ServerData.State.UNREACHABLE);
            }
        });
    }

    /** The server's icon, or null while it hasn't one. */
    static Identifier icon() {
        return icon != null && iconBytes != null ? icon.textureLocation() : null;
    }

    /** No answer: it said no, or it's been too long. */
    static boolean offline() {
        if (data == null) return true;
        ServerData.State state = data.state();
        return state == ServerData.State.UNREACHABLE || state == ServerData.State.PINGING && Util.getMillis() - pingedAt > GIVE_UP;
    }

    /** {@code server} is the one asked here. */
    static boolean tracks(ServerData server) {
        return server != null && data != null && data.ip.equalsIgnoreCase(server.ip);
    }

    static boolean answered() {
        return data != null && (data.state() == ServerData.State.SUCCESSFUL || data.state() == ServerData.State.INCOMPATIBLE);
    }

    static void join(Screen from) {
        if (data == null) return;
        ConnectScreen.startConnecting(from, Minecraft.getInstance(), ServerAddress.parseString(data.ip), data, false, null);
    }
}

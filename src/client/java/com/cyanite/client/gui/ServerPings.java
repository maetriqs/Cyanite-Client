package com.cyanite.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Each server's recent pings, for the server list's graphs: the list's own answers, and while the list is open
 * one more every 15 s, asked with a copy of the entry so the row doesn't blank while it waits. No answer is -1.
 * Kept until the game closes.
 */
public final class ServerPings {
    static final int KEEP = 20;
    private static final long EVERY = 15_000;
    private static final long GIVE_UP = 8_000;
    private static final ServerStatusPinger PINGER = new ServerStatusPinger();
    private static final Deque<Long> NONE = new ArrayDeque<>();

    private static final Map<String, Deque<Long>> HISTORY = new HashMap<>();
    /** When each server was last asked, by the list or here. */
    private static final Map<String, Long> ASKED = new HashMap<>();
    private static final Map<String, Probe> OUT = new HashMap<>();

    private static final class Probe {
        final long since = Util.getMillis();
        boolean done;
    }

    private ServerPings() {
    }

    /** The list's own ping has an answer (or none). */
    public static void heard(ServerData server) {
        switch (server.state()) {
            case SUCCESSFUL, INCOMPATIBLE -> add(server.ip, server.ping);
            case UNREACHABLE -> add(server.ip, -1);
            default -> {
            }
        }
    }

    /** The server list opened: it asks every server itself first. */
    static void opened() {
        ASKED.clear();
    }

    static void closed() {
        PINGER.removeAll();
        OUT.clear();
    }

    /** Every tick while the server list is open. */
    static void tick(ServerList servers) {
        PINGER.tick();
        long now = Util.getMillis();
        for (Iterator<Map.Entry<String, Probe>> it = OUT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Probe> out = it.next();
            Probe probe = out.getValue();
            if (!probe.done && now - probe.since > GIVE_UP) {
                probe.done = true;
                add(out.getKey(), -1);
            }
            if (probe.done) it.remove();
        }
        for (int i = 0; i < servers.size(); i++) {
            ServerData server = servers.get(i);
            String key = key(server.ip);
            Long asked = ASKED.get(key);
            if (asked == null) {
                ASKED.put(key, now);
                continue;
            }
            if (now - asked >= EVERY && !OUT.containsKey(key)) ask(server, key);
        }
    }

    private static void ask(ServerData server, String key) {
        ASKED.put(key, Util.getMillis());
        Probe probe = new Probe();
        OUT.put(key, probe);
        ServerData copy = new ServerData(server.name, server.ip, ServerData.Type.OTHER);
        Minecraft mc = Minecraft.getInstance();
        EventLoopGroupHolder group = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
        Util.nonCriticalIoPool().execute(() -> {
            try {
                PINGER.pingServer(copy, () -> { }, () -> mc.execute(() -> answer(key, probe, copy.ping)), group);
            } catch (Exception e) {
                mc.execute(() -> answer(key, probe, -1));
            }
        });
    }

    private static void answer(String key, Probe probe, long ping) {
        if (probe.done) return;
        probe.done = true;
        add(key, ping);
    }

    static void add(String ip, long ping) {
        String key = key(ip);
        ASKED.put(key, Util.getMillis());
        Deque<Long> pings = HISTORY.computeIfAbsent(key, k -> new ArrayDeque<>());
        pings.addLast(ping);
        while (pings.size() > KEEP) pings.removeFirst();
    }

    /** Oldest first; empty if it hasn't been asked. */
    static Deque<Long> of(String ip) {
        return HISTORY.getOrDefault(key(ip), NONE);
    }

    private static String key(String ip) {
        return ip.strip().toLowerCase(Locale.ROOT);
    }
}

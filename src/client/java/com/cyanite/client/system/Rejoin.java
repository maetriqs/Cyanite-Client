package com.cyanite.client.system;

import com.cyanite.client.gui.RejoinScreen;
import com.cyanite.client.mixin.DisconnectedScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Back onto the server after the connection drops, for modules that carry on afterwards (Auto
 * Mine). When the game shows its disconnected screen it reads why ({@link Disconnects}): a reset,
 * a restart or a full server gets a countdown and a rejoin, tried again with longer waits while the
 * server isn't back; a ban, a dead session or a kick from staff ends it, and the module stops. A
 * module that left on purpose ({@link Disconnects#leave}) is never brought back. Once the world's
 * back, the module picks up from there and says so ({@link #back}).
 */
public final class Rejoin {
    /** A module that can carry on after a dropped connection. */
    public interface Client {
        /** Wants to come back if the connection drops now. */
        boolean wantsRejoin();

        /** Comes back after a disconnect it doesn't recognise as well. */
        boolean rejoinUnknown();

        /** The most tries in a row before giving up. */
        int rejoinTries();

        /** The first wait after a dropped connection, in milliseconds (longer for a restart). */
        long rejoinDelay();

        /** Not coming back: stop, saying why. */
        void rejoinEnded(String why);
    }

    /** The longest wait between tries. */
    private static final long LONGEST = 120_000;
    /** How much of the server's reason goes in a toast. */
    private static final int BRIEF = 64;

    /** The server you're on (or were last on), null for singleplayer and Realms. */
    private static ServerData server;
    /** The module waiting to get back on. */
    private static Module who;
    private static int tries;
    private static long connectAt;
    private static boolean connecting;
    private static long joinedAt;
    private static String why = "";
    /** The disconnected screen already read, so each one is judged once. */
    private static Screen handled;
    /** The last disconnected screen a module was on for (rejoining or stopping): the screen's own countdown leaves it alone. */
    private static Screen claimed;

    private Rejoin() {
    }

    /** End of every client tick, in a world or not. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (who != null && !who.isEnabled()) clear();
        if (mc.level != null) {
            ServerData current = mc.isLocalServer() ? null : mc.getCurrentServer();
            server = current != null && !current.isRealm() ? current : null;
            if (who != null && connecting) {
                connecting = false;
                joinedAt = System.currentTimeMillis();
            }
            return;
        }
        if (mc.screen instanceof DisconnectedScreen screen && screen != handled) {
            handled = screen;
            disconnected(mc, screen);
            return;
        }
        if (who == null) return;
        if (connectAt > 0) {
            // Back to the server list from the game's own screen (it stays up in Stealth Mode): called off.
            if (!(mc.screen instanceof RejoinScreen) && mc.screen != handled) {
                end("Rejoin called off");
                return;
            }
            if (System.currentTimeMillis() >= connectAt) connect(mc);
        } else if (connecting && (mc.screen instanceof JoinMultiplayerScreen || mc.screen instanceof TitleScreen)) {
            // Cancel on the connecting screen.
            end("Rejoin called off");
        }
    }

    private static void disconnected(Minecraft mc, DisconnectedScreen screen) {
        Module module = who != null ? who : wanting();
        if (module == null) return;
        claimed = screen;
        Client client = (Client) module;
        Component reason = ((DisconnectedScreenAccessor) screen).cyanite$details().reason();
        String text = Disconnects.text(reason);
        String brief = Disconnects.brief(text, BRIEF);
        if (Disconnects.leftOnPurpose()) {
            end(module, "Left the server (" + brief + ")");
            return;
        }
        if (server == null) {
            end(module, "Disconnected: " + brief);
            return;
        }
        Disconnects.Kind kind = Disconnects.judge(reason);
        if (kind == Disconnects.Kind.FINAL) {
            end(module, "Can't rejoin: " + brief);
            return;
        }
        if (kind == Disconnects.Kind.UNKNOWN && !client.rejoinUnknown()) {
            end(module, "Disconnected: " + brief);
            return;
        }
        if (tries >= client.rejoinTries()) {
            end(module, "Couldn't get back in after " + tries + (tries == 1 ? " try: " : " tries: ") + brief);
            return;
        }
        who = module;
        why = text;
        tries++;
        connecting = false;
        joinedAt = 0;
        // Doubled after every try that didn't get in, up to two minutes apart.
        long first = Math.max(1000, client.rejoinDelay() * kind.scale);
        connectAt = System.currentTimeMillis() + Math.min(first << Math.min(tries - 1, 6), Math.max(first, LONGEST));
        mc.setScreen(new RejoinScreen());
    }

    private static void connect(Minecraft mc) {
        connectAt = 0;
        connecting = true;
        // The same parent as the game's own Back button, so backing out lands on the server list.
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(server.ip), server, false, null);
    }

    /** The first module that's on and would come back. */
    private static Module wanting() {
        for (Module module : Modules.getAll()) {
            if (module instanceof Client client && module.isEnabled() && client.wantsRejoin()) return module;
        }
        return null;
    }

    private static void end(String why) {
        Module module = who;
        clear();
        if (module != null) ((Client) module).rejoinEnded(why);
    }

    private static void end(Module module, String why) {
        clear();
        ((Client) module).rejoinEnded(why);
    }

    private static void clear() {
        who = null;
        tries = 0;
        connectAt = 0;
        connecting = false;
        joinedAt = 0;
    }

    /** This module is getting back on: counting down, connecting, or back and not carrying on yet. */
    public static boolean waiting(Module module) {
        return module != null && who == module;
    }

    /** How long the world's been back after a rejoin, in milliseconds, or -1 while it isn't. */
    public static long backFor() {
        return joinedAt == 0 ? -1 : System.currentTimeMillis() - joinedAt;
    }

    /** Carrying on: the next disconnect starts counting tries afresh. */
    public static void back() {
        clear();
    }

    /** A module was on when this disconnected screen came up, and decided what happens next. */
    public static boolean claimed(Screen screen) {
        return screen != null && screen == claimed;
    }

    /** Why it left: the server's reason, on one line. */
    public static String why() {
        return why;
    }

    // ---- For the countdown screen

    public static boolean counting() {
        return who != null && connectAt > 0;
    }

    public static long millisLeft() {
        return Math.max(0, connectAt - System.currentTimeMillis());
    }

    public static int tries() {
        return tries;
    }

    public static int maxTries() {
        return who instanceof Client client ? client.rejoinTries() : 0;
    }

    public static String moduleName() {
        return who != null ? who.name : "";
    }

    public static String serverName() {
        return server != null ? server.name : "";
    }

    /** "Rejoin now". */
    public static void now() {
        if (connectAt > 0) connectAt = 1;
    }

    /** "Stop": the module stops too. */
    public static void cancel() {
        end("You called off the rejoin");
    }
}
